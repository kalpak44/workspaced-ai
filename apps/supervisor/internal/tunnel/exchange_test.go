package tunnel

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
)

const testPassword = "harness-password-that-must-never-be-logged"

// message is one frame the hub side received.
type message struct {
	typ  websocket.MessageType
	data []byte
}

// hubConn is the hub's end of a worker; a reader goroutine answers pings and queues frames.
type hubConn struct {
	t      *testing.T
	conn   *websocket.Conn
	msgs   chan message
	closed chan struct{}
	// closeErr holds the read error that ended the worker, once closed is closed.
	closeErr error
}

func newHubConn(t *testing.T, conn *websocket.Conn) *hubConn {
	h := &hubConn{t: t, conn: conn, msgs: make(chan message, 256), closed: make(chan struct{})}
	conn.SetReadLimit(maxMessage + 1)
	go func() {
		defer close(h.closed)
		for {
			typ, data, err := conn.Read(context.Background())
			if err != nil {
				h.closeErr = err
				return
			}
			h.msgs <- message{typ, data}
		}
	}()
	return h
}

func (h *hubConn) send(v any) {
	h.t.Helper()
	data, _ := json.Marshal(v)
	if err := h.conn.Write(context.Background(), websocket.MessageText, data); err != nil {
		h.t.Fatalf("hub send: %v", err)
	}
}

func (h *hubConn) sendBytes(b []byte) {
	h.t.Helper()
	if err := h.conn.Write(context.Background(), websocket.MessageBinary, b); err != nil {
		h.t.Fatalf("hub send: %v", err)
	}
}

func (h *hubConn) next() message {
	h.t.Helper()
	select {
	case m := <-h.msgs:
		return m
	case <-h.closed:
		select {
		case m := <-h.msgs:
			return m
		default:
		}
		h.t.Fatalf("worker closed while waiting for a message: %v", h.closeErr)
	case <-time.After(5 * time.Second):
		h.t.Fatal("timed out waiting for a message")
	}
	return message{}
}

// nextControl reads a text frame as JSON.
func (h *hubConn) nextControl() map[string]any {
	h.t.Helper()
	m := h.next()
	if m.typ != websocket.MessageText {
		h.t.Fatalf("got binary frame %q, want a control message", m.data)
	}
	var v map[string]any
	if err := json.Unmarshal(m.data, &v); err != nil {
		h.t.Fatalf("control %q: %v", m.data, err)
	}
	return v
}

func (h *hubConn) waitClosed() {
	h.t.Helper()
	select {
	case <-h.closed:
	case <-time.After(5 * time.Second):
		h.t.Fatal("worker was not closed")
	}
}

// logs collects the proxy's output so each test can assert nothing secret reached it.
type logs struct {
	mu sync.Mutex
	b  strings.Builder
}

func (l *logs) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *logs) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

func (l *logs) logger() *slog.Logger {
	return slog.New(slog.NewJSONHandler(l, nil))
}

func (l *logs) assertClean(t *testing.T, secrets ...string) {
	t.Helper()
	out := l.String()
	for _, s := range append(secrets, testPassword) {
		if strings.Contains(out, s) {
			t.Errorf("logs contain %q:\n%s", s, out)
		}
	}
}

// exchange starts Serve on a fresh worker and returns the hub's end of it.
func exchange(t *testing.T, harnessURL string, password func() string, head control) (*hubConn, *logs) {
	t.Helper()
	l := &logs{}
	proxy, err := NewProxy(harnessURL, password, l.logger())
	if err != nil {
		t.Fatal(err)
	}
	if head.Type == "web-http" || head.Type == "web-ws" {
		proxy.WebBase, _ = url.Parse(harnessURL)
		proxy.base, _ = url.Parse("http://127.0.0.1:1")
	}
	accepted := make(chan *websocket.Conn, 1)
	hub := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		accepted <- c
	}))
	t.Cleanup(hub.Close)
	worker, _, err := websocket.Dial(context.Background(), hub.URL, nil)
	if err != nil {
		t.Fatal(err)
	}
	worker.SetReadLimit(maxMessage)
	h := newHubConn(t, <-accepted)
	done := make(chan struct{})
	go func() {
		defer close(done)
		proxy.Serve(context.Background(), worker, head)
	}()
	t.Cleanup(func() {
		h.conn.CloseNow()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("Serve did not return")
		}
	})
	return h, l
}

