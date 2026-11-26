package agent

import (
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestRenderFirewallSplitsFamiliesAndChecksEverything(t *testing.T) {
	got, err := RenderFirewall([]FirewallRule{
		{Protocol: "tcp", Port: "443", SourceIPs: []string{"0.0.0.0/0", "::/0"}},
		{Protocol: "udp", Port: "8000-8100", SourceIPs: []string{"203.0.113.7"}},
		{Protocol: "icmp", SourceIPs: []string{"10.0.0.0/8", "2001:db8::/32"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{
		"ip saddr { 0.0.0.0/0 } tcp dport 443 accept",
		"ip6 saddr { ::/0 } tcp dport 443 accept",
		"ip saddr { 203.0.113.7/32 } udp dport 8000-8100 accept",
		"ip saddr { 10.0.0.0/8 } meta l4proto icmp accept",
		"ip6 saddr { 2001:db8::/32 } meta l4proto ipv6-icmp accept",
	}
	for _, line := range want {
		if !strings.Contains(got, line+"\n") {
			t.Errorf("missing %q in\n%s", line, got)
		}
	}
}

func TestRenderFirewallRefusesAnythingButAProtocolPortAndAddress(t *testing.T) {
	for name, r := range map[string]FirewallRule{
		"protocol": {Protocol: "tcp; flush ruleset", Port: "80", SourceIPs: []string{"::/0"}},
		"port":     {Protocol: "tcp", Port: "80 accept", SourceIPs: []string{"::/0"}},
		"range":    {Protocol: "tcp", Port: "90-80", SourceIPs: []string{"::/0"}},
		"zero":     {Protocol: "udp", Port: "0", SourceIPs: []string{"::/0"}},
		"source":   {Protocol: "tcp", Port: "80", SourceIPs: []string{"1.2.3.4 } accept #"}},
		"icmpPort": {Protocol: "icmp", Port: "80", SourceIPs: []string{"::/0"}},
	} {
		if _, err := RenderFirewall([]FirewallRule{r}); err == nil {
			t.Errorf("%s: accepted %+v", name, r)
		}
	}
}

func TestApplyFirewallWritesOnlyWhereTheImageLooksAndOnlyChanges(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "firewall.d")
	s := &Supervisor{FirewallFile: filepath.Join(dir, "rules.nft")}
	log := discardLog()
	rules := []FirewallRule{{Protocol: "tcp", Port: "80", SourceIPs: []string{"0.0.0.0/0"}}}

	s.applyFirewall(rules, log)
	if _, err := os.Stat(s.FirewallFile); !os.IsNotExist(err) {
		t.Fatalf("wrote rules for an image without the directory: %v", err)
	}

	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	s.applyFirewall(rules, log)
	first, err := os.Stat(s.FirewallFile)
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(10 * time.Millisecond)
	s.applyFirewall(rules, log)
	if again, _ := os.Stat(s.FirewallFile); !again.ModTime().Equal(first.ModTime()) {
		t.Error("rewrote an unchanged file")
	}

	s.applyFirewall([]FirewallRule{{Protocol: "bogus"}}, log)
	if text, _ := os.ReadFile(s.FirewallFile); !strings.Contains(string(text), "tcp dport 80") {
		t.Errorf("a refused set replaced the applied one:\n%s", text)
	}
	if entries, _ := os.ReadDir(dir); len(entries) != 1 {
		t.Errorf("left %d files behind", len(entries))
	}
}

func TestHeartbeatAppliesTheRulesItCarriesAndKeepsThemOnNull(t *testing.T) {
	h, srv := newHub(t, http.StatusNoContent)
	h.firewall = `[{"protocol":"tcp","port":"8080","sourceIps":["0.0.0.0/0"],"description":"x"}]`
	f := newFixture(t, srv.URL, true)
	dir := filepath.Join(t.TempDir(), "firewall.d")
	if err := os.Mkdir(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(dir, "rules.nft")
	stop := f.startWith(t, newFakeDocker(t), func(s *Supervisor) { s.FirewallFile = file })
	waitFor(t, "rules written", func() bool {
		text, _ := os.ReadFile(file)
		return strings.Contains(string(text), "tcp dport 8080 accept")
	})
	h.mu.Lock()
	h.firewall = "null"
	h.mu.Unlock()
	beats := h.count("heartbeat")
	waitFor(t, "a heartbeat without rules", func() bool { return h.count("heartbeat") >= beats+2 })
	stop()
	h.check()
	if text, _ := os.ReadFile(file); !strings.Contains(string(text), "tcp dport 8080 accept") {
		t.Errorf("a null firewall dropped the applied rules:\n%s", text)
	}
}

func discardLog() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
