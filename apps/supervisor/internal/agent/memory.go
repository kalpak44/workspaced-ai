package agent

import (
	"bufio"
	"fmt"
	"os"
	"strconv"
	"strings"
)

// Memory is the VM's memory as its kernel counts it; Hetzner's metrics have no memory series,
// so the heartbeat is the only way it reaches the app.
type Memory struct {
	TotalBytes     uint64 `json:"totalBytes"`
	AvailableBytes uint64 `json:"availableBytes"`
}

// ReadMemory parses a /proc/meminfo file. Inside a container it is still the VM's own file,
// because /proc/meminfo is not namespaced.
func ReadMemory(path string) (Memory, error) {
	f, err := os.Open(path)
	if err != nil {
		return Memory{}, err
	}
	defer f.Close()
	var m Memory
	var total, available bool
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		name, rest, ok := strings.Cut(sc.Text(), ":")
		if !ok || (name != "MemTotal" && name != "MemAvailable") {
			continue
		}
		kb, err := strconv.ParseUint(strings.TrimSuffix(strings.TrimSpace(rest), " kB"), 10, 64)
		if err != nil {
			return Memory{}, fmt.Errorf("%s: %s: %w", path, name, err)
		}
		if name == "MemTotal" {
			m.TotalBytes, total = kb*1024, true
		} else {
			m.AvailableBytes, available = kb*1024, true
		}
	}
	if err := sc.Err(); err != nil {
		return Memory{}, err
	}
	if !total || !available {
		return Memory{}, fmt.Errorf("%s: no MemTotal or MemAvailable", path)
	}
	return m, nil
}
