package agent

import (
	"bytes"
	"fmt"
	"log/slog"
	"net/netip"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// FirewallRule is one inbound rule as the hub hands it over. Its description is never written
// out: it is free text, and the rules file is parsed by nft.
type FirewallRule struct {
	Protocol  string   `json:"protocol"`
	Port      string   `json:"port"`
	SourceIPs []string `json:"sourceIps"`
}

var portPattern = regexp.MustCompile(`^(\d{1,5})(?:-(\d{1,5}))?$`)

// RenderFirewall turns the rules into nft statements for the input chain the image defines,
// which drops whatever they do not accept. Everything is checked again here, so nothing the hub
// hands over reaches nft unless it is a protocol, a port and an address.
func RenderFirewall(rules []FirewallRule) (string, error) {
	var b strings.Builder
	b.WriteString("# Written by the supervisor from the workspace's firewall rules.\n")
	for i, r := range rules {
		v4, v6, err := sources(r.SourceIPs)
		if err != nil {
			return "", fmt.Errorf("rule %d: %w", i, err)
		}
		var match4, match6 string
		switch r.Protocol {
		case "tcp", "udp":
			port, err := ports(r.Port)
			if err != nil {
				return "", fmt.Errorf("rule %d: %w", i, err)
			}
			match4 = r.Protocol + " dport " + port
			match6 = match4
		case "icmp":
			if r.Port != "" {
				return "", fmt.Errorf("rule %d: an icmp rule has no port", i)
			}
			match4, match6 = "meta l4proto icmp", "meta l4proto ipv6-icmp"
		default:
			return "", fmt.Errorf("rule %d: protocol %q is not tcp, udp or icmp", i, r.Protocol)
		}
		if len(v4) > 0 {
			fmt.Fprintf(&b, "ip saddr { %s } %s accept\n", strings.Join(v4, ", "), match4)
		}
		if len(v6) > 0 {
			fmt.Fprintf(&b, "ip6 saddr { %s } %s accept\n", strings.Join(v6, ", "), match6)
		}
	}
	return b.String(), nil
}

func ports(port string) (string, error) {
	m := portPattern.FindStringSubmatch(port)
	if m == nil {
		return "", fmt.Errorf("port %q is not a port or a range", port)
	}
	start, _ := strconv.Atoi(m[1])
	end := start
	if m[2] != "" {
		end, _ = strconv.Atoi(m[2])
	}
	if start < 1 || end > 65535 || start > end {
		return "", fmt.Errorf("port %q is out of range", port)
	}
	if start == end {
		return strconv.Itoa(start), nil
	}
	return fmt.Sprintf("%d-%d", start, end), nil
}

// sources splits the networks by family. None at all lets in no one rather than anyone: the hub
// always names anywhere explicitly, so an empty list is not one it sent.
func sources(given []string) (v4, v6 []string, err error) {
	for _, s := range given {
		p, err := netip.ParsePrefix(s)
		if err != nil {
			a, aerr := netip.ParseAddr(s)
			if aerr != nil {
				return nil, nil, fmt.Errorf("source %q is not an address or network", s)
			}
			p = netip.PrefixFrom(a, a.BitLen())
		}
		p = p.Masked()
		if p.Addr().Is4() {
			v4 = append(v4, p.String())
		} else {
			v6 = append(v6, p.String())
		}
	}
	return v4, v6, nil
}

// applyFirewall writes the rules where the image's path unit picks them up and reloads the
// table. Only when that directory exists: an image without it filters nothing itself and sits
// behind a firewall of its own, so a file there would apply nowhere. An unchanged file is not
// rewritten, so a heartbeat does not reload the table every time.
func (s *Supervisor) applyFirewall(rules []FirewallRule, log *slog.Logger) {
	if s.FirewallFile == "" {
		return
	}
	dir := filepath.Dir(s.FirewallFile)
	if _, err := os.Stat(dir); err != nil {
		return
	}
	text, err := RenderFirewall(rules)
	if err != nil {
		log.Error("firewall rules refused, keeping the ones applied", "error", err.Error())
		return
	}
	if current, err := os.ReadFile(s.FirewallFile); err == nil && bytes.Equal(current, []byte(text)) {
		return
	}
	tmp, err := os.CreateTemp(dir, ".rules-*")
	if err != nil {
		log.Error("firewall rules not written", "error", err.Error())
		return
	}
	defer os.Remove(tmp.Name())
	_, werr := tmp.WriteString(text)
	cerr := tmp.Close()
	if werr != nil || cerr != nil {
		log.Error("firewall rules not written", "error", errString(firstErr(werr, cerr)))
		return
	}
	// The rename is what the path unit sees, so it never reads a half-written file.
	if err := os.Rename(tmp.Name(), s.FirewallFile); err != nil {
		log.Error("firewall rules not written", "error", err.Error())
		return
	}
	log.Info("firewall rules written", "rules", len(rules))
}

func firstErr(errs ...error) error {
	for _, err := range errs {
		if err != nil {
			return err
		}
	}
	return nil
}
