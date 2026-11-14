package agent

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"time"
)

const (
	keyVMID  = "WORKSPACED_VM_ID"
	keyURL   = "WORKSPACED_AGENT_URL"
	keyToken = "WORKSPACED_ENROLLMENT_TOKEN"
)

// Supervisor enrolls the VM once, starts the harness, reports ready once it answers, then
// heartbeats and follows the status the hub reports back.
type Supervisor struct {
	EnvFile   string
	StateDir  string
	HTTP      *http.Client
	UserAgent string
	Log       *slog.Logger
	Now       func() time.Time

	BackoffMin    time.Duration
	BackoffMax    time.Duration
	OperatorRetry time.Duration
	Heartbeat     time.Duration

	Docker        Docker
	HealthURL     string
	HealthPoll    time.Duration
	HealthTimeout time.Duration
}

// Run returns nil when ctx is cancelled and an error only for unusable local state.
func (s *Supervisor) Run(ctx context.Context) error {
	if err := os.MkdirAll(s.StateDir, 0o700); err != nil {
		return fmt.Errorf("state dir: %w", err)
	}
	env, err := ReadEnvFile(s.EnvFile)
	if err != nil {
		return fmt.Errorf("env file: %w", err)
	}
	vmID, baseURL := env[keyVMID], env[keyURL]
	if vmID == "" || baseURL == "" {
		return fmt.Errorf("env file %s: %s and %s are required", s.EnvFile, keyVMID, keyURL)
	}
	key, err := LoadOrCreateKey(filepath.Join(s.StateDir, "agent.key"))
	if err != nil {
		return fmt.Errorf("agent key: %w", err)
	}
	c := &Client{BaseURL: baseURL, VMID: vmID, Key: key, HTTP: s.HTTP, UserAgent: s.UserAgent, Now: s.Now}
	log := s.Log.With("vmId", vmID)

	if err := s.enroll(ctx, c, log); err != nil {
		return err
	}
	if ctx.Err() != nil {
		return nil
	}
	s.bringUp(ctx, c, log, false)
	s.heartbeat(ctx, c, log)
	return nil
}

func (s *Supervisor) enroll(ctx context.Context, c *Client, log *slog.Logger) error {
	marker := filepath.Join(s.StateDir, "enrolled")
	if _, err := os.Stat(marker); err == nil {
		log.Info("already enrolled, skipping enrollment")
		return s.dropToken(log)
	} else if !errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("enrollment marker: %w", err)
	}

	backoff := s.BackoffMin
	for ctx.Err() == nil {
		// Re-read each attempt, so an operator who writes a fresh token needs no restart.
		env, err := ReadEnvFile(s.EnvFile)
		token := env[keyToken]
		if err != nil || token == "" {
			log.Error("not enrolled and no enrollment token in env file; an operator must issue one",
				"file", s.EnvFile, "retryIn", s.OperatorRetry.String())
			sleep(ctx, s.OperatorRetry)
			continue
		}
		status, err := c.Enroll(ctx, token)
		switch {
		case err != nil || status >= 500:
			log.Warn("enroll failed, backing off", "status", status, "error", errString(err), "retryIn", backoff.String())
			sleep(ctx, backoff)
			backoff = s.next(backoff)
			continue
		case status == http.StatusNoContent:
			log.Info("enrolled")
		case status == http.StatusConflict:
			// The hub holds a key for this VM, and only this VM's previous run could have
			// sent it: that run crashed between enrolling and writing the marker.
			log.Warn("enroll answered 409; assuming an earlier run of this VM enrolled this key")
		case status == http.StatusUnauthorized || status == http.StatusBadRequest:
			log.Error("enrollment rejected; token is invalid, expired or used, an operator must issue a new one",
				"status", status, "retryIn", s.OperatorRetry.String())
			sleep(ctx, s.OperatorRetry)
			continue
		default:
			log.Error("enroll answered unexpected status", "status", status, "retryIn", s.OperatorRetry.String())
			sleep(ctx, s.OperatorRetry)
			continue
		}
		if err := writeAtomic(marker, nil, 0o600); err != nil {
			return fmt.Errorf("enrollment marker: %w", err)
		}
		return s.dropToken(log)
	}
	return nil
}

// dropToken also runs on the enrolled path, to finish a run that crashed after the marker.
func (s *Supervisor) dropToken(log *slog.Logger) error {
	env, err := ReadEnvFile(s.EnvFile)
	if err != nil {
		return fmt.Errorf("env file: %w", err)
	}
	if _, ok := env[keyToken]; !ok {
		return nil
	}
	if err := RemoveEnvKey(s.EnvFile, keyToken); err != nil {
		return fmt.Errorf("remove enrollment token: %w", err)
	}
	log.Info("removed spent enrollment token from env file", "file", s.EnvFile)
	return nil
}

func (s *Supervisor) ready(ctx context.Context, c *Client, log *slog.Logger) {
	backoff := s.BackoffMin
	for ctx.Err() == nil {
		status, err := c.Signed(ctx, "/agent/ready")
		if err == nil && status == http.StatusNoContent {
			log.Info("reported ready")
			return
		}
		if ctx.Err() != nil {
			return
		}
		log.Warn("ready failed, backing off", "status", status, "error", errString(err), "retryIn", backoff.String())
		sleep(ctx, backoff)
		backoff = s.next(backoff)
	}
}

func (s *Supervisor) heartbeat(ctx context.Context, c *Client, log *slog.Logger) {
	t := time.NewTicker(s.Heartbeat)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			log.Info("shutting down")
			return
		case <-t.C:
		}
		s.keepRunning(ctx, c, log)
		status, body, err := c.Call(ctx, "/agent/heartbeat")
		if ctx.Err() != nil {
			continue
		}
		if err != nil || (status != http.StatusOK && status != http.StatusNoContent) {
			log.Warn("heartbeat failed", "status", status, "error", errString(err))
			continue
		}
		// An older hub answers 204 with no body; that means nothing to act on.
		var hb struct{ Status string }
		_ = json.Unmarshal(body, &hb)
		switch hb.Status {
		case "RESTARTING":
			log.Info("workspace restart requested, recreating the harness")
			s.bringUp(ctx, c, log, true)
		case "PROVISIONING":
			// The VM manager lost this VM's status, usually across its own restart.
			if s.healthy(ctx) {
				log.Info("hub reports provisioning and the harness answers, reporting ready again")
				s.ready(ctx, c, log)
			} else {
				log.Info("hub reports provisioning and the harness does not answer, bringing it up")
				s.bringUp(ctx, c, log, false)
			}
		}
	}
}

func (s *Supervisor) next(d time.Duration) time.Duration {
	return min(d*2, s.BackoffMax)
}

func sleep(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-t.C:
	}
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
