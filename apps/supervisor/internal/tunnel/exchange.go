// Package tunnel carries the API's calls to the harness over worker WebSockets the VM opens
// to agent-hub, since the VM accepts no inbound connection.
package tunnel

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/coder/websocket"
)

const (
	// harnessUser is the basic auth user the harness expects beside its password.
	harnessUser = "opencode"
	// maxMessage bounds one frame either way; a body arrives in many frames, so only a
	// single relayed terminal message is ever this large.
	maxMessage  = 16 << 20
	chunkSize   = 32 << 10
	dialHarness = 30 * time.Second

	prefixText   = 0x01
	prefixBinary = 0x02
)

// hopByHop are dropped both ways. Host is dropped too, so the harness sees its own address.
var hopByHop = []string{"Connection", "Keep-Alive", "Te", "Trailer", "Transfer-Encoding", "Upgrade", "Host"}

// wsHandshake are the headers the harness dial computes itself.
var wsHandshake = []string{"Sec-Websocket-Key", "Sec-Websocket-Version", "Sec-Websocket-Extensions",
	"Sec-Websocket-Accept", "Sec-Websocket-Protocol"}

// control is every JSON text frame of the protocol; each type uses a subset of the fields.
type control struct {
	Type    string      `json:"type"`
	Web     bool        `json:"web,omitempty"`
	Method  string      `json:"method,omitempty"`
	Path    string      `json:"path,omitempty"`
	Headers http.Header `json:"headers,omitempty"`
	Code    int         `json:"code,omitempty"`
	Reason  string      `json:"reason,omitempty"`
}

// Proxy runs the exchange a worker was given against the harness.
type Proxy struct {
	base *url.URL
	// WebBase is the fixed VM loopback binding to the harness nginx on port 80.
	WebBase *url.URL
	// Password returns the harness password the container runs with now.
	Password func() string
	Log      *slog.Logger
	http     *http.Client
}

