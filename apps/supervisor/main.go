// Command supervisor enrolls a workspace VM with agent-hub, runs its harness container and
// keeps it reported alive.
package main

import (
	"context"
	"errors"
	"fmt"
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
	"github.com/kalpak44/workspaced-ai/apps/supervisor/internal/update"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	if len(os.Args) > 1 && os.Args[1] == "--version" {
		fmt.Println(version)
		return
	}
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil)).With("version", version)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()
	if len(os.Args) > 1 && os.Args[1] == "--launch" {
		if err := update.Launch(ctx, envOr("SUPERVISOR_UPDATE_DIR", "/var/lib/workspaced/supervisor"), log); err != nil {
			log.Error("launcher stopped", "error", err.Error())
			os.Exit(1)
		}
		return
	}

	socket := envOr("SUPERVISOR_DOCKER_SOCKET", "/var/run/docker.sock")
	harness := envOr("SUPERVISOR_HARNESS_URL", "http://127.0.0.1:4096")
	s := &agent.Supervisor{
		ForceRestart: os.Getenv("SUPERVISOR_FORCE_RESTART") == "true",
		Version:      version,
		UpdateDir:    os.Getenv("SUPERVISOR_UPDATE_DIR"),
		ReadyFile:    os.Getenv("SUPERVISOR_READY_FILE"),
		EnvFile:      envOr("SUPERVISOR_CONFIG", "/etc/workspaced/agent.env"),
		StateDir:     envOr("SUPERVISOR_STATE_DIR", "/var/lib/workspaced"),
		HTTP: &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) >= 5 || (len(via) > 0 && via[0].URL.Scheme == "https" &&
				(req.URL.Scheme != "https" || req.URL.Host != via[0].URL.Host)) {
				return errors.New("control plane redirect refused")
			}
			return nil
		}},
		UserAgent:     "workspaced-supervisor/" + version,
		Log:           log,
		Now:           time.Now,
		BackoffMin:    time.Second,
		BackoffMax:    60 * time.Second,
		OperatorRetry: 60 * time.Second,
		Heartbeat:     30 * time.Second,
		MemInfo:       "/proc/meminfo",
		FirewallFile:  envOr("SUPERVISOR_FIREWALL_FILE", "/var/lib/workspaced/firewall.d/rules.nft"),
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
