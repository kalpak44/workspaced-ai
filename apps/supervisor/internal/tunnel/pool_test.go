package tunnel

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const (
	testVM     = "3f1c2a9e-1111-4c2b-9a6e-123456789abc"
	testUA     = "workspaced-supervisor/test"
	tunnelPath = "/api/v1/agent/tunnel"
)

// fakeHub accepts workers only with a valid signature and tracks how many are open.
type fakeHub struct {
	t   *testing.T
	pub ed25519.PublicKey
	// reject answers this many handshakes with 401 before accepting any.
	reject atomic.Int32

	mu         sync.Mutex
	workers    []*worker
	handshakes int
	maxOpen    int
	bad        []string
	pings      atomic.Int32
}

type worker struct {
	*hubConn
	taken bool
}

func (w *worker) isOpen() bool {
	select {
	case <-w.closed:
		return false
	default:
		return true
	}
}

func (h *fakeHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if err := h.verify(r); err != nil {
		h.mu.Lock()
		h.bad = append(h.bad, err.Error())
		h.mu.Unlock()
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	if h.reject.Load() > 0 {
		h.reject.Add(-1)
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		OnPingReceived: func(context.Context, []byte) bool { h.pings.Add(1); return true },
	})
	if err != nil {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	h.handshakes++
	h.workers = append(h.workers, &worker{hubConn: newHubConn(h.t, c)})
	h.maxOpen = max(h.maxOpen, h.countLocked(func(*worker) bool { return true }))
}

func (h *fakeHub) verify(r *http.Request) error {
	if r.Method != http.MethodGet || r.URL.Path != tunnelPath {
		return fmt.Errorf("%s %s", r.Method, r.URL.Path)
	}
	if ua := r.Header.Get("User-Agent"); ua != testUA {
		return fmt.Errorf("user agent %q", ua)
	}
	if vm := r.Header.Get("X-Agent-Vm"); vm != testVM {
		return fmt.Errorf("X-Agent-Vm %q", vm)
	}
	ts, err := strconv.ParseInt(r.Header.Get("X-Agent-Timestamp"), 10, 64)
	if err != nil || time.Now().Unix()-ts > 60 || ts-time.Now().Unix() > 60 {
		return fmt.Errorf("timestamp %q", r.Header.Get("X-Agent-Timestamp"))
	}
	sig, err := base64.StdEncoding.DecodeString(r.Header.Get("X-Agent-Signature"))
	if err != nil {
		return fmt.Errorf("signature encoding: %v", err)
	}
	sum := sha256.Sum256(nil)
	msg := "GET\n" + r.URL.EscapedPath() + "\n" + r.Header.Get("X-Agent-Timestamp") + "\n" + hex.EncodeToString(sum[:])
	if !ed25519.Verify(h.pub, []byte(msg), sig) {
		return fmt.Errorf("signature does not verify for %s", r.URL.EscapedPath())
	}
	return nil
}

func (h *fakeHub) countLocked(f func(*worker) bool) int {
	n := 0
	for _, w := range h.workers {
		if w.isOpen() && f(w) {
			n++
		}
	}
	return n
}

// counts reports open idle workers, all open workers and handshakes so far.
func (h *fakeHub) counts() (idle, open, handshakes int) {
	h.mu.Lock()
	defer h.mu.Unlock()
	idle = h.countLocked(func(w *worker) bool { return !w.taken })
	open = h.countLocked(func(*worker) bool { return true })
	h.maxOpen = max(h.maxOpen, open)
	return idle, open, h.handshakes
}

// take hands one idle worker an exchange head.
func (h *fakeHub) take(head control) *worker {
	h.t.Helper()
	h.mu.Lock()
	var w *worker
	for _, c := range h.workers {
		if c.isOpen() && !c.taken {
			w = c
			break
		}
	}
	if w == nil {
		h.mu.Unlock()
		h.t.Fatal("no idle worker to take")
	}
	w.taken = true
	h.mu.Unlock()
	w.send(head)
	return w
}

// dropIdle closes every idle worker from the hub's side, as a hub restart would.
func (h *fakeHub) dropIdle() {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, w := range h.workers {
		if w.isOpen() && !w.taken {
			w.conn.CloseNow()
		}
	}
}

func (h *fakeHub) check() {
	h.t.Helper()
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, b := range h.bad {
		h.t.Error(b)
	}
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

// startPool runs a pool against a fake hub and a harness that answers /slow only once
// release is closed.
func startPool(t *testing.T, idle, maxWorkers int) (hub *fakeHub, release chan struct{}, stop func()) {
	seed := make([]byte, ed25519.SeedSize)
	seed[0] = 7
	key := ed25519.NewKeyFromSeed(seed)
	hub = &fakeHub{t: t, pub: key.Public().(ed25519.PublicKey)}
	hubSrv := httptest.NewServer(hub)
	t.Cleanup(hubSrv.Close)

	release = make(chan struct{})
	harness := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/slow" {
			select {
			case <-release:
			case <-r.Context().Done():
				return
			}
		}
		io.WriteString(w, "ok")
	}))
	t.Cleanup(harness.Close)

	l := &logs{}
	proxy, err := NewProxy(harness.URL, fixed(testPassword), l.logger())
	if err != nil {
		t.Fatal(err)
	}
	p := &Pool{
		URL: "ws" + strings.TrimPrefix(hubSrv.URL, "http") + tunnelPath,
		Sign: func(method, path string) http.Header {
			ts := strconv.FormatInt(time.Now().Unix(), 10)
			sum := sha256.Sum256(nil)
			sig := ed25519.Sign(key, []byte(method+"\n"+path+"\n"+ts+"\n"+hex.EncodeToString(sum[:])))
			return http.Header{"X-Agent-Vm": {testVM}, "X-Agent-Timestamp": {ts},
				"X-Agent-Signature": {base64.StdEncoding.EncodeToString(sig)}}
		},
		UserAgent: testUA, Proxy: proxy, Log: l.logger(),
		Idle: idle, Max: maxWorkers, Ping: 20 * time.Millisecond,
		BackoffMin: time.Millisecond, BackoffMax: 4 * time.Millisecond,
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { done <- p.Run(ctx) }()
	stopped := false
	stop = func() {
		t.Helper()
		if stopped {
			return
		}
		stopped = true
		cancel()
		select {
		case err := <-done:
			if err != nil {
				t.Errorf("Run returned %v", err)
			}
		case <-time.After(5 * time.Second):
			t.Fatal("Run did not stop after cancel")
		}
		l.assertClean(t)
	}
	t.Cleanup(stop)
	return hub, release, stop
}