// NewProxy targets harness, e.g. http://127.0.0.1:4096; nothing else is ever dialled.
func NewProxy(harness string, password func() string, log *slog.Logger) (*Proxy, error) {
	base, err := url.Parse(strings.TrimRight(harness, "/"))
	if err != nil || (base.Scheme != "http" && base.Scheme != "https") || base.Host == "" {
		return nil, fmt.Errorf("harness url %q: want http://host:port", harness)
	}
	return &Proxy{base: base, Password: password, Log: log, http: &http.Client{
		Transport: &http.Transport{
			// No proxy from the environment, and the body passes through still encoded.
			Proxy:               nil,
			DisableCompression:  true,
			MaxIdleConnsPerHost: 16,
			IdleConnTimeout:     90 * time.Second,
		},
		// A redirect goes back to the API's client, which knows what the location means.
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}, nil
}

// Serve runs one exchange and closes the worker. head is the hub's first message.
func (p *Proxy) Serve(ctx context.Context, conn *websocket.Conn, head control) {
	defer conn.CloseNow()
	// Distinct types make an old supervisor reject web traffic instead of exposing its harness.
	head.Web = head.Type == "web-http" || head.Type == "web-ws"
	switch head.Type {
	case "http", "web-http":
		p.serveHTTP(ctx, conn, head)
	case "ws", "web-ws":
		p.serveWS(ctx, conn, head)
	default:
		p.Log.Warn("tunnel exchange of unknown type", "type", head.Type)
		conn.Close(websocket.StatusPolicyViolation, "unknown exchange type")
	}
}

// target keeps the base's scheme and host whatever the path holds, so "//host" or "@host"
// cannot steer the request anywhere else.
func (p *Proxy) target(path, scheme string) (*url.URL, error) {
	if !strings.HasPrefix(path, "/") {
		return nil, errors.New("path must start with /")
	}
	u, err := url.Parse(p.base.String() + path)
	if err != nil || u.Host != p.base.Host || u.User != nil {
		return nil, errors.New("invalid path")
	}
	u.Fragment, u.RawFragment = "", ""
	if scheme != "" {
		u.Scheme = scheme
	}
	return u, nil
}

// exchangeTarget selects only one of two operator-configured destinations.
func (p *Proxy) exchangeTarget(head control, scheme string) (*url.URL, error) {
	if !head.Web {
		return p.target(head.Path, scheme)
	}
	if p.WebBase == nil {
		return nil, errors.New("web proxy unavailable; restart the workspace")
	}
	web := *p
	web.base = p.WebBase
	return web.target(head.Path, scheme)
}

func (p *Proxy) serveHTTP(ctx context.Context, conn *websocket.Conn, head control) {
	start := time.Now()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	log := p.Log.With("method", head.Method, "path", pathOnly(head.Path))

	target, err := p.exchangeTarget(head, "")
	if err == nil && !validMethod(head.Method) {
		err = errors.New("invalid method")
	}
	if err != nil {
		log.Warn("tunnel request refused", "error", err.Error())
		p.fail(ctx, conn, http.StatusBadRequest, err.Error())
		return
	}
	body, bodyDone, err := p.requestBody(ctx, conn, cancel)
	if err != nil {
		log.Warn("tunnel request body failed", "error", err.Error())
		return
	}
	defer func() {
		// Unblocks a body write the harness never read, so the reader goroutine ends.
		cancel()
		body.Close()
		conn.CloseNow()
		<-bodyDone
	}()

	req, err := http.NewRequestWithContext(ctx, head.Method, target.String(), body)
	if err != nil {
		p.fail(ctx, conn, http.StatusBadRequest, "invalid request")
		return
	}
	req.Header = forward(head.Headers, "Authorization")
	if head.Web {
		req.Header = forward(head.Headers)
		req.Host = head.Headers.Get("Host")
	}
	req.ContentLength = -1
	if body == http.NoBody {
		req.ContentLength = 0
	} else if n, err := strconv.ParseInt(req.Header.Get("Content-Length"), 10, 64); err == nil && n >= 0 {
		req.ContentLength = n
	}
	if pw := p.password(); !head.Web && pw != "" {
		req.SetBasicAuth(harnessUser, pw)
	}

	resp, err := p.http.Do(req)
	if err != nil {
		if ctx.Err() != nil {
			log.Info("tunnel request abandoned by the hub", "duration", since(start))
			return
		}
		log.Warn("harness unreachable", "error", cause(err), "duration", since(start))
		p.fail(ctx, conn, http.StatusBadGateway, "the workspace's harness did not answer")
		return
	}
	defer resp.Body.Close()
	if err := writeJSON(ctx, conn, map[string]any{
		"type": "response", "status": resp.StatusCode, "headers": forward(resp.Header),
	}); err != nil {
		log.Info("tunnel response abandoned by the hub", "status", resp.StatusCode, "duration", since(start))
		return
	}
	// Each read goes out as its own frame, so an event stream is never held back.
	buf := make([]byte, chunkSize)
	for {
		n, rerr := resp.Body.Read(buf)
		if n > 0 {
			if err := conn.Write(ctx, websocket.MessageBinary, buf[:n]); err != nil {
				log.Info("tunnel response abandoned by the hub", "status", resp.StatusCode, "duration", since(start))
				return
			}
		}
		if rerr == io.EOF {
			break
		}
		if rerr != nil {
			log.Warn("harness response broke off", "status", resp.StatusCode, "error", cause(rerr), "duration", since(start))
			conn.Close(websocket.StatusInternalError, "harness response broke off")
			return
		}
	}
	log.Info("tunnel request", "status", resp.StatusCode, "duration", since(start))
	if err := writeJSON(ctx, conn, control{Type: "end"}); err == nil {
		conn.Close(websocket.StatusNormalClosure, "")
	}
}

// requestBody reads the body frames up to "end" and then keeps reading, so the hub closing
// the worker (its client went away) cancels the request even during a long response.
func (p *Proxy) requestBody(ctx context.Context, conn *websocket.Conn, cancel context.CancelFunc) (io.ReadCloser, <-chan struct{}, error) {
	typ, first, err := conn.Read(ctx)
	if err != nil {
		return nil, nil, err
	}
	var pr *io.PipeReader
	var pw *io.PipeWriter
	switch {
	case typ == websocket.MessageText && isEnd(first):
	case typ == websocket.MessageBinary:
		pr, pw = io.Pipe()
	default:
		conn.Close(websocket.StatusPolicyViolation, "expected body or end")
		return nil, nil, errors.New("expected body or end")
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer cancel()
		open := pw != nil
		if open {
			if _, err := pw.Write(first); err != nil {
				open = false
			}
		}
		for {
			typ, data, err := conn.Read(ctx)
			if err != nil {
				if pw != nil {
					pw.CloseWithError(errors.New("the hub closed the worker"))
				}
				return
			}
			switch {
			case open && typ == websocket.MessageBinary:
				// A failed write means the harness answered without reading on; skip the rest.
				if _, err := pw.Write(data); err != nil {
					open = false
				}
			case pw != nil && typ == websocket.MessageText && isEnd(data):
				pw.Close()
				open = false
			case pw != nil && typ == websocket.MessageBinary:
			default:
				if pw != nil {
					pw.CloseWithError(errors.New("unexpected message"))
				}
				conn.Close(websocket.StatusPolicyViolation, "unexpected message")
				return
			}
		}
	}()
	if pr == nil {
		return http.NoBody, done, nil
	}
	return pr, done, nil
}

type hostTransport struct {
	base http.RoundTripper
	host string
}

func (t hostTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	copy := req.Clone(req.Context())
	copy.Host = t.host
	return t.base.RoundTrip(copy)
}

func (p *Proxy) serveWS(ctx context.Context, conn *websocket.Conn, head control) {
	start := time.Now()
	log := p.Log.With("method", "WS", "path", pathOnly(head.Path))

	target, err := p.exchangeTarget(head, wsScheme(p.base.Scheme))
	if err != nil {
		log.Warn("tunnel socket refused", "error", err.Error())
		p.fail(ctx, conn, http.StatusBadRequest, err.Error())
		return
	}
	// The hub's header names need not be canonical, so read the protocols from the copy.
	hdr := forward(head.Headers, "Authorization")
	if head.Web {
		hdr = forward(head.Headers)
	}
	var protocols []string
	for _, v := range hdr.Values("Sec-WebSocket-Protocol") {
		for _, s := range strings.Split(v, ",") {
			if s = strings.TrimSpace(s); s != "" {
				protocols = append(protocols, s)
			}
		}
	}
	for _, k := range wsHandshake {
		hdr.Del(k)
	}
	if pw := p.password(); !head.Web && pw != "" {
		hdr.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(harnessUser+":"+pw)))
	}
	client := p.http
	if head.Web {
		// websocket.Dial builds Host from its URL; override it at the final transport hop.
		copy := *p.http
		copy.Transport = hostTransport{base: p.http.Transport, host: head.Headers.Get("Host")}
		client = &copy
	}
	dctx, cancelDial := context.WithTimeout(ctx, dialHarness)
	hc, resp, err := websocket.Dial(dctx, target.String(), &websocket.DialOptions{
		HTTPClient: client, HTTPHeader: hdr, Subprotocols: protocols,
	})
	cancelDial()
	if err != nil {
		if ctx.Err() != nil {
			return
		}
		// A harness that answered without upgrading passes its own status on, e.g. 404.
		status := http.StatusBadGateway
		if resp != nil && resp.StatusCode >= 400 {
			status = resp.StatusCode
		}
		log.Warn("harness socket failed", "status", status, "error", cause(err), "duration", since(start))
		p.fail(ctx, conn, status, "the workspace's harness did not accept the connection")
		return
	}
	defer hc.CloseNow()
	hc.SetReadLimit(maxMessage)
	if err := writeJSON(ctx, conn, map[string]any{"type": "open", "protocol": hc.Subprotocol()}); err != nil {
		hc.Close(websocket.StatusGoingAway, "")
		return
	}
	code := p.relay(ctx, conn, hc)
	log.Info("tunnel socket", "code", int(code), "duration", since(start))
}

