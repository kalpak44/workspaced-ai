package agent

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// Client speaks the agent-hub protocol for one VM.
type Client struct {
	BaseURL   string
	VMID      string
	Key       ed25519.PrivateKey
	HTTP      *http.Client
	UserAgent string
	Now       func() time.Time
}

// SignMessage builds the exact byte string agent-hub verifies; any drift here
// makes every request after enrollment fail with 401.
func SignMessage(method, path string, timestamp int64, body []byte) string {
	sum := sha256.Sum256(body)
	return method + "\n" + path + "\n" + strconv.FormatInt(timestamp, 10) + "\n" + hex.EncodeToString(sum[:])
}

// Enroll returns the HTTP status; err is set only when no response arrived.
func (c *Client) Enroll(ctx context.Context, token string) (int, error) {
	body, err := json.Marshal(map[string]string{
		"vmId":      c.VMID,
		"token":     token,
		"publicKey": base64.StdEncoding.EncodeToString(c.Key.Public().(ed25519.PublicKey)),
	})
	if err != nil {
		return 0, err
	}
	req, err := c.newRequest(ctx, "/agent/enroll", body)
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/json")
	return c.do(req)
}

// Signed posts an empty, signed body to a path below the agent base URL.
func (c *Client) Signed(ctx context.Context, subpath string) (int, error) {
	status, _, err := c.Call(ctx, subpath)
	return status, err
}

// Call is Signed that also returns the response body, which may hold secret values.
func (c *Client) Call(ctx context.Context, subpath string) (int, []byte, error) {
	req, err := c.newRequest(ctx, subpath, nil)
	if err != nil {
		return 0, nil, err
	}
	// Sign the path as it goes on the wire, so the /api/v1 prefix comes from the URL.
	for k, v := range c.SignHeaders(req.Method, req.URL.EscapedPath()) {
		req.Header[k] = v
	}
	return c.doBody(req)
}

// SignHeaders signs a request with no body, such as the tunnel's WebSocket handshake.
func (c *Client) SignHeaders(method, path string) http.Header {
	ts := c.Now().Unix()
	msg := SignMessage(method, path, ts, nil)
	h := http.Header{}
	h.Set("X-Agent-Vm", c.VMID)
	h.Set("X-Agent-Timestamp", strconv.FormatInt(ts, 10))
	h.Set("X-Agent-Signature", base64.StdEncoding.EncodeToString(ed25519.Sign(c.Key, []byte(msg))))
	return h
}

// TunnelURL is the worker endpoint below the base URL, as a WebSocket URL.
func (c *Client) TunnelURL() string {
	u := strings.TrimRight(c.BaseURL, "/") + "/agent/tunnel"
	if rest, ok := strings.CutPrefix(u, "https://"); ok {
		return "wss://" + rest
	}
	if rest, ok := strings.CutPrefix(u, "http://"); ok {
		return "ws://" + rest
	}
	return u
}

func (c *Client) newRequest(ctx context.Context, subpath string, body []byte) (*http.Request, error) {
	url := strings.TrimRight(c.BaseURL, "/") + subpath
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", c.UserAgent)
	return req, nil
}

func (c *Client) do(req *http.Request) (int, error) {
	status, _, err := c.doBody(req)
	return status, err
}

// maxBody bounds a response; a harness answer with fifty secrets is far below it.
const maxBody = 1 << 20

func (c *Client) doBody(req *http.Request) (int, []byte, error) {
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxBody))
	// Drain so the keep-alive connection is reused for the next heartbeat.
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	return resp.StatusCode, body, err
}