func fixed(pw string) func() string { return func() string { return pw } }

// readHTTP collects a whole HTTP exchange: response head, body, end.
func readHTTP(t *testing.T, h *hubConn) (map[string]any, string) {
	t.Helper()
	head := h.nextControl()
	if head["type"] != "response" {
		t.Fatalf("head %v", head)
	}
	var body bytes.Buffer
	for {
		m := h.next()
		if m.typ == websocket.MessageText {
			var c control
			if json.Unmarshal(m.data, &c) != nil || c.Type != "end" {
				t.Fatalf("unexpected control %q", m.data)
			}
			break
		}
		body.Write(m.data)
	}
	h.waitClosed()
	return head, body.String()
}

func headers(v any) http.Header {
	out := http.Header{}
	for k, vs := range v.(map[string]any) {
		for _, s := range vs.([]any) {
			out.Add(k, s.(string))
		}
	}
	return out
}

func TestHTTPPlainRequest(t *testing.T) {
	harness := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/api/session" || r.URL.RawQuery != "x=1&token=query-secret" {
			t.Errorf("harness got %s %s", r.Method, r.URL)
		}
		if r.ContentLength != 0 || len(r.TransferEncoding) != 0 {
			t.Errorf("a request without a body arrived with length %d, encoding %v", r.ContentLength, r.TransferEncoding)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-Harness", "yes")
		w.WriteHeader(http.StatusCreated)
		io.WriteString(w, `{"id":"s1"}`)
	}))
	defer harness.Close()
	h, l := exchange(t, harness.URL, fixed(testPassword),
		control{Type: "http", Method: "GET", Path: "/api/session?x=1&token=query-secret"})
	h.send(control{Type: "end"})
	head, body := readHTTP(t, h)
	if head["status"] != float64(http.StatusCreated) {
		t.Errorf("status %v", head["status"])
	}
	hdr := headers(head["headers"])
	if hdr.Get("X-Harness") != "yes" || hdr.Get("Content-Type") != "application/json" {
		t.Errorf("response headers %v", hdr)
	}
	if body != `{"id":"s1"}` {
		t.Errorf("body %q", body)
	}
	l.assertClean(t, "query-secret", "s1")
	if !strings.Contains(l.String(), `"path":"/api/session"`) || !strings.Contains(l.String(), `"status":201`) {
		t.Errorf("exchange not logged:\n%s", l)
	}
}

func TestHTTPStreamsResponseFrameByFrame(t *testing.T) {
	// The second event is held back until the hub has the first, which buffering would deadlock.
	gotFirst := make(chan struct{})
	harness := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		io.WriteString(w, "data: one\n\n")
		w.(http.Flusher).Flush()
		select {
		case <-gotFirst:
		case <-time.After(5 * time.Second):
			t.Error("the first event never reached the hub")
		}
		io.WriteString(w, "data: two\n\n")
	}))
	defer harness.Close()
	h, _ := exchange(t, harness.URL, fixed(testPassword), control{Type: "http", Method: "GET", Path: "/api/event"})
	h.send(control{Type: "end"})
	if head := h.nextControl(); head["status"] != float64(200) {
		t.Fatalf("head %v", head)
	}
	if m := h.next(); m.typ != websocket.MessageBinary || string(m.data) != "data: one\n\n" {
		t.Fatalf("first frame %q", m.data)
	}
	close(gotFirst)
	if m := h.next(); string(m.data) != "data: two\n\n" {
		t.Fatalf("second frame %q", m.data)
	}
	if c := h.nextControl(); c["type"] != "end" {
		t.Fatalf("got %v, want end", c)
	}
	h.waitClosed()
}

