package agent

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"sort"
	"strings"

	"github.com/kalpak44/workspaced-ai/apps/supervisor/internal/docker"
)

const (
	harnessName  = "workspaced-harness"
	configLabel  = "io.workspaced.config"
	harnessPort  = "4096/tcp"
	workspaceDir = "/home/workspace"
)

// Docker is the part of the Engine API the harness lifecycle uses; tests swap in a fake.
type Docker interface {
	Inspect(ctx context.Context, name string) (*docker.Container, error)
	ImageExists(ctx context.Context, ref string) (bool, error)
	Pull(ctx context.Context, ref string) error
	Stop(ctx context.Context, name string) error
	Remove(ctx context.Context, name string) error
	Create(ctx context.Context, name string, spec docker.ContainerSpec) (string, error)
	Start(ctx context.Context, id string) error
}

// Harness is the agent-hub's answer to /agent/harness. Environment holds secret values.
type Harness struct {
	Image       string            `json:"image"`
	Environment map[string]string `json:"environment"`
}

// ConfigHash changes whenever the image or any variable does, so a container carrying the
// same label already runs with exactly these values. JSON sorts the map keys and quotes
// every string, so no value can pass for a name.
func ConfigHash(h Harness) string {
	data, _ := json.Marshal([]any{h.Image, h.Environment, "web-port-80-v1"})
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

// harnessSpec builds the create body. The home has the same path on both sides, so a bind
// mount the agent asks its own Docker for resolves to the same files.
func harnessSpec(h Harness) docker.ContainerSpec {
	env := make([]string, 0, len(h.Environment)+1)
	for k, v := range h.Environment {
		env = append(env, k+"="+v)
	}
	sort.Strings(env)
	env = append(env, "DOCKER_HOST=unix:///var/run/docker.sock")
	return docker.ContainerSpec{
		Image:        h.Image,
		Env:          env,
		User:         "1000:1000",
		Labels:       map[string]string{configLabel: ConfigHash(h)},
		ExposedPorts: map[string]struct{}{harnessPort: {}, "80/tcp": {}},
		HostConfig: docker.HostConfig{
			Sysctls: map[string]string{"net.ipv4.ip_unprivileged_port_start": "0"},
			// Mounts, not Binds: Binds would create a missing socket path as a directory
			// while the rootless Docker is still starting; Mounts fails and is retried.
			Mounts: []docker.Mount{
				{Type: "bind", Source: workspaceDir, Target: workspaceDir},
				// The VM's rootless Docker; the harness never sees the system socket.
				{Type: "bind", Source: "/run/workspace-docker/docker.sock", Target: "/var/run/docker.sock"},
			},
			PortBindings: map[string][]docker.PortBinding{harnessPort: {{HostIp: "127.0.0.1", HostPort: "4096"}}, "80/tcp": {{HostIp: "127.0.0.1", HostPort: "8080"}}},
			// Not unless-stopped: the daemon would start it at boot before the rootless
			// Docker exists. The supervisor starts it instead.
			RestartPolicy:  docker.RestartPolicy{Name: "on-failure"},
			CapDrop:        []string{"ALL"},
			SecurityOpt:    []string{"no-new-privileges"},
			Init:           true,
			ReadonlyRootfs: true,
			// exec, which Docker's tmpfs default leaves out: OpenCode loads its terminals'
			// native library from here, and without it every terminal fails to start.
			Tmpfs: map[string]string{"/tmp": "rw,exec,nosuid,nodev"},
		},
	}
}

// bringUp fetches the harness config, ensures the container, waits until it answers and
// reports ready. It returns only once ready is posted or ctx ends.
func (s *Supervisor) bringUp(ctx context.Context, c *Client, log *slog.Logger, force bool) {
	backoff := s.BackoffMin
	for ctx.Err() == nil {
		h, ok := s.fetchHarness(ctx, c, log)
		if !ok {
			return
		}
		if err := s.ensure(ctx, h, force, log); err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Warn("harness ensure failed, backing off",
				"error", redact(err.Error(), h.Environment), "retryIn", backoff.String())
			sleep(ctx, backoff)
			backoff = s.next(backoff)
			continue
		}
		s.keepPassword(h.Environment)
		if s.waitHealthy(ctx) {
			log.Info("harness answers")
			s.ready(ctx, c, log)
			return
		}
		if ctx.Err() != nil {
			return
		}
		log.Error("harness did not answer in time, recreating it", "timeout", s.HealthTimeout.String())
		force = true
	}
}

