// Package docker is the small slice of the Docker Engine API the supervisor needs, spoken
// over the daemon's unix socket with the standard library alone.
package docker

import (
	"bytes"
	"cmp"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Client talks to one Docker daemon. Requests other than a pull are capped at Timeout.
type Client struct {
	HTTP    *http.Client
	Timeout time.Duration
}

// New dials socket for every request; the host in the URL is a placeholder.
func New(socket string) *Client {
	tr := &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", socket)
		},
	}
	return &Client{HTTP: &http.Client{Transport: tr}, Timeout: 2 * time.Minute}
}

const base = "http://docker"

// Container is what the supervisor reads back from an inspect.
type Container struct {
	ID      string
	Running bool
	Labels  map[string]string
}

// ContainerSpec is the create body; field names are the Engine API's.
type ContainerSpec struct {
	Image        string
	Env          []string
	User         string
	Labels       map[string]string
	ExposedPorts map[string]struct{}
	HostConfig   HostConfig
}

type HostConfig struct {
	Sysctls        map[string]string
	Mounts         []Mount
	PortBindings   map[string][]PortBinding
	RestartPolicy  RestartPolicy
	CapDrop        []string
	SecurityOpt    []string
	Init           bool
	ReadonlyRootfs bool
	Tmpfs          map[string]string
}

type Mount struct {
	Type   string
	Source string
	Target string
}

type PortBinding struct {
	HostIp   string
	HostPort string
}

type RestartPolicy struct {
	Name string
}

// Inspect returns nil, nil when no container has that name.
func (c *Client) Inspect(ctx context.Context, name string) (*Container, error) {
	var out struct {
		Id     string
		State  struct{ Running bool }
		Config struct{ Labels map[string]string }
	}
	status, err := c.call(ctx, http.MethodGet, "/containers/"+name+"/json", nil, &out)
	if status == http.StatusNotFound {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &Container{ID: out.Id, Running: out.State.Running, Labels: out.Config.Labels}, nil
}

// ImageExists reports whether ref is present locally.
func (c *Client) ImageExists(ctx context.Context, ref string) (bool, error) {
	// The ref goes in raw, as the docker CLI sends it; the daemon's router expects its slashes.
	status, err := c.call(ctx, http.MethodGet, "/images/"+ref+"/json", nil, nil)
	if status == http.StatusNotFound {
		return false, nil
	}
	return err == nil, err
}

// Pull reads the progress stream to the end, because the daemon reports a failed pull
// as an error line inside a 200 response.
func (c *Client) Pull(ctx context.Context, ref string) error {
	from, tag := SplitRef(ref)
	q := url.Values{"fromImage": {from}}
	if tag != "" {
		q.Set("tag", tag)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+"/images/create?"+q.Encode(), nil)
	if err != nil {
		return err
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return apiError(resp)
	}
	dec := json.NewDecoder(resp.Body)
	for {
		var line struct {
			Error       string `json:"error"`
			ErrorDetail struct {
				Message string `json:"message"`
			} `json:"errorDetail"`
		}
		if err := dec.Decode(&line); errors.Is(err, io.EOF) {
			return nil
		} else if err != nil {
			return fmt.Errorf("pull %s: reading progress: %w", ref, err)
		}
		if msg := cmp.Or(line.ErrorDetail.Message, line.Error); msg != "" {
			return fmt.Errorf("pull %s: %s", ref, msg)
		}
	}
}

// Stop is a no-op for a container that is already stopped or gone.
func (c *Client) Stop(ctx context.Context, name string) error {
	status, err := c.call(ctx, http.MethodPost, "/containers/"+name+"/stop", nil, nil)
	if status == http.StatusNotModified || status == http.StatusNotFound {
		return nil
	}
	return err
}

// Remove is a no-op for a container that is already gone.
func (c *Client) Remove(ctx context.Context, name string) error {
	status, err := c.call(ctx, http.MethodDelete, "/containers/"+name+"?force=true", nil, nil)
	if status == http.StatusNotFound {
		return nil
	}
	return err
}

// Create returns the new container's id.
func (c *Client) Create(ctx context.Context, name string, spec ContainerSpec) (string, error) {
	var out struct{ Id string }
	if _, err := c.call(ctx, http.MethodPost, "/containers/create?name="+url.QueryEscape(name), spec, &out); err != nil {
		return "", err
	}
	return out.Id, nil
}

// Start is a no-op for a container that already runs.
func (c *Client) Start(ctx context.Context, id string) error {
	status, err := c.call(ctx, http.MethodPost, "/containers/"+id+"/start", nil, nil)
	if status == http.StatusNotModified {
		return nil
	}
	return err
}

// call returns the status alongside any error, so callers can accept 304 or 404.
func (c *Client) call(ctx context.Context, method, path string, in, out any) (int, error) {
	if c.Timeout > 0 {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, c.Timeout)
		defer cancel()
	}
	var body io.Reader
	if in != nil {
		data, err := json.Marshal(in)
		if err != nil {
			return 0, err
		}
		body = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, body)
	if err != nil {
		return 0, err
	}
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return resp.StatusCode, apiError(resp)
	}
	if out != nil {
		if err := json.NewDecoder(resp.Body).Decode(out); err != nil {
			return resp.StatusCode, fmt.Errorf("%s %s: decoding response: %w", method, path, err)
		}
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	return resp.StatusCode, nil
}

func apiError(resp *http.Response) error {
	data, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
	var e struct{ Message string }
	if json.Unmarshal(data, &e) != nil || e.Message == "" {
		e.Message = strings.TrimSpace(string(data))
	}
	return fmt.Errorf("docker %s %s: %d %s", resp.Request.Method, resp.Request.URL.Path, resp.StatusCode, e.Message)
}

// SplitRef separates the tag the create endpoint wants on its own; a digest ref is passed
// whole, because the daemon resolves repo@sha256:... from fromImage alone.
func SplitRef(ref string) (from, tag string) {
	if strings.Contains(ref, "@") {
		return ref, ""
	}
	i := strings.LastIndex(ref, ":")
	if i > strings.LastIndex(ref, "/") {
		return ref[:i], ref[i+1:]
	}
	return ref, "latest"
}