func TestHTTPRequestBody(t *testing.T) {
	for _, withLength := range []bool{true, false} {
		harness := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			body, _ := io.ReadAll(r.Body)
			if withLength && r.ContentLength != 11 {
				t.Errorf("content length %d", r.ContentLength)
			}
			w.Write(append([]byte(r.Method+" "), body...))
		}))
		hdr := http.Header{"Content-Type": {"application/json"}}
		if withLength {
			hdr.Set("Content-Length", "11")
		}
		h, _ := exchange(t, harness.URL, fixed(testPassword),
			control{Type: "http", Method: "POST", Path: "/api/session", Headers: hdr})
		h.sendBytes([]byte(`{"a":`))
		h.sendBytes([]byte(`"bcd"}`))
		h.send(control{Type: "end"})
		_, body := readHTTP(t, h)
		if body != `POST {"a":"bcd"}` {
			t.Errorf("withLength=%v: harness answered %q", withLength, body)
		}
		harness.Close()
	}
}

func TestHTTPHeaderRules(t *testing.T) {
	var password atomic.Value
	password.Store("first-password")
	var gotAuth []string
	var mu sync.Mutex
	harness := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		user, pw, ok := r.BasicAuth()
		mu.Lock()
		gotAuth = append(gotAuth, user+":"+pw)
		mu.Unlock()
		if !ok || len(r.Header.Values("Authorization")) != 1 {
			t.Errorf("authorization %q", r.Header.Values("Authorization"))
		}
		for _, k := range []string{"X-Listed", "Keep-Alive", "Proxy-Authorization", "Proxy-Anything", "Te", "Upgrade", "Trailer"} {
			if v := r.Header.Get(k); v != "" {
				t.Errorf("hop-by-hop header %s reached the harness", k)
			}
		}
		if r.Header.Get("X-Kept") != "kept" || r.Header.Get("Accept") != "text/plain" {
			t.Errorf("end-to-end headers lost: %v", r.Header)
		}
		if local := r.Context().Value(http.LocalAddrContextKey).(net.Addr).String(); r.Host != local {
			t.Errorf("host %q", r.Host)
		}
		w.Header().Set("Keep-Alive", "timeout=5")
		w.Header().Set("Proxy-Authenticate", "Basic")
		w.Header().Set("X-Answer", "a")
	}))
	defer harness.Close()
	in := http.Header{
		// Lower case on purpose: the hub's JSON need not be canonical.
		"authorization":       {"Bearer api-token-that-must-be-replaced"},
		"Connection":          {"keep-alive, X-Listed"},
		"X-Listed":            {"1"},
		"Keep-Alive":          {"timeout=5"},
		"Proxy-Authorization": {"Basic x"},
		"Proxy-Anything":      {"x"},
		"Te":                  {"trailers"},
		"Upgrade":             {"h2c"},
		"Trailer":             {"X-T"},
		"Host":                {"evil.example"},
		"X-Kept":              {"kept"},
		"Accept":              {"text/plain"},
	}
	for _, want := range []string{"first-password", testPassword} {
		password.Store(want)
		h, l := exchange(t, harness.URL, func() string { return password.Load().(string) },
			control{Type: "http", Method: "GET", Path: "/api/info", Headers: in})
		h.send(control{Type: "end"})
		head, _ := readHTTP(t, h)
		out := headers(head["headers"])
		if out.Get("Keep-Alive") != "" || out.Get("Proxy-Authenticate") != "" || out.Get("X-Answer") != "a" {
			t.Errorf("response headers %v", out)
		}
		l.assertClean(t, "first-password", "api-token-that-must-be-replaced")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(gotAuth) != 2 || gotAuth[0] != "opencode:first-password" || gotAuth[1] != "opencode:"+testPassword {
		t.Errorf("harness saw credentials %q; want the newest password each time", gotAuth)
	}
}

