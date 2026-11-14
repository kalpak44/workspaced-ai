package agent

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"slices"
	"strconv"
	"strings"
	"sync"
	"testing"

	"github.com/kalpak44/workspaced-ai/apps/supervisor/internal/docker"
)

const (
	testImage      = "ghcr.io/kalpak44/workspaced-ai-harness:85c2bd5"
	testSecretName = "GITHUB_TOKEN_FOR_TEST"
	testSecret     = "ghp-value-that-must-never-be-logged"
)

type fakeContainer struct {
	id      string
	running bool
	// answers is whether the harness inside responds on its port.
	answers bool
	labels  map[string]string
}

// fakeDocker keeps one container and serves the health URL from its state.
type fakeDocker struct {
	mu         sync.Mutex
	images     map[string]bool
	c          *fakeContainer
	nextID     int
	unhealthy  int // the next n containers created never answer
	failCreate int
	calls      []string
	specs      []docker.ContainerSpec
	healthURL  string
}

func newFakeDocker(t *testing.T) *fakeDocker {
	d := &fakeDocker{images: map[string]bool{}}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		d.mu.Lock()
		up := d.c != nil && d.c.running && d.c.answers
		d.mu.Unlock()
		if up {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		// Drop the connection, as a port with nothing behind it would.
		conn, _, err := w.(http.Hijacker).Hijack()
		if err == nil {
			conn.Close()
		}
	}))
	t.Cleanup(srv.Close)
	d.healthURL = srv.URL + "/api/info"
	return d
}

func (d *fakeDocker) record(call string) {
	d.calls = append(d.calls, call)
}

func (d *fakeDocker) Inspect(_ context.Context, name string) (*docker.Container, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.record("inspect")
	if name != harnessName || d.c == nil {
		return nil, nil
	}
	return &docker.Container{ID: d.c.id, Running: d.c.running, Labels: d.c.labels}, nil
}

func (d *fakeDocker) ImageExists(_ context.Context, ref string) (bool, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.record("image")
	return d.images[ref], nil
}

func (d *fakeDocker) Pull(_ context.Context, ref string) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.record("pull")
	d.images[ref] = true
	return nil
}

func (d *fakeDocker) Stop(context.Context, string) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.record("stop")
	if d.c != nil {
		d.c.running = false
	}
	return nil
}

func (d *fakeDocker) Remove(context.Context, string) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.record("remove")
	d.c = nil
	return nil
}

func (d *fakeDocker) Create(_ context.Context, name string, spec docker.ContainerSpec) (string, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.record("create")
	if d.failCreate > 0 {
		d.failCreate--
		// Echo a value, as a daemon error about the body could.
		return "", errors.New("bind source path does not exist; env " + strings.Join(spec.Env, ","))
	}
	if d.c != nil {
		return "", errors.New("name " + name + " already in use")
	}
	d.nextID++
	d.c = &fakeContainer{id: "c" + strconv.Itoa(d.nextID), labels: spec.Labels, answers: d.unhealthy == 0}
	if d.unhealthy > 0 {
		d.unhealthy--
	}
	d.specs = append(d.specs, spec)
	return d.c.id, nil
}

func (d *fakeDocker) Start(_ context.Context, id string) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.record("start")
	if d.c == nil || d.c.id != id {
		return errors.New("no such container " + id)
	}
	d.c.running = true
	return nil
}

func (d *fakeDocker) count(call string) int {
	d.mu.Lock()
	defer d.mu.Unlock()
	n := 0
	for _, c := range d.calls {
		if c == call {
			n++
		}
	}
	return n
}

// mutate changes the container under the lock, e.g. to simulate it exiting.
func (d *fakeDocker) mutate(f func(c *fakeContainer)) {
	d.mu.Lock()
	defer d.mu.Unlock()
	f(d.c)
}

func (d *fakeDocker) spec(i int) docker.ContainerSpec {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.specs[i]
}

func defaultHarness() Harness {
	return Harness{Image: testImage, Environment: map[string]string{testSecretName: testSecret, "OPENCODE_SERVER_PASSWORD": "pw-0"}}
}

