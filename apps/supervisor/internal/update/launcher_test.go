package update

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLauncherReplacesAndRollsBackProcesses(t *testing.T) {
	for _, mode := range []string{"healthy", "exit", "timeout"} {
		t.Run(mode, func(t *testing.T) {
			dir := t.TempDir()
			oldBody := "#!/bin/sh\ntrap 'exit 0' INT TERM\nprintf old > \"$SUPERVISOR_READY_FILE\"\nwhile :; do sleep 0.01; done\n"
			nextBody := "#!/bin/sh\ntrap 'exit 0' INT TERM\n"
			switch mode {
			case "healthy":
				nextBody += "printf new > \"$SUPERVISOR_READY_FILE\"\n"
			case "exit":
				nextBody += "exit 1\n"
			}
			nextBody += "while :; do sleep 0.01; done\n"
			old, next := pin("old", oldBody), pin("new", nextBody)
			for _, v := range []struct {
				req  Request
				body string
			}{{old, oldBody}, {next, nextBody}} {
				if err := store(dir, v.req, strings.NewReader(v.body)); err != nil {
					t.Fatal(err)
				}
			}
			path := filepath.Join(dir, "journal.json")
			if err := Save(path, Journal{Current: old}); err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			done := make(chan error, 1)
			healthTimeout := 200 * time.Millisecond
			if mode == "healthy" {
				healthTimeout = 2 * time.Second
			}
			go func() {
				done <- launch(ctx, dir, slog.New(slog.NewTextHandler(io.Discard, nil)), 10*time.Millisecond, healthTimeout)
			}()
			if err := Save(filepath.Join(dir, "request.json"), next); err != nil {
				t.Fatal(err)
			}
			until := time.Now().Add(5 * time.Second)
			var j Journal
			for time.Now().Before(until) {
				if Load(path, &j) == nil {
					if mode == "healthy" && j.Current.Version == "new" && !j.Pending {
						break
					}
					if mode != "healthy" && j.Status.State == "rolled_back" {
						break
					}
				}
				time.Sleep(10 * time.Millisecond)
			}
			cancel()
			select {
			case err := <-done:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(25 * time.Second):
				t.Fatal("launcher failed to stop")
			}
			if mode == "healthy" {
				if j.Current.Version != "new" || j.Pending {
					t.Fatal(j)
				}
			} else if j.Current.Version != "old" || j.Failed != next.SHA256 {
				t.Fatal(j)
			}
			if !Verify(Binary(dir, old), old) {
				t.Fatal("previous binary not retained")
			}
		})
	}
}
func TestDownloadFailureAndTLSIntegrity(t *testing.T) {
	for _, mode := range []string{"healthy", "checksum", "rejected", "truncated"} {
		t.Run(mode, func(t *testing.T) {
			server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch mode {
				case "rejected":
					w.WriteHeader(503)
				case "checksum":
					io.WriteString(w, "wrong")
				case "truncated":
					w.Header().Set("Content-Length", "100")
					io.WriteString(w, "partial")
				default:
					io.WriteString(w, "release")
				}
			}))
			defer server.Close()
			dir := t.TempDir()
			req := pin("new", "release")
			err := downloadWithClient(context.Background(), dir, Artifact{URL: server.URL, SHA256: req.SHA256}, req, server.Client())
			if mode == "healthy" {
				if err != nil || !Verify(Binary(dir, req), req) {
					t.Fatal(err)
				}
			} else {
				if err == nil {
					t.Fatal("accepted failed download")
				}
				if _, err := os.Stat(Binary(dir, req)); !os.IsNotExist(err) {
					t.Fatal("failed download installed")
				}
			}
		})
	}
}