func TestHTTPRefusesPathsOffTheHarness(t *testing.T) {
	var hits atomic.Int32
	harness := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if r.URL.Path != "//evil.example/x" {
			t.Errorf("path %q", r.URL.Path)
		}
	}))
	defer harness.Close()

	h, _ := exchange(t, harness.URL, fixed(""), control{Type: "http", Method: "GET", Path: "evil.example/x"})
	if c := h.nextControl(); c["type"] != "error" || c["status"] != float64(400) {
		t.Errorf("relative path: %v", c)
	}
	h.waitClosed()

	h, _ = exchange(t, harness.URL, fixed(""), control{Type: "http", Method: "GET /x HTTP/1.1\r\n", Path: "/x"})
	if c := h.nextControl(); c["type"] != "error" || c["status"] != float64(400) {
		t.Errorf("bad method: %v", c)
	}

	// A path that looks like an authority still goes to the harness, as a path.
	h, _ = exchange(t, harness.URL, fixed(""), control{Type: "http", Method: "GET", Path: "//evil.example/x"})
	h.send(control{Type: "end"})
	readHTTP(t, h)
	if hits.Load() != 1 {
		t.Errorf("harness hits %d, want 1", hits.Load())
	}
}

// closedPort is an address nothing listens on.
func closedPort(t *testing.T) string {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	ln.Close()
	return "http://" + addr
}

func TestHarnessUnreachable(t *testing.T) {
	base := closedPort(t)
	h, l := exchange(t, base, fixed(testPassword), control{Type: "http", Method: "GET", Path: "/api/info?k=query-secret"})
	h.send(control{Type: "end"})
	c := h.nextControl()
	if c["type"] != "error" || c["status"] != float64(http.StatusBadGateway) || c["message"] == "" {
		t.Errorf("got %v", c)
	}
	h.waitClosed()
	l.assertClean(t, "query-secret")

	h, _ = exchange(t, base, fixed(testPassword), control{Type: "ws", Path: "/api/pty/1/connect"})
	if c := h.nextControl(); c["type"] != "error" || c["status"] != float64(http.StatusBadGateway) {
		t.Errorf("ws: got %v", c)
	}
	h.waitClosed()
}

func TestWebSocketExchange(t *testing.T) {
	harnessClosed := make(chan websocket.CloseError, 1)
	harness := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if user, pw, _ := r.BasicAuth(); user != "opencode" || pw != testPassword {
			t.Errorf("harness handshake credentials %q", user)
		}
		if r.URL.Path != "/api/pty/abc/connect" || r.URL.Query().Get("cursor") != "0" {
			t.Errorf("harness got %s", r.URL)
		}
		c, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{"pty.v1"}})
		if err != nil {
			t.Errorf("accept: %v", err)
			return
		}
		defer c.CloseNow()
		ctx := context.Background()
		for {
			typ, data, err := c.Read(ctx)
			if err != nil {
				var ce websocket.CloseError
				if !errors.As(err, &ce) {
					t.Errorf("harness read: %v", err)
				}
				harnessClosed <- ce
				return
			}
			if typ == websocket.MessageText {
				data = append([]byte("echo:"), data...)
			} else {
				data = append(data, 0xff)
			}
			c.Write(ctx, typ, data)
		}
	}))
	defer harness.Close()
	h, _ := exchange(t, harness.URL, fixed(testPassword), control{Type: "ws", Path: "/api/pty/abc/connect?cursor=0",
		Headers: http.Header{"Sec-WebSocket-Protocol": {"other, pty.v1"}, "Authorization": {"Bearer x"},
			"Sec-WebSocket-Key": {"stale"}}})
	if c := h.nextControl(); c["type"] != "open" || c["protocol"] != "pty.v1" {
		t.Fatalf("got %v, want open with pty.v1", c)
	}
	h.sendBytes([]byte("\x01ls -la\r"))
	if m := h.next(); m.typ != websocket.MessageBinary || string(m.data) != "\x01echo:ls -la\r" {
		t.Errorf("text relay %q", m.data)
	}
	h.sendBytes([]byte{0x02, 0x00, 0x01})
	if m := h.next(); !bytes.Equal(m.data, []byte{0x02, 0x00, 0x01, 0xff}) {
		t.Errorf("binary relay %v", m.data)
	}
	h.send(control{Type: "end", Code: 4000, Reason: "closed by user"})
	select {
	case ce := <-harnessClosed:
		if ce.Code != 4000 || ce.Reason != "closed by user" {
			t.Errorf("harness closed with %d %q", ce.Code, ce.Reason)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("harness socket not closed")
	}
	h.waitClosed()
}