func TestHarnessCreatedFromHubConfig(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	stop := f.startWith(t, d)
	waitFor(t, "heartbeat", func() bool { return h.count("heartbeat") >= 2 })
	stop()
	h.check()

	if n := h.count("ready"); n != 1 {
		t.Errorf("ready calls = %d, want 1", n)
	}
	d.mu.Lock()
	calls := slices.Clone(d.calls)
	d.mu.Unlock()
	want := []string{"inspect", "image", "pull", "create", "start"}
	if len(calls) < len(want) || !reflect.DeepEqual(calls[:len(want)], want) {
		t.Fatalf("docker calls %v, want prefix %v", calls, want)
	}
	spec := d.spec(0)
	if spec.Image != testImage || spec.User != "1000:1000" {
		t.Errorf("image %q user %q", spec.Image, spec.User)
	}
	wantEnv := []string{"GITHUB_TOKEN_FOR_TEST=" + testSecret, "OPENCODE_SERVER_PASSWORD=pw-0", "DOCKER_HOST=unix:///var/run/docker.sock"}
	if !reflect.DeepEqual(spec.Env, wantEnv) {
		t.Errorf("env %v", spec.Env)
	}
	if spec.Labels[configLabel] != ConfigHash(defaultHarness()) {
		t.Errorf("label %q", spec.Labels[configLabel])
	}
	hc := spec.HostConfig
	wantMounts := []docker.Mount{
		{Type: "bind", Source: "/home/workspace", Target: "/home/workspace"},
		{Type: "bind", Source: "/run/workspace-docker/docker.sock", Target: "/var/run/docker.sock"},
	}
	if !reflect.DeepEqual(hc.Mounts, wantMounts) {
		t.Errorf("mounts %v", hc.Mounts)
	}
	if b := hc.PortBindings["4096/tcp"]; len(b) != 1 || b[0] != (docker.PortBinding{HostIp: "127.0.0.1", HostPort: "4096"}) {
		t.Errorf("port bindings %v", hc.PortBindings)
	}
	if _, ok := spec.ExposedPorts["4096/tcp"]; !ok {
		t.Error("4096/tcp not exposed")
	}
	if hc.RestartPolicy.Name != "on-failure" || !hc.Init || !hc.ReadonlyRootfs ||
		!reflect.DeepEqual(hc.CapDrop, []string{"ALL"}) || !reflect.DeepEqual(hc.SecurityOpt, []string{"no-new-privileges"}) ||
		!reflect.DeepEqual(hc.Tmpfs, map[string]string{"/tmp": ""}) {
		t.Errorf("host config %+v", hc)
	}
}

func TestHarnessWithSameConfigIsKept(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	d.images[testImage] = true
	d.c = &fakeContainer{id: "old", running: true, answers: true, labels: map[string]string{configLabel: ConfigHash(defaultHarness())}}
	stop := f.startWith(t, d)
	waitFor(t, "heartbeat", func() bool { return h.count("heartbeat") >= 2 })
	stop()
	h.check()
	if d.count("create") != 0 || d.count("stop") != 0 || d.count("start") != 0 {
		t.Errorf("container touched: %v", d.calls)
	}
	if n := h.count("ready"); n != 1 {
		t.Errorf("ready calls = %d, want 1", n)
	}
}

func TestStoppedHarnessWithSameConfigIsStarted(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	d.images[testImage] = true
	d.c = &fakeContainer{id: "old", answers: true, labels: map[string]string{configLabel: ConfigHash(defaultHarness())}}
	stop := f.startWith(t, d)
	waitFor(t, "ready", func() bool { return h.count("ready") >= 1 })
	stop()
	h.check()
	if d.count("create") != 0 || d.count("start") != 1 {
		t.Errorf("want one start and no create: %v", d.calls)
	}
}

func TestHarnessWithChangedConfigIsReplaced(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	d.images[testImage] = true
	d.c = &fakeContainer{id: "old", running: true, answers: true, labels: map[string]string{configLabel: "stale"}}
	stop := f.startWith(t, d)
	waitFor(t, "ready", func() bool { return h.count("ready") >= 1 })
	stop()
	h.check()
	if d.count("pull") != 0 || d.count("stop") != 1 || d.count("remove") != 1 || d.count("create") != 1 {
		t.Errorf("docker calls %v", d.calls)
	}
}

func TestRestartingRecreatesHarness(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	h.heartbeats = []string{"READY", "RESTARTING"}
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	stop := f.startWith(t, d)
	waitFor(t, "second ready", func() bool { return h.count("ready") >= 2 })
	waitFor(t, "heartbeat after restart", func() bool { return h.count("heartbeat") >= 4 })
	stop()
	h.check()
	if n := h.count("harness"); n != 2 {
		t.Errorf("harness fetches = %d, want 2", n)
	}
	// Same config both times, so only the forced flag can explain the recreation.
	if d.count("create") != 2 || d.count("remove") != 1 {
		t.Errorf("docker calls %v", d.calls)
	}
	if n := h.count("ready"); n != 2 {
		t.Errorf("ready calls = %d, want 2", n)
	}
}

