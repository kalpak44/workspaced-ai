package update

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"time"
)

// Launch is the stable host watchdog. The supervisor never replaces this executable.
// A pending journal after a watchdog crash or power loss always selects the previous binary.
func Launch(ctx context.Context, dir string, log *slog.Logger) error {
	return launch(ctx, dir, log, time.Second, 10*time.Minute)
}

func launch(ctx context.Context, dir string, log *slog.Logger, poll, healthTimeout time.Duration) error {
	var active *exec.Cmd
	var activeDone <-chan error
	defer func() {
		if active != nil {
			stop(active, activeDone)
		}
	}()
	path := filepath.Join(dir, "journal.json")
	var j Journal
	if err := Load(path, &j); err != nil {
		return errors.New("cannot load launcher journal")
	}
	if j.Pending {
		j.Rollback("interrupted health check")
		if err := Save(path, j); err != nil {
			return err
		}
	}
	for ctx.Err() == nil {
		if !Verify(Binary(dir, j.Current), j.Current) {
			if !j.Pending {
				return errors.New("installed binary failed checksum verification")
			}
			j.Rollback("candidate checksum mismatch")
			if err := Save(path, j); err != nil {
				return err
			}
			continue
		}
		// A fresh private marker per process prevents stale readiness from accepting a release.
		marker, err := os.CreateTemp(dir, ".ready-*")
		if err != nil {
			return err
		}
		ready := marker.Name()
		marker.Close()
		os.Remove(ready)
		cmd := exec.Command(Binary(dir, j.Current))
		cmd.Env = append(os.Environ(), "SUPERVISOR_UPDATE_DIR="+dir, "SUPERVISOR_READY_FILE="+ready, "SUPERVISOR_FORCE_RESTART="+strconv.FormatBool(j.Current.Restart))
		cmd.Stdout = os.Stdout
		cmd.Stderr = os.Stderr
		if err := cmd.Start(); err != nil {
			if j.Pending {
				j.Rollback("candidate failed to start")
				if err := Save(path, j); err != nil {
					return err
				}
				continue
			}
			return errors.New("supervisor failed to start")
		}
		done := make(chan error, 1)
		go func() { done <- cmd.Wait() }()
		active = cmd
		activeDone = done
		deadline := time.NewTimer(healthTimeout)
		ticker := time.NewTicker(poll)
		restart := false
		running := true
		for !restart {
			select {
			case <-ctx.Done():
				restart = true
			case <-done:
				running = false
				active = nil
				if j.Pending {
					j.Rollback("candidate exited before ready")
					if err := Save(path, j); err != nil {
						return err
					}
					restart = true
				} else {
					ticker.Stop()
					deadline.Stop()
					os.Remove(ready)
					return errors.New("supervisor exited")
				}
			case <-deadline.C:
				if j.Pending {
					j.Rollback("candidate health timeout")
					if err := Save(path, j); err != nil {
						return err
					}
					restart = true
				}
			case <-ticker.C:
				if j.Pending {
					if _, err := os.Stat(ready); err == nil {
						j.Pending = false
						j.Failed = ""
						j.Status = Status{Installed: j.Current.Version, Desired: j.Current.Version, State: "current", Time: time.Now().UTC()}
						if err := Save(path, j); err != nil {
							return err
						}
						log.Info("supervisor update healthy", "supervisor", j.Status)
					}
					continue
				}
				var req Request
				requestPath := filepath.Join(dir, "request.json")
				if Load(requestPath, &req) != nil {
					continue
				}
				if !Valid(req) || req.SHA256 == j.Failed || req == j.Current {
					os.Remove(requestPath)
					continue
				}
				if !Verify(Binary(dir, req), req) {
					os.Remove(requestPath)
					log.Error("staged supervisor checksum mismatch")
					continue
				}
				if err := j.Activate(req); err != nil {
					return err
				}
				if err := Save(path, j); err != nil {
					return err
				}
				os.Remove(requestPath)
				restart = true
			}
		}
		ticker.Stop()
		deadline.Stop()
		if running {
			stop(cmd, done)
			active = nil
		}
		os.Remove(ready)
		log.Info("supervisor update state", "supervisor", j.Status)
	}
	return nil
}
func stop(cmd *exec.Cmd, done <-chan error) {
	_ = cmd.Process.Signal(os.Interrupt)
	select {
	case <-done:
	case <-time.After(20 * time.Second):
		_ = cmd.Process.Kill()
		<-done
	}
}
