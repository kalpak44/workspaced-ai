package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func pin(version, data string) Request {
	sum := sha256.Sum256([]byte(data))
	return Request{Version: version, SHA256: hex.EncodeToString(sum[:])}
}
func TestStoreVerificationAndPreservation(t *testing.T) {
	dir := t.TempDir()
	old := pin("old", "old")
	next := pin("new", "new")
	if err := store(dir, old, strings.NewReader("old")); err != nil {
		t.Fatal(err)
	}
	if err := store(dir, next, strings.NewReader("corrupt")); err == nil {
		t.Fatal("accepted corrupt binary")
	}
	if !Verify(Binary(dir, old), old) {
		t.Fatal("previous binary lost")
	}
	if _, err := os.Stat(Binary(dir, next)); !os.IsNotExist(err) {
		t.Fatal("corrupt candidate installed")
	}
	if err := store(dir, next, strings.NewReader("new")); err != nil {
		t.Fatal(err)
	}
	if !Verify(Binary(dir, next), next) {
		t.Fatal("candidate not verified")
	}
}
func TestStagePausePinArchitectureAndRollback(t *testing.T) {
	dir := t.TempDir()
	old := pin("old", "old")
	next := pin("new", "new")
	journal := Journal{Current: old}
	if err := Save(filepath.Join(dir, "journal.json"), journal); err != nil {
		t.Fatal(err)
	}
	status, ok := Stage(context.Background(), dir, "old", nil, false)
	if ok || status.State != "paused" {
		t.Fatal(status)
	}
	r := &Release{Version: "old"}
	status, ok = Stage(context.Background(), dir, "old", r, false)
	if ok || status.State != "current" {
		t.Fatal(status)
	}
	r.Version = "new"
	status, ok = Stage(context.Background(), dir, "old", r, false)
	if ok || status.Failure != "unsupported architecture" {
		t.Fatal(status)
	}
	r.Artifacts = map[string]Artifact{runtime.GOARCH: {URL: "http://example.com/binary", SHA256: next.SHA256}}
	status, ok = Stage(context.Background(), dir, "old", r, false)
	if ok || status.Failure != "release URL must use HTTPS" {
		t.Fatal(status)
	}
	r.Artifacts[runtime.GOARCH] = Artifact{URL: "https://example.com/binary", SHA256: next.SHA256}
	// Verified cached artifact avoids network but still requires a trusted HTTPS pin.
	if err := store(dir, next, strings.NewReader("new")); err != nil {
		t.Fatal(err)
	}
	status, ok = Stage(context.Background(), dir, "old", r, true)
	if !ok || status.State != "staged" {
		t.Fatal(status)
	}
	var req Request
	if err := Load(filepath.Join(dir, "request.json"), &req); err != nil {
		t.Fatal(err)
	}
	if !req.Restart || req.SHA256 != next.SHA256 {
		t.Fatal(req)
	}
	if err := journal.Activate(req); err != nil {
		t.Fatal(err)
	}
	journal.Rollback("candidate health timeout")
	if journal.Current.Version != old.Version || journal.Current.SHA256 != old.SHA256 || !journal.Current.Restart || journal.Pending || journal.Failed != next.SHA256 {
		t.Fatal(journal)
	}
	if err := Save(filepath.Join(dir, "journal.json"), journal); err != nil {
		t.Fatal(err)
	}
	status, ok = Stage(context.Background(), dir, "old", r, false)
	if ok || status.State != "rolled_back" {
		t.Fatal(status)
	}
}
func TestInvalidPinsCannotEscapeDirectory(t *testing.T) {
	for _, req := range []Request{{Version: "../../x", SHA256: strings.Repeat("a", 64)}, {Version: "v1", SHA256: "../bad"}, {Version: "v1", SHA256: strings.Repeat("A", 64)}} {
		if Valid(req) {
			t.Fatalf("accepted %#v", req)
		}
	}
}
func TestInterruptedActivationRollsBack(t *testing.T) {
	old := pin("old", "old")
	next := pin("new", "new")
	j := Journal{Current: old}
	if err := j.Activate(next); err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "journal.json")
	if err := Save(path, j); err != nil {
		t.Fatal(err)
	}
	var recovered Journal
	if err := Load(path, &recovered); err != nil {
		t.Fatal(err)
	}
	if !recovered.Pending {
		t.Fatal("missing pending transaction")
	}
	recovered.Rollback("interrupted health check")
	if recovered.Current != old || recovered.Status.State != "rolled_back" {
		t.Fatal(recovered)
	}
}
