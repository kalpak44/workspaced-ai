package tunnel

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const (
	dialHub     = 30 * time.Second
	pingTimeout = 10 * time.Second
)

// Pool keeps Idle signed workers open to the hub, replacing each one the moment it is taken,
// so a request never waits for a handshake. Max caps idle plus busy workers.
type Pool struct {
	// URL is the worker endpoint, e.g. wss://agent.workspaced.online/api/v1/agent/tunnel.
	URL string
	// Sign returns the signature headers for a request; the path is signed as it goes on
	// the wire.
	Sign      func(method, path string) http.Header
	UserAgent string
	Proxy     *Proxy
	Log       *slog.Logger

	Idle int
	Max  int
	// Ping keeps idle workers from being closed by Cloudflare as idle sockets.
	Ping       time.Duration
	BackoffMin time.Duration
	BackoffMax time.Duration
}

// Run returns once ctx ends and every worker and exchange has finished.
func (p *Pool) Run(ctx context.Context) error {
	u, err := url.Parse(p.URL)
	if err != nil {
		return fmt.Errorf("tunnel url: %w", err)
	}
	limit := max(p.Max, 1)
	slots := min(max(p.Idle, 1), limit)
	// One token per open worker, idle or busy; an exchange returns its token when it ends.
	sem := make(chan struct{}, limit)
	var wg sync.WaitGroup
	for range slots {
		wg.Add(1)
		go func() {
			defer wg.Done()
			p.slot(ctx, u.EscapedPath(), sem, &wg)
		}()
	}
	p.Log.Info("tunnel started", "idle", slots, "max", limit)
	wg.Wait()
	p.Log.Info("tunnel stopped")
	return nil
}

// slot keeps one idle worker open; when it is taken, the exchange runs on its own goroutine
// and the slot dials the replacement at once.
func (p *Pool) slot(ctx context.Context, path string, sem chan struct{}, wg *sync.WaitGroup) {
	backoff := p.BackoffMin
	for {
		select {
		case <-ctx.Done():
			return
		case sem <- struct{}{}:
		}
		release := func() { <-sem }
		conn, err := p.dial(ctx, path)
		if err != nil {
			release()
			if ctx.Err() != nil {
				return
			}
			p.Log.Warn("tunnel worker dial failed, backing off", "error", err.Error(), "retryIn", backoff.String())
			sleep(ctx, backoff)
			backoff = min(backoff*2, p.BackoffMax)
			continue
		}
		opened := time.Now()
		head, err := p.waitHead(ctx, conn)
		if err != nil {
			conn.CloseNow()
			release()
			if ctx.Err() != nil {
				return
			}
			// A worker that lived through a ping was healthy; only quick drops grow the delay.
			if time.Since(opened) >= p.Ping {
				backoff = p.BackoffMin
			}
			p.Log.Warn("tunnel worker dropped, replacing it", "error", err.Error(), "retryIn", backoff.String())
			sleep(ctx, backoff)
			backoff = min(backoff*2, p.BackoffMax)
			continue
		}
		backoff = p.BackoffMin
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer release()
			p.Proxy.Serve(ctx, conn, head)
		}()
	}
}

func (p *Pool) dial(ctx context.Context, path string) (*websocket.Conn, error) {
	hdr := p.Sign(http.MethodGet, path)
	hdr.Set("User-Agent", p.UserAgent)
	ctx, cancel := context.WithTimeout(ctx, dialHub)
	defer cancel()
	conn, resp, err := websocket.Dial(ctx, p.URL, &websocket.DialOptions{HTTPHeader: hdr})
	if err != nil {
		if resp != nil {
			return nil, fmt.Errorf("hub answered %d", resp.StatusCode)
		}
		return nil, errors.New(cause(err))
	}
	conn.SetReadLimit(maxMessage)
	return conn, nil
}

// waitHead pings the idle worker until the hub sends the exchange's head.
func (p *Pool) waitHead(ctx context.Context, conn *websocket.Conn) (control, error) {
	pctx, stop := context.WithCancel(ctx)
	pinged := make(chan struct{})
	if p.Ping <= 0 {
		close(pinged)
	} else {
		go p.ping(pctx, conn, pinged)
	}
	defer func() {
		stop()
		<-pinged
	}()

	typ, data, err := conn.Read(ctx)
	if err != nil {
		if s := websocket.CloseStatus(err); s != -1 {
			return control{}, fmt.Errorf("hub closed the worker with %d", int(s))
		}
		return control{}, errors.New(cause(err))
	}
	var head control
	if typ != websocket.MessageText || json.Unmarshal(data, &head) != nil {
		conn.Close(websocket.StatusPolicyViolation, "expected an exchange head")
		return control{}, errors.New("hub sent no exchange head")
	}
	return head, nil
}

// ping runs while the worker is idle; the Read in waitHead takes in the pongs.
func (p *Pool) ping(ctx context.Context, conn *websocket.Conn, done chan<- struct{}) {
	defer close(done)
	t := time.NewTicker(p.Ping)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		tctx, cancel := context.WithTimeout(ctx, pingTimeout)
		err := conn.Ping(tctx)
		cancel()
		if err != nil && ctx.Err() == nil {
			// Ends the Read in waitHead, which reports the drop.
			conn.CloseNow()
			return
		}
	}
}

func sleep(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}
