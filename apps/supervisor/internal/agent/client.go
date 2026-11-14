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
	req, err := c.newRequest(ctx, subpath, nil)
	if err != nil {
		return 0, err
	}
	ts := c.Now().Unix()
	// Sign the path as it goes on the wire, so the /api/v1 prefix comes from the URL.
	msg := SignMessage(req.Method, req.URL.EscapedPath(), ts, nil)
	req.Header.Set("X-Agent-Vm", c.VMID)
	req.Header.Set("X-Agent-Timestamp", strconv.FormatInt(ts, 10))
	req.Header.Set("X-Agent-Signature", base64.StdEncoding.EncodeToString(ed25519.Sign(c.Key, []byte(msg))))
	return c.do(req)
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
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	// Drain so the keep-alive connection is reused for the next heartbeat.
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	return resp.StatusCode, nil
}
