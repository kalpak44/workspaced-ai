// Package update stages authenticated releases and runs a supervisor with crash-safe rollback.
package update

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"time"
)

type Artifact struct {
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
}
type Release struct {
	Version   string              `json:"version"`
	Artifacts map[string]Artifact `json:"artifacts"`
}
type Status struct {
	Installed string    `json:"installed"`
	Desired   string    `json:"desired"`
	State     string    `json:"state"`
	Time      time.Time `json:"time"`
	Failure   string    `json:"failure,omitempty"`
}
type Request struct {
	Restart bool   `json:"restart,omitempty"`
	Version string `json:"version"`
	SHA256  string `json:"sha256"`
}
type Journal struct {
	Current  Request `json:"current"`
	Previous Request `json:"previous"`
	Pending  bool    `json:"pending"`
	Failed   string  `json:"failed,omitempty"`
	Status   Status  `json:"status"`
}

var validVersion = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$`)

// Save uses rename on the same filesystem; the journal is committed before a candidate runs.
func Save(path string, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	f, err := os.CreateTemp(filepath.Dir(path), ".update-*")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	if _, err = f.Write(b); err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = os.Rename(f.Name(), path); err != nil {
		return err
	}
	d, err := os.Open(filepath.Dir(path))
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
func Load(path string, v any) error {
	b, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(b, v)
}
func Binary(dir string, req Request) string { return filepath.Join(dir, "supervisor-"+req.SHA256) }
func Valid(req Request) bool {
	b, err := hex.DecodeString(req.SHA256)
	return validVersion.MatchString(req.Version) && err == nil && len(b) == sha256.Size && req.SHA256 == hex.EncodeToString(b)
}
func Verify(path string, req Request) bool {
	if !Valid(req) {
		return false
	}
	f, err := os.Open(path)
	if err != nil {
		return false
	}
	defer f.Close()
	h := sha256.New()
	if _, err = io.Copy(h, f); err != nil {
		return false
	}
	return hex.EncodeToString(h.Sum(nil)) == req.SHA256
}

// Stage trusts a checksum delivered by the authenticated HTTPS control plane, never one
// fetched beside the binary. Transport and checksum failures use fixed messages without URLs.
func Stage(ctx context.Context, dir, installed string, release *Release, restart bool) (Status, bool) {
	status := Status{Installed: installed, Time: time.Now().UTC(), State: "paused"}
	if release == nil || release.Version == "" {
		return status, false
	}
	status.Desired = release.Version
	fail := func(reason string) (Status, bool) {
		status.State = "failed"
		status.Failure = reason
		return status, false
	}
	if release.Version == installed {
		status.State = "current"
		return status, false
	}
	artifact, ok := release.Artifacts[runtime.GOARCH]
	if !ok {
		return fail("unsupported architecture")
	}
	req := Request{Version: release.Version, SHA256: artifact.SHA256, Restart: restart}
	if !Valid(req) {
		return fail("invalid release pin")
	}
	u, err := url.Parse(artifact.URL)
	if err != nil || u.Scheme != "https" || u.Host == "" || u.User != nil {
		return fail("release URL must use HTTPS")
	}
	var journal Journal
	if err := Load(filepath.Join(dir, "journal.json"), &journal); err != nil {
		return fail("launcher unavailable")
	}
	if journal.Failed == req.SHA256 {
		status = journal.Status
		status.Installed = installed
		status.Desired = release.Version
		return status, false
	}
	if err := download(ctx, dir, artifact, req); err != nil {
		return fail(err.Error())
	}
	if err := Save(filepath.Join(dir, "request.json"), req); err != nil {
		return fail("cannot stage update request")
	}
	status.State = "staged"
	return status, true
}
func download(ctx context.Context, dir string, artifact Artifact, pin Request) error {
	if Verify(Binary(dir, pin), pin) {
		return nil
	}
	client := &http.Client{Timeout: 2 * time.Minute, CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if req.URL.Scheme != "https" || len(via) >= 5 {
			return errors.New("redirect refused")
		}
		return nil
	}}
	return downloadWithClient(ctx, dir, artifact, pin, client)
}

func downloadWithClient(ctx context.Context, dir string, artifact Artifact, pin Request, client *http.Client) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, artifact.URL, nil)
	if err != nil {
		return errors.New("invalid release request")
	}
	resp, err := client.Do(req)
	if err != nil {
		return errors.New("release download failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return errors.New("release download rejected")
	}
	return store(dir, pin, resp.Body)
}
func store(dir string, pin Request, body io.Reader) error {
	f, err := os.CreateTemp(dir, ".binary-*")
	if err != nil {
		return errors.New("cannot create staged binary")
	}
	defer os.Remove(f.Name())
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(body, (64<<20)+1))
	if err == nil {
		err = f.Sync()
	}
	closeErr := f.Close()
	if err != nil || closeErr != nil {
		return errors.New("release download incomplete")
	}
	if n > 64<<20 || hex.EncodeToString(h.Sum(nil)) != pin.SHA256 {
		return errors.New("release checksum mismatch")
	}
	if err = os.Chmod(f.Name(), 0700); err != nil {
		return errors.New("cannot chmod release")
	}
	if err = os.Rename(f.Name(), Binary(dir, pin)); err != nil {
		return errors.New("cannot install release")
	}
	return nil
}
func (j *Journal) Rollback(reason string) {
	j.Failed = j.Current.SHA256
	desired := j.Current.Version
	restart := j.Current.Restart
	j.Current = j.Previous
	j.Current.Restart = j.Current.Restart || restart
	j.Pending = false
	j.Status = Status{Installed: j.Current.Version, Desired: desired, State: "rolled_back", Failure: reason, Time: time.Now().UTC()}
}
func (j *Journal) Activate(req Request) error {
	if !Valid(req) {
		return fmt.Errorf("invalid release request")
	}
	j.Previous = j.Current
	j.Current = req
	j.Pending = true
	j.Status = Status{Installed: j.Previous.Version, Desired: req.Version, State: "checking", Time: time.Now().UTC()}
	return nil
}
