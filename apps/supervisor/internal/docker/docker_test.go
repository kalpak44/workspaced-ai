package docker

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

// daemon answers like the Engine API and records each request line.
type daemon struct {
	mu   sync.Mutex
	reqs []string
	body []byte
	pull string
}

func (d *daemon) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.reqs = append(d.reqs, r.Method+" "+r.URL.RequestURI())
	switch {
	case r.Method == "GET" && r.URL.Path == "/containers/workspaced-harness/json":
		_, _ = io.WriteString(w, `{"Id":"abc","State":{"Running":true},"Config":{"Labels":{"k":"v"}}}`)
	case r.Method == "GET" && r.URL.Path == "/containers/missing/json",
		r.Method == "GET" && r.URL.Path == "/images/ghcr.io/x/missing:1/json",
		r.Method == "DELETE" && r.URL.Path == "/containers/missing":
		w.WriteHeader(http.StatusNotFound)
		_, _ = io.WriteString(w, `{"message":"No such object"}`)
	case r.Method == "GET" && r.URL.Path == "/images/ghcr.io/x/harness:1/json":
		_, _ = io.WriteString(w, `{"Id":"sha256:1"}`)
	case r.Method == "POST" && r.URL.Path == "/images/create":
		_, _ = io.WriteString(w, `{"status":"Pulling from x/harness"}`+"\n"+d.pull)
	case r.Method == "POST" && r.URL.Path == "/containers/abc/stop":
		w.WriteHeader(http.StatusNotModified)
	case r.Method == "DELETE" && r.URL.Path == "/containers/abc":
		w.WriteHeader(http.StatusNoContent)
	case r.Method == "POST" && r.URL.Path == "/containers/create":
		if r.Header.Get("Content-Type") != "application/json" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		d.body, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"Id":"new","Warnings":[]}`)
	case r.Method == "POST" && r.URL.Path == "/containers/new/start":
		w.WriteHeader(http.StatusNoContent)
	default:
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = io.WriteString(w, `{"message":"boom"}`)
	}
}

func TestClientOverUnixSocket(t *testing.T) {
	// A short path: macOS caps a socket path at 104 bytes.
	sock := filepath.Join(t.TempDir(), "d.sock")
	l, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	d := &daemon{}
	srv := httptest.NewUnstartedServer(d)
	srv.Listener = l
	srv.Start()
	defer srv.Close()

	c := New(sock)
	ctx := context.Background()

	got, err := c.Inspect(ctx, "workspaced-harness")
	if err != nil || got.ID != "abc" || !got.Running || got.Labels["k"] != "v" {
		t.Fatalf("inspect: %+v %v", got, err)
	}
	if got, err := c.Inspect(ctx, "missing"); got != nil || err != nil {
		t.Fatalf("inspect missing: %+v %v", got, err)
	}
	if ok, err := c.ImageExists(ctx, "ghcr.io/x/harness:1"); !ok || err != nil {
		t.Fatalf("image exists: %v %v", ok, err)
	}
	if ok, err := c.ImageExists(ctx, "ghcr.io/x/missing:1"); ok || err != nil {
		t.Fatalf("image missing: %v %v", ok, err)
	}
	if err := c.Pull(ctx, "ghcr.io/x/harness:1"); err != nil {
		t.Fatalf("pull: %v", err)
	}
	d.mu.Lock()
	d.pull = `{"errorDetail":{"message":"manifest unknown"},"error":"manifest unknown"}`
	d.mu.Unlock()
	if err := c.Pull(ctx, "ghcr.io/x/harness:1"); err == nil || !strings.Contains(err.Error(), "manifest unknown") {
		t.Fatalf("pull error line: %v", err)
	}
	if err := c.Stop(ctx, "abc"); err != nil {
		t.Fatalf("stop already stopped: %v", err)
	}
	if err := c.Remove(ctx, "abc"); err != nil {
		t.Fatalf("remove: %v", err)
	}
	if err := c.Remove(ctx, "missing"); err != nil {
		t.Fatalf("remove missing: %v", err)
	}
	spec := ContainerSpec{Image: "ghcr.io/x/harness:1", Env: []string{"A=1"},
		HostConfig: HostConfig{Mounts: []Mount{{Type: "bind", Source: "/a", Target: "/b"}}, Init: true, Tmpfs: map[string]string{"/tmp": ""}}}
	id, err := c.Create(ctx, "workspaced-harness", spec)
	if err != nil || id != "new" {
		t.Fatalf("create: %q %v", id, err)
	}
	if err := c.Start(ctx, id); err != nil {
		t.Fatalf("start: %v", err)
	}
	if err := c.Start(ctx, "broken"); err == nil || !strings.Contains(err.Error(), "500 boom") {
		t.Fatalf("start error: %v", err)
	}

	d.mu.Lock()
	defer d.mu.Unlock()
	var sent map[string]any
	if err := json.Unmarshal(d.body, &sent); err != nil {
		t.Fatal(err)
	}
	hc := sent["HostConfig"].(map[string]any)
	if sent["Image"] != "ghcr.io/x/harness:1" || hc["Init"] != true || hc["Mounts"].([]any)[0].(map[string]any)["Type"] != "bind" {
		t.Errorf("create body %s", d.body)
	}
	for _, want := range []string{
		"POST /images/create?fromImage=ghcr.io%2Fx%2Fharness&tag=1",
		"POST /containers/create?name=workspaced-harness",
		"DELETE /containers/abc?force=true",
	} {
		found := false
		for _, r := range d.reqs {
			found = found || r == want
		}
		if !found {
			t.Errorf("no request %q in %v", want, d.reqs)
		}
	}
}

func TestSplitRef(t *testing.T) {
	for ref, want := range map[string][2]string{
		"ghcr.io/kalpak44/h:85c2bd5":      {"ghcr.io/kalpak44/h", "85c2bd5"},
		"localhost:5000/h":                {"localhost:5000/h", "latest"},
		"localhost:5000/h:1":              {"localhost:5000/h", "1"},
		"alpine":                          {"alpine", "latest"},
		"ghcr.io/k/h@sha256:0123456789ab": {"ghcr.io/k/h@sha256:0123456789ab", ""},
	} {
		from, tag := SplitRef(ref)
		if from != want[0] || tag != want[1] {
			t.Errorf("SplitRef(%q) = %q, %q; want %q", ref, from, tag, want)
		}
	}
}
