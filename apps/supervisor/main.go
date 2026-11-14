// Command supervisor enrolls a workspace VM with agent-hub and keeps it reported alive.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/kalpak44/workspaced-ai/apps/supervisor/internal/agent"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("version", version)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()

	s := &agent.Supervisor{
		EnvFile:       envOr("SUPERVISOR_CONFIG", "/etc/workspaced/agent.env"),
		StateDir:      envOr("SUPERVISOR_STATE_DIR", "/var/lib/workspaced"),
		HTTP:          &http.Client{Timeout: 10 * time.Second},
		UserAgent:     "workspaced-supervisor/" + version,
		Log:           log,
		Now:           time.Now,
		BackoffMin:    time.Second,
		BackoffMax:    60 * time.Second,
		OperatorRetry: 60 * time.Second,
		Heartbeat:     30 * time.Second,
	}
	log.Info("starting", "config", s.EnvFile, "stateDir", s.StateDir)
	if err := s.Run(ctx); err != nil {
		log.Error("supervisor stopped", "error", err.Error())
		os.Exit(1)
	}
	log.Info("stopped")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