func TestWebSocketClosedByHarness(t *testing.T) {
	harness := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		c.Write(context.Background(), websocket.MessageText, []byte("bye"))
		c.Close(4001, "pty exited")
	}))
	defer harness.Close()
	h, _ := exchange(t, harness.URL, fixed(testPassword), control{Type: "ws", Path: "/api/pty/abc/connect"})
	if c := h.nextControl(); c["type"] != "open" || c["protocol"] != "" {
		t.Fatalf("got %v", c)
	}
	if m := h.next(); string(m.data) != "\x01bye" {
		t.Errorf("relay %q", m.data)
	}
	c := h.nextControl()
	if c["type"] != "end" || c["code"] != float64(4001) || c["reason"] != "pty exited" {
		t.Errorf("got %v", c)
	}
	h.waitClosed()
}

func TestWebSocketRefusedByHarness(t *testing.T) {
	harness := httptest.NewServer(http.NotFoundHandler())
	defer harness.Close()
	h, _ := exchange(t, harness.URL, fixed(testPassword), control{Type: "ws", Path: "/api/pty/gone/connect"})
	if c := h.nextControl(); c["type"] != "error" || c["status"] != float64(http.StatusNotFound) {
		t.Errorf("got %v", c)
	}
	h.waitClosed()
}

func TestPublicWebKeepsHostAndVisitorAuthWithoutHarnessCredentials(t *testing.T) {
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "box-site.workspaced.test" {
			t.Errorf("Host = %q", r.Host)
		}
		if r.Header.Get("Authorization") != "Bearer visitor" {
			t.Errorf("visitor auth replaced: %q", r.Header.Get("Authorization"))
		}
		if r.URL.RequestURI() != "/app?q=%2F" {
			t.Errorf("URI = %q", r.URL.RequestURI())
		}
		io.WriteString(w, "site")
	}))
	defer web.Close()
	h, _ := exchange(t, web.URL, fixed(testPassword), control{Type: "web-http", Method: "GET", Path: "/app?q=%2F", Headers: http.Header{"Host": {"box-site.workspaced.test"}, "Authorization": {"Bearer visitor"}}})
	h.send(control{Type: "end"})
	head, body := readHTTP(t, h)
	if head["status"] != float64(200) || body != "site" {
		t.Fatalf("%v %q", head, body)
	}
}

func TestPublicWebSocketKeepsHostAndVisitorAuth(t *testing.T) {
	web := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Host != "box-app.workspaced.test" || r.Header.Get("Authorization") != "Bearer visitor" {
			t.Errorf("public socket headers changed: Host=%q auth=%q", r.Host, r.Header.Get("Authorization"))
		}
		c, err := websocket.Accept(w, r, nil)
		if err != nil {
			t.Error(err)
			return
		}
		defer c.CloseNow()
		kind, data, err := c.Read(context.Background())
		if err != nil {
			t.Error(err)
			return
		}
		_ = c.Write(context.Background(), kind, data)
		c.Close(websocket.StatusNormalClosure, "done")
	}))
	defer web.Close()
	h, _ := exchange(t, web.URL, fixed(testPassword), control{Type: "web-ws", Path: "/socket", Headers: http.Header{"Host": {"box-app.workspaced.test"}, "Authorization": {"Bearer visitor"}}})
	if head := h.nextControl(); head["type"] != "open" {
		t.Fatalf("socket did not open: %v", head)
	}
	h.sendBytes([]byte("\x01hello"))
	if message := h.next(); string(message.data) != "\x01hello" {
		t.Fatalf("socket echo %q", message.data)
	}
}
