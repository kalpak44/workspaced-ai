package agent

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const (
	testVM    = "3f1c2a9e-1111-4c2b-9a6e-123456789abc"
	testToken = "dG9rZW4tdGhhdC1tdXN0LW5ldmVyLWxlYWs"
	testUA    = "workspaced-supervisor/test"
)

// hub is a fake agent-hub that verifies signatures against the key it was enrolled with.
type hub struct {
	t  *testing.T
	mu sync.Mutex
	// enrollStatus is consumed in order; the last entry repeats.
	enrollStatus []int
	// harnessStatus is consumed like enrollStatus; a 200 answers with harness.
	harnessStatus []int
	harness       Harness
	// heartbeats are the statuses reported in order; READY once they run out, and
	// "" answers 204 as an older hub does.
	heartbeats []string
	pub        ed25519.PublicKey
	counts     map[string]int
	bad        []string
}

func newHub(t *testing.T, enrollStatus ...int) (*hub, *httptest.Server) {
	h := &hub{t: t, enrollStatus: enrollStatus, counts: map[string]int{},
		harnessStatus: []int{http.StatusOK},
		harness:       Harness{Image: testImage, Environment: map[string]string{testSecretName: testSecret, "OPENCODE_SERVER_PASSWORD": "pw-0"}}}
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return h, srv
}

func (h *hub) fail(format string, args ...any) {
	h.bad = append(h.bad, fmt.Sprintf(format, args...))
}