func TestPoolKeepsIdleWorkers(t *testing.T) {
	hub, _, stop := startPool(t, 3, 64)
	waitFor(t, "three idle workers", func() bool { idle, _, _ := hub.counts(); return idle == 3 })
	waitFor(t, "pings on idle workers", func() bool { return hub.pings.Load() >= 3 })
	idle, open, handshakes := hub.counts()
	if idle != 3 || open != 3 || handshakes != 3 {
		t.Errorf("idle %d open %d handshakes %d, want 3 each", idle, open, handshakes)
	}
	stop()
	waitFor(t, "workers closed on shutdown", func() bool { _, open, _ := hub.counts(); return open == 0 })
	hub.check()
}

func TestPoolReplacesTakenWorker(t *testing.T) {
	hub, release, _ := startPool(t, 3, 64)
	waitFor(t, "three idle workers", func() bool { idle, _, _ := hub.counts(); return idle == 3 })
	w := hub.take(control{Type: "http", Method: "GET", Path: "/slow"})
	w.send(control{Type: "end"})
	waitFor(t, "a replacement", func() bool { idle, open, _ := hub.counts(); return idle == 3 && open == 4 })
	close(release)
	if _, body := readHTTP(t, w.hubConn); body != "ok" {
		t.Errorf("body %q", body)
	}
	waitFor(t, "the busy worker closed", func() bool { _, open, _ := hub.counts(); return open == 3 })
	hub.check()
}

func TestPoolRespectsMax(t *testing.T) {
	hub, release, _ := startPool(t, 2, 3)
	waitFor(t, "two idle workers", func() bool { idle, _, _ := hub.counts(); return idle == 2 })
	var busy []*worker
	for range 2 {
		w := hub.take(control{Type: "http", Method: "GET", Path: "/slow"})
		w.send(control{Type: "end"})
		busy = append(busy, w)
		waitFor(t, "a replacement", func() bool { _, open, _ := hub.counts(); return open == 3 })
	}
	// Room for one more worker only: two busy and one idle.
	time.Sleep(50 * time.Millisecond)
	if idle, open, _ := hub.counts(); idle != 1 || open != 3 {
		t.Errorf("idle %d open %d, want 1 and 3", idle, open)
	}
	close(release)
	for _, w := range busy {
		readHTTP(t, w.hubConn)
	}
	waitFor(t, "two idle workers again", func() bool { idle, open, _ := hub.counts(); return idle == 2 && open == 2 })
	hub.mu.Lock()
	maxOpen := hub.maxOpen
	hub.mu.Unlock()
	if maxOpen > 3 {
		t.Errorf("%d workers open at once, max is 3", maxOpen)
	}
	hub.check()
}

func TestPoolReconnectsAfterDrop(t *testing.T) {
	hub, _, _ := startPool(t, 2, 64)
	waitFor(t, "two idle workers", func() bool { idle, _, _ := hub.counts(); return idle == 2 })
	// A restarted hub first refuses, then accepts again.
	hub.reject.Store(3)
	hub.dropIdle()
	waitFor(t, "workers back", func() bool { idle, _, n := hub.counts(); return idle == 2 && n == 4 })
	if hub.reject.Load() != 0 {
		t.Error("the refused handshakes were not retried")
	}
	hub.check()
}

func TestPoolShutdownEndsBusyExchanges(t *testing.T) {
	hub, _, stop := startPool(t, 1, 64)
	waitFor(t, "an idle worker", func() bool { idle, _, _ := hub.counts(); return idle == 1 })
	w := hub.take(control{Type: "http", Method: "GET", Path: "/slow"})
	w.send(control{Type: "end"})
	waitFor(t, "a replacement", func() bool { _, open, _ := hub.counts(); return open == 2 })
	stop()
	w.waitClosed()
	hub.check()
}
