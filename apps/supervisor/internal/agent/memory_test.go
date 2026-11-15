package agent

import (
	"os"
	"path/filepath"
	"testing"
)

func TestReadMemory(t *testing.T) {
	path := filepath.Join(t.TempDir(), "meminfo")
	content := "MemTotal:        3911960 kB\nMemFree:          212340 kB\nMemAvailable:    2876544 kB\nBuffers:           81508 kB\n"
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	m, err := ReadMemory(path)
	if err != nil {
		t.Fatal(err)
	}
	if m.TotalBytes != 3911960*1024 || m.AvailableBytes != 2876544*1024 {
		t.Errorf("got %+v", m)
	}
}

func TestReadMemoryRefusesAnIncompleteFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "meminfo")
	if err := os.WriteFile(path, []byte("MemTotal: 1024 kB\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadMemory(path); err == nil {
		t.Error("want an error without MemAvailable")
	}
	if _, err := ReadMemory(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Error("want an error for a missing file")
	}
}