// relay copies messages both ways until one side ends; the side that ends first decides the
// close code the other one gets.
func (p *Proxy) relay(ctx context.Context, conn, hc *websocket.Conn) websocket.StatusCode {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	// The first side to end wins; a close handshake under a lock would wait on the other.
	var ended atomic.Bool
	var code websocket.StatusCode
	done := make(chan struct{})

	go func() {
		defer close(done)
		for {
			typ, data, err := hc.Read(ctx)
			if err != nil {
				if ended.CompareAndSwap(false, true) {
					code = endCode(err)
					var ce websocket.CloseError
					errors.As(err, &ce)
					if writeJSON(ctx, conn, control{Type: "end", Code: int(code), Reason: ce.Reason}) == nil {
						conn.Close(websocket.StatusNormalClosure, "")
					}
					conn.CloseNow()
				}
				return
			}
			prefix := byte(prefixText)
			if typ == websocket.MessageBinary {
				prefix = prefixBinary
			}
			if err := conn.Write(ctx, websocket.MessageBinary, append([]byte{prefix}, data...)); err != nil {
				return
			}
		}
	}()

	for {
		typ, data, err := conn.Read(ctx)
		if err != nil {
			if ended.CompareAndSwap(false, true) {
				code = websocket.StatusGoingAway
				hc.Close(code, "")
			}
			break
		}
		if typ == websocket.MessageBinary && len(data) > 0 && (data[0] == prefixText || data[0] == prefixBinary) {
			mt := websocket.MessageText
			if data[0] == prefixBinary {
				mt = websocket.MessageBinary
			}
			// A failed write means the harness side ended; the reader above reports it.
			_ = hc.Write(ctx, mt, data[1:])
			continue
		}
		var c control
		isEnd := typ == websocket.MessageText && json.Unmarshal(data, &c) == nil && c.Type == "end"
		if ended.CompareAndSwap(false, true) {
			if isEnd {
				code = websocket.StatusCode(c.Code)
				if code == 0 {
					code = websocket.StatusNormalClosure
				}
				// An invalid code or an overlong reason fails the handshake; close anyway.
				if hc.Close(code, c.Reason) != nil {
					hc.CloseNow()
				}
				conn.Close(websocket.StatusNormalClosure, "")
			} else {
				code = websocket.StatusPolicyViolation
				hc.Close(websocket.StatusGoingAway, "")
				conn.Close(websocket.StatusPolicyViolation, "unexpected message")
			}
		}
		break
	}
	cancel()
	hc.CloseNow()
	conn.CloseNow()
	<-done
	return code
}

