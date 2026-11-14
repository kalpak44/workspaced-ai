// Command supervisor enrolls a workspace VM with agent-hub, runs its harness container and
// keeps it reported alive.
package main

import (
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/kalpak44/workspaced-ai/apps/supervisor/internal/agent"
	"github.com/kalpak44/workspaced-ai/apps/supervisor/internal/docker"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("version", version)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()

	socket := envOr("SUPERVISOR_DOCKER_SOCKET", "/var/run/docker.sock")
	harness := envOr("SUPERVISOR_HARNESS_URL", "http://127.0.0.1:4096")
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
		Docker:        docker.New(socket),
		HealthURL:     strings.TrimRight(harness, "/") + "/api/info",
		HealthPoll:    2 * time.Second,
		HealthTimeout: 5 * time.Minute,
		HarnessURL:    harness,
		TunnelIdle:    envInt(log, "SUPERVISOR_TUNNEL_IDLE", 4),
		TunnelMax:     envInt(log, "SUPERVISOR_TUNNEL_MAX", 64),
		TunnelPing:    25 * time.Second,
	}
	log.Info("starting", "config", s.EnvFile, "stateDir", s.StateDir, "docker", socket,
		"harness", harness, "tunnelIdle", s.TunnelIdle, "tunnelMax", s.TunnelMax)
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

// envInt falls back to def for an unset or malformed value, so a typo cannot stop the tunnel.
func envInt(log *slog.Logger, key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 {
		log.Warn("ignoring malformed setting", "key", key, "default", def)
		return def
	}
	return n
}