// fetchHarness retries until the hub answers 200 with an image; ok is false only when ctx ends.
func (s *Supervisor) fetchHarness(ctx context.Context, c *Client, log *slog.Logger) (Harness, bool) {
	backoff := s.BackoffMin
	for ctx.Err() == nil {
		status, body, err := c.Call(ctx, "/agent/harness")
		if ctx.Err() != nil {
			break
		}
		var h Harness
		if err == nil && status == http.StatusOK {
			if err = json.Unmarshal(body, &h); err == nil && h.Image == "" {
				err = errors.New("no image in harness config")
			}
			if err == nil {
				log.Info("fetched harness config", "image", h.Image, "variables", len(h.Environment))
				return h, true
			}
			// A decode error never quotes the body, so no value reaches the log.
			err = fmt.Errorf("harness config: %w", err)
		}
		log.Warn("harness config fetch failed, backing off", "status", status, "error", errString(err), "retryIn", backoff.String())
		sleep(ctx, backoff)
		backoff = s.next(backoff)
	}
	return Harness{}, false
}

// ensure keeps a container built from the same config, starting it if it stopped, and
// otherwise replaces it. The pull happens before the stop, so the old one serves meanwhile.
func (s *Supervisor) ensure(ctx context.Context, h Harness, force bool, log *slog.Logger) error {
	want := ConfigHash(h)
	cur, err := s.Docker.Inspect(ctx, harnessName)
	if err != nil {
		return err
	}
	if !force && cur != nil && cur.Labels[configLabel] == want {
		if cur.Running {
			log.Info("harness already runs with this config")
			return nil
		}
		log.Info("starting stopped harness")
		return s.Docker.Start(ctx, cur.ID)
	}
	present, err := s.Docker.ImageExists(ctx, h.Image)
	if err != nil {
		return err
	}
	if !present {
		log.Info("pulling harness image", "image", h.Image)
		if err := s.Docker.Pull(ctx, h.Image); err != nil {
			return err
		}
	}
	if cur != nil {
		log.Info("replacing harness container", "forced", force)
		if err := s.Docker.Stop(ctx, harnessName); err != nil {
			return err
		}
		if err := s.Docker.Remove(ctx, harnessName); err != nil {
			return err
		}
	}
	id, err := s.Docker.Create(ctx, harnessName, harnessSpec(h))
	if err != nil {
		return err
	}
	if err := s.Docker.Start(ctx, id); err != nil {
		return err
	}
	log.Info("harness container started", "image", h.Image, "variables", len(h.Environment))
	return nil
}

// keepRunning restarts a harness that exited, since its restart policy does not cover a
// clean exit or a daemon restart. A missing container is recreated from a fresh config.
func (s *Supervisor) keepRunning(ctx context.Context, c *Client, log *slog.Logger) {
	cur, err := s.Docker.Inspect(ctx, harnessName)
	switch {
	case err != nil:
		log.Warn("harness inspect failed", "error", err.Error())
	case cur == nil:
		log.Warn("harness container is gone, recreating it")
		h, ok := s.fetchHarness(ctx, c, log)
		if !ok {
			return
		}
		if err := s.ensure(ctx, h, false, log); err != nil {
			log.Warn("harness ensure failed", "error", redact(err.Error(), h.Environment))
		} else {
			s.keepPassword(h.Environment)
		}
	case !cur.Running:
		log.Warn("harness is not running, starting it")
		if err := s.Docker.Start(ctx, cur.ID); err != nil {
			log.Warn("harness start failed", "error", err.Error())
		}
	}
}

// waitHealthy polls until the harness answers with any status; a 401 still means it is up.
func (s *Supervisor) waitHealthy(ctx context.Context) bool {
	ctx, cancel := context.WithTimeout(ctx, s.HealthTimeout)
	defer cancel()
	for {
		if s.healthy(ctx) {
			return true
		}
		sleep(ctx, s.HealthPoll)
		if ctx.Err() != nil {
			return false
		}
	}
}

func (s *Supervisor) healthy(ctx context.Context) bool {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.HealthURL, nil)
	if err != nil {
		return false
	}
	resp, err := s.HTTP.Do(req)
	if err != nil {
		return false
	}
	resp.Body.Close()
	return true
}

// redact keeps a Docker error that echoes the create body from leaking a value into the log.
func redact(msg string, env map[string]string) string {
	for _, v := range env {
		if v != "" {
			msg = strings.ReplaceAll(msg, v, "[redacted]")
		}
	}
	return msg
}