// fail reports an exchange that never reached the harness; the hub turns it into a problem.
func (p *Proxy) fail(ctx context.Context, conn *websocket.Conn, status int, message string) {
	if writeJSON(ctx, conn, map[string]any{"type": "error", "status": status, "message": message}) == nil {
		conn.Close(websocket.StatusNormalClosure, "")
	}
}

func (p *Proxy) password() string {
	if p.Password == nil {
		return ""
	}
	return p.Password()
}

// forward copies the headers a hop may pass on: none named by Connection, no hop-by-hop
// header, nothing starting with Proxy-, and none of drop.
func forward(in http.Header, drop ...string) http.Header {
	out := http.Header{}
	for k, vs := range in {
		ck := http.CanonicalHeaderKey(k)
		out[ck] = append(out[ck], vs...)
	}
	for _, v := range out.Values("Connection") {
		for _, name := range strings.Split(v, ",") {
			out.Del(strings.TrimSpace(name))
		}
	}
	for k := range out {
		if strings.HasPrefix(k, "Proxy-") {
			delete(out, k)
		}
	}
	for _, k := range append(hopByHop, drop...) {
		out.Del(k)
	}
	return out
}

func writeJSON(ctx context.Context, conn *websocket.Conn, v any) error {
	data, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return conn.Write(ctx, websocket.MessageText, data)
}

func isEnd(data []byte) bool {
	var c control
	return json.Unmarshal(data, &c) == nil && c.Type == "end"
}

// endCode maps a harness close to one the hub can send on; 1005 and 1006 never go on a wire.
func endCode(err error) websocket.StatusCode {
	switch c := websocket.CloseStatus(err); c {
	case -1, websocket.StatusAbnormalClosure:
		return websocket.StatusInternalError
	case websocket.StatusNoStatusRcvd:
		return websocket.StatusNormalClosure
	default:
		return c
	}
}

// validMethod accepts an HTTP token, so a method cannot smuggle a request line.
func validMethod(m string) bool {
	if m == "" {
		return false
	}
	for _, r := range m {
		if r > 127 || !strings.ContainsRune("!#$%&'*+-.^_`|~", r) && !('0' <= r && r <= '9' || 'A' <= r && r <= 'Z' || 'a' <= r && r <= 'z') {
			return false
		}
	}
	return true
}

func wsScheme(scheme string) string {
	if scheme == "https" {
		return "wss"
	}
	return "ws"
}

// pathOnly keeps the query out of the log; it may carry a token or a file name.
func pathOnly(p string) string {
	path, _, _ := strings.Cut(p, "?")
	return path
}

// cause drops the URL a *url.Error carries, so no query reaches the log.
func cause(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) {
		err = ue.Err
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return "timeout"
	}
	return err.Error()
}

func since(t time.Time) string {
	return time.Since(t).Round(time.Millisecond).String()
}