func TestProvisioningWithHealthyHarnessReportsReady(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	h.heartbeats = []string{"PROVISIONING"}
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	stop := f.startWith(t, d)
	waitFor(t, "second ready", func() bool { return h.count("ready") >= 2 })
	stop()
	h.check()
	if d.count("create") != 1 || h.count("harness") != 1 {
		t.Errorf("harness touched again: fetches %d, calls %v", h.count("harness"), d.calls)
	}
}

func TestProvisioningWithDeadHarnessBringsItUp(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	stop := f.startWith(t, d)
	waitFor(t, "ready", func() bool { return h.count("ready") >= 1 })
	// A running container that stopped answering: keeping it times out, then it is recreated.
	d.mutate(func(c *fakeContainer) { c.answers = false })
	h.mu.Lock()
	h.heartbeats = []string{"PROVISIONING"}
	h.mu.Unlock()
	waitFor(t, "second ready", func() bool { return h.count("ready") >= 2 })
	stop()
	h.check()
	if d.count("create") != 2 {
		t.Errorf("docker calls %v", d.calls)
	}
}

func TestHarnessThatNeverAnswersIsRecreated(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	d.unhealthy = 1
	stop := f.startWith(t, d)
	waitFor(t, "ready", func() bool { return h.count("ready") >= 1 })
	stop()
	h.check()
	if d.count("create") != 2 {
		t.Errorf("creates = %d, want 2", d.count("create"))
	}
	if !strings.Contains(f.logs.String(), "did not answer in time") {
		t.Error("timeout not logged")
	}
}

func TestExitedHarnessIsStartedAgain(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	stop := f.startWith(t, d)
	waitFor(t, "ready", func() bool { return h.count("ready") >= 1 })
	d.mutate(func(c *fakeContainer) { c.running = false })
	waitFor(t, "restart", func() bool { return d.count("start") >= 2 })
	stop()
	h.check()
	if d.count("create") != 1 || h.count("ready") != 1 {
		t.Errorf("want a plain start: ready %d, calls %v", h.count("ready"), d.calls)
	}
}

func TestHarnessFetchAndCreateFailuresBackOff(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	h.harnessStatus = []int{http.StatusServiceUnavailable, http.StatusNotFound, http.StatusOK}
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	d.failCreate = 2
	stop := f.startWith(t, d)
	waitFor(t, "ready", func() bool { return h.count("ready") >= 1 })
	// stop also asserts the create error's echoed value was redacted from the log.
	stop()
	h.check()
	if d.count("create") != 3 {
		t.Errorf("creates = %d, want 3", d.count("create"))
	}
	if !strings.Contains(f.logs.String(), "[redacted]") {
		t.Error("create failure not logged")
	}
}

func TestHeartbeatNoContentIsTolerated(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	h.heartbeats = []string{"", "", ""}
	f := newFixture(t, srv.URL, true)
	d := newFakeDocker(t)
	stop := f.startWith(t, d)
	waitFor(t, "heartbeats", func() bool { return h.count("heartbeat") >= 4 })
	stop()
	h.check()
	if strings.Contains(f.logs.String(), "heartbeat failed") || h.count("ready") != 1 {
		t.Errorf("204 treated as a failure or a status:\n%s", f.logs.String())
	}
}

func TestConfigHash(t *testing.T) {
	a := Harness{Image: "img:1", Environment: map[string]string{"A": "1", "B": "2"}}
	b := Harness{Image: "img:1", Environment: map[string]string{"B": "2", "A": "1"}}
	if ConfigHash(a) != ConfigHash(b) {
		t.Error("hash depends on map order")
	}
	for _, other := range []Harness{
		{Image: "img:2", Environment: a.Environment},
		{Image: "img:1", Environment: map[string]string{"A": "1", "B": "3"}},
		{Image: "img:1", Environment: map[string]string{"A": "1"}},
		// The separator keeps a value from sliding into the next name.
		{Image: "img:1", Environment: map[string]string{"A": "1\nB=2"}},
	} {
		if ConfigHash(other) == ConfigHash(a) {
			t.Errorf("hash unchanged for %+v", other)
		}
	}
	if len(ConfigHash(a)) != 64 {
		t.Errorf("hash %q is not hex sha256", ConfigHash(a))
	}
}