func (h *hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/api/v1/agent/tunnel" {
		h.tunnel(w, r)
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	body, _ := io.ReadAll(r.Body)
	h.counts[r.URL.Path]++
	if r.Method != http.MethodPost {
		h.fail("method %s", r.Method)
	}
	if ua := r.Header.Get("User-Agent"); ua != testUA {
		h.fail("user agent %q", ua)
	}
	switch r.URL.Path {
	case "/api/v1/agent/enroll":
		var req struct{ VMID, Token, PublicKey string }
		if err := json.Unmarshal(body, &req); err != nil {
			h.fail("enroll body: %v", err)
		}
		status := h.enrollStatus[0]
		if len(h.enrollStatus) > 1 {
			h.enrollStatus = h.enrollStatus[1:]
		}
		if req.VMID != testVM || req.Token != testToken {
			h.fail("enroll vmId=%q token mismatch=%v", req.VMID, req.Token != testToken)
		}
		if status == http.StatusNoContent {
			pub, err := base64.StdEncoding.DecodeString(req.PublicKey)
			if err != nil || len(pub) != ed25519.PublicKeySize {
				h.fail("public key %q: %v", req.PublicKey, err)
			}
			h.pub = pub
		}
		w.WriteHeader(status)
	case "/api/v1/agent/ready":
		if !h.verify(r, body) {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	case "/api/v1/agent/heartbeat":
		if !h.verify(r, body) {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		status := "READY"
		if len(h.heartbeats) > 0 {
			status, h.heartbeats = h.heartbeats[0], h.heartbeats[1:]
		}
		if status == "" {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"status": status})
	case "/api/v1/agent/harness":
		if !h.verify(r, body) {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		status := h.harnessStatus[0]
		if len(h.harnessStatus) > 1 {
			h.harnessStatus = h.harnessStatus[1:]
		}
		if status != http.StatusOK {
			w.WriteHeader(status)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(h.harness)
	default:
		h.fail("unexpected path %s", r.URL.Path)
		w.WriteHeader(http.StatusNotFound)
	}
}

// tunnel accepts a signed worker and leaves it idle; it must never come before ready.
func (h *hub) tunnel(w http.ResponseWriter, r *http.Request) {
	h.mu.Lock()
	h.counts[r.URL.Path]++
	ok := h.verify(r, nil)
	if r.Method != http.MethodGet {
		h.fail("tunnel method %s", r.Method)
	}
	if ua := r.Header.Get("User-Agent"); ua != testUA {
		h.fail("tunnel user agent %q", ua)
	}
	if h.counts["/api/v1/agent/ready"] == 0 {
		h.fail("tunnel worker before ready")
	}
	h.mu.Unlock()
	if !ok {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	c, err := websocket.Accept(w, r, nil)
	if err != nil {
		return
	}
	// Reads answer the pool's pings until the supervisor closes the worker.
	c.CloseRead(context.Background())
}

func (h *hub) verify(r *http.Request, body []byte) bool {
	if h.pub == nil {
		h.fail("signed request before enrollment")
		return false
	}
	if vm := r.Header.Get("X-Agent-Vm"); vm != testVM {
		h.fail("X-Agent-Vm %q", vm)
		return false
	}
	ts, err := strconv.ParseInt(r.Header.Get("X-Agent-Timestamp"), 10, 64)
	if err != nil || abs(time.Now().Unix()-ts) > 60 {
		h.fail("timestamp %q", r.Header.Get("X-Agent-Timestamp"))
		return false
	}
	sig, err := base64.StdEncoding.DecodeString(r.Header.Get("X-Agent-Signature"))
	if err != nil {
		h.fail("signature encoding: %v", err)
		return false
	}
	sum := sha256.Sum256(body)
	msg := r.Method + "\n" + r.URL.Path + "\n" + r.Header.Get("X-Agent-Timestamp") + "\n" + hex.EncodeToString(sum[:])
	if !ed25519.Verify(h.pub, []byte(msg), sig) {
		h.fail("signature does not verify for %s", r.URL.Path)
		return false
	}
	return true
}

func (h *hub) count(path string) int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.counts["/api/v1/agent/"+path]
}

func (h *hub) check() {
	h.t.Helper()
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, b := range h.bad {
		h.t.Error(b)
	}
}

type fixture struct {
	envFile, stateDir string
	logs              *strings.Builder
}

func newFixture(t *testing.T, baseURL string, withToken bool) fixture {
	dir := t.TempDir()
	f := fixture{envFile: filepath.Join(dir, "agent.env"), stateDir: filepath.Join(dir, "state"), logs: &strings.Builder{}}
	content := "WORKSPACED_VM_ID=" + testVM + "\nWORKSPACED_AGENT_URL=" + baseURL + "/api/v1\n"
	if withToken {
		content += "WORKSPACED_ENROLLMENT_TOKEN=" + testToken + "\n"
	}
	if err := os.WriteFile(f.envFile, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return f
}

// start runs the supervisor with millisecond intervals and returns a stop function.
func (f fixture) start(t *testing.T) (stop func()) {
	return f.startWith(t, newFakeDocker(t))
}

func (f fixture) startWith(t *testing.T, d *fakeDocker, opts ...func(*Supervisor)) (stop func()) {
	var mu sync.Mutex
	s := &Supervisor{
		EnvFile:       f.envFile,
		StateDir:      f.stateDir,
		HTTP:          &http.Client{Timeout: 2 * time.Second},
		UserAgent:     testUA,
		Log:           slog.New(slog.NewJSONHandler(lockedWriter{&mu, f.logs}, nil)),
		Now:           time.Now,
		BackoffMin:    time.Millisecond,
		BackoffMax:    4 * time.Millisecond,
		OperatorRetry: 5 * time.Millisecond,
		Heartbeat:     5 * time.Millisecond,
		Docker:        d,
		HealthURL:     d.healthURL,
		HealthPoll:    time.Millisecond,
		HealthTimeout: 200 * time.Millisecond,
	}
	for _, o := range opts {
		o(s)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- s.Run(ctx) }()
	return func() {
		t.Helper()
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Fatalf("Run returned %v", err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("Run did not stop after cancel")
		}
		mu.Lock()
		defer mu.Unlock()
		if strings.Contains(f.logs.String(), testToken) {
			t.Error("logs contain the enrollment token")
		}
		if strings.Contains(f.logs.String(), testSecret) {
			t.Error("logs contain a harness variable's value")
		}
	}
}

type lockedWriter struct {
	mu *sync.Mutex
	b  *strings.Builder
}

func (w lockedWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.b.Write(p)
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(time.Millisecond)
	}
}

func assertEnrolledState(t *testing.T, f fixture) {
	t.Helper()
	if _, err := os.Stat(filepath.Join(f.stateDir, "enrolled")); err != nil {
		t.Errorf("marker: %v", err)
	}
	data, err := os.ReadFile(f.envFile)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "WORKSPACED_ENROLLMENT_TOKEN") {
		t.Errorf("token line still present:\n%s", data)
	}
	if !strings.Contains(string(data), "WORKSPACED_VM_ID="+testVM+"\n") || !strings.Contains(string(data), "WORKSPACED_AGENT_URL=") {
		t.Errorf("other lines lost:\n%s", data)
	}
	if fi, _ := os.Stat(f.envFile); fi.Mode().Perm() != 0o600 {
		t.Errorf("env file mode %v", fi.Mode().Perm())
	}
	fi, err := os.Stat(filepath.Join(f.stateDir, "agent.key"))
	if err != nil || fi.Mode().Perm() != 0o600 || fi.Size() != ed25519.SeedSize {
		t.Errorf("agent.key: %v %v", fi, err)
	}
	if fi, _ := os.Stat(f.stateDir); fi.Mode().Perm() != 0o700 {
		t.Errorf("state dir mode %v", fi.Mode().Perm())
	}
}

func TestFreshEnrollThenReadyThenHeartbeat(t *testing.T) {
	// A 503 first proves server errors back off rather than give up.
	h, srv := newHub(t, http.StatusServiceUnavailable, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	stop := f.start(t)
	waitFor(t, "two heartbeats", func() bool { return h.count("heartbeat") >= 2 })
	stop()
	h.check()
	if n := h.count("enroll"); n != 2 {
		t.Errorf("enroll calls = %d, want 2", n)
	}
	if n := h.count("ready"); n != 1 {
		t.Errorf("ready calls = %d, want 1", n)
	}
	assertEnrolledState(t, f)
}

func TestRestartWithMarkerSkipsEnroll(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	stop := f.start(t)
	waitFor(t, "first heartbeat", func() bool { return h.count("heartbeat") >= 1 })
	stop()

	stop = f.start(t)
	waitFor(t, "second ready", func() bool { return h.count("ready") >= 2 })
	waitFor(t, "heartbeat after restart", func() bool { return h.count("heartbeat") >= 3 })
	stop()
	h.check()
	if n := h.count("enroll"); n != 1 {
		t.Errorf("enroll calls = %d, want 1", n)
	}
	assertEnrolledState(t, f)
}

func TestConflictAssumesEarlierEnrollment(t *testing.T) {
	h, srv := newHub(t, http.StatusConflict)
	f := newFixture(t, srv.URL, true)
	// The crashed earlier run left this key behind and the hub already holds it.
	if err := os.MkdirAll(f.stateDir, 0o700); err != nil {
		t.Fatal(err)
	}
	key, err := LoadOrCreateKey(filepath.Join(f.stateDir, "agent.key"))
	if err != nil {
		t.Fatal(err)
	}
	h.pub = key.Public().(ed25519.PublicKey)

	stop := f.start(t)
	waitFor(t, "heartbeat", func() bool { return h.count("heartbeat") >= 1 })
	stop()
	h.check()
	if n := h.count("enroll"); n != 1 {
		t.Errorf("enroll calls = %d, want 1", n)
	}
	assertEnrolledState(t, f)
}

func TestUnauthorizedKeepsRetrying(t *testing.T) {
	h, srv := newHub(t, http.StatusUnauthorized)
	f := newFixture(t, srv.URL, true)
	stop := f.start(t)
	waitFor(t, "repeated enroll attempts", func() bool { return h.count("enroll") >= 4 })
	stop()
	h.check()
	if h.count("ready") != 0 || h.count("heartbeat") != 0 {
		t.Error("signed requests sent without enrollment")
	}
	if _, err := os.Stat(filepath.Join(f.stateDir, "enrolled")); err == nil {
		t.Error("marker written after 401")
	}
	data, _ := os.ReadFile(f.envFile)
	if !strings.Contains(string(data), "WORKSPACED_ENROLLMENT_TOKEN=") {
		t.Error("token removed after 401")
	}
}

func TestSignMessageFormat(t *testing.T) {
	got := SignMessage("POST", "/api/v1/agent/heartbeat", 1700000000, nil)
	want := "POST\n/api/v1/agent/heartbeat\n1700000000\n" +
		"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	if got != want {
		t.Fatalf("got %q\nwant %q", got, want)
	}
	got = SignMessage("POST", "/x", 1, []byte("abc"))
	want = "POST\n/x\n1\nba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
	if got != want {
		t.Fatalf("got %q\nwant %q", got, want)
	}
}

func TestSignedRequestHeaders(t *testing.T) {
	seed := make([]byte, ed25519.SeedSize)
	key := ed25519.NewKeyFromSeed(seed)
	var got *http.Request
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()
	c := &Client{BaseURL: srv.URL + "/api/v1/", VMID: testVM, Key: key, HTTP: srv.Client(), UserAgent: testUA,
		Now: func() time.Time { return time.Unix(1700000000, 0) }}
	if _, err := c.Signed(context.Background(), "/agent/ready"); err != nil {
		t.Fatal(err)
	}
	if got.URL.Path != "/api/v1/agent/ready" || got.Header.Get("X-Agent-Timestamp") != "1700000000" {
		t.Fatalf("path %q timestamp %q", got.URL.Path, got.Header.Get("X-Agent-Timestamp"))
	}
	wantSig := base64.StdEncoding.EncodeToString(ed25519.Sign(key, []byte(
		"POST\n/api/v1/agent/ready\n1700000000\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")))
	if s := got.Header.Get("X-Agent-Signature"); s != wantSig {
		t.Fatalf("signature %q, want %q", s, wantSig)
	}
}

func TestRemoveEnvKeyKeepsOtherLines(t *testing.T) {
	p := filepath.Join(t.TempDir(), "agent.env")
	in := "# c\nA=1\nWORKSPACED_ENROLLMENT_TOKEN=x\nB=2"
	if err := os.WriteFile(p, []byte(in), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := RemoveEnvKey(p, "WORKSPACED_ENROLLMENT_TOKEN"); err != nil {
		t.Fatal(err)
	}
	out, _ := os.ReadFile(p)
	if string(out) != "# c\nA=1\nB=2" {
		t.Fatalf("got %q", out)
	}
	if fi, _ := os.Stat(p); fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode %v", fi.Mode().Perm())
	}
	entries, _ := os.ReadDir(filepath.Dir(p))
	if len(entries) != 1 {
		t.Fatalf("temp file left behind: %v", entries)
	}
}

func abs(n int64) int64 {
	if n < 0 {
		return -n
	}
	return n
}

func TestTunnelStartsAfterReadyWithSignedWorkers(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	stop := f.startWith(t, d, func(s *Supervisor) {
		s.HarnessURL = strings.TrimSuffix(d.healthURL, "/api/info")
		s.TunnelIdle = 2
		s.TunnelMax = 4
		s.TunnelPing = 10 * time.Millisecond
	})
	waitFor(t, "two tunnel workers", func() bool { return h.count("tunnel") >= 2 })
	stop()
	h.check()
}

func TestHarnessPasswordFollowsEnvironment(t *testing.T) {
	s := &Supervisor{}
	if s.harnessPassword() != "" {
		t.Error("password before any harness ran")
	}
	s.keepPassword(map[string]string{"OPENCODE_SERVER_PASSWORD": "server"})
	if got := s.harnessPassword(); got != "server" {
		t.Errorf("got %q, want the fallback", got)
	}
	s.keepPassword(map[string]string{"OPENCODE_PASSWORD": "newer", "OPENCODE_SERVER_PASSWORD": "server"})
	if got := s.harnessPassword(); got != "newer" {
		t.Errorf("got %q, want OPENCODE_PASSWORD", got)
	}
}

func TestTunnelURL(t *testing.T) {
	for in, want := range map[string]string{
		"https://agent.workspaced.online/api/v1": "wss://agent.workspaced.online/api/v1/agent/tunnel",
		"http://127.0.0.1:8080/api/v1/":          "ws://127.0.0.1:8080/api/v1/agent/tunnel",
	} {
		if got := (&Client{BaseURL: in}).TunnelURL(); got != want {
			t.Errorf("%s: got %s, want %s", in, got, want)
		}
	}
}
