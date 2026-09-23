package api

import (
	"context"
	"net/http"
	"testing"
)

func TestDefaultTsnetConfig(t *testing.T) {
	cfg := DefaultTsnetConfig()
	if cfg.Enabled {
		t.Errorf("Expected tsnet to be disabled by default")
	}
	if cfg.Hostname != "dialex-server" {
		t.Errorf("Expected hostname 'dialex-server', got '%s'", cfg.Hostname)
	}
	if cfg.Port != 7890 {
		t.Errorf("Expected port 7890, got %d", cfg.Port)
	}
	if cfg.StateDir == "" {
		t.Errorf("Expected non-empty StateDir")
	}
}

func TestStartTsnetServer_DisabledReturnsError(t *testing.T) {
	cfg := DefaultTsnetConfig()
	cfg.Enabled = false

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {})
	logBuf := NewLogRingBuffer(10)

	_, _, err := StartTsnetServer(context.Background(), cfg, handler, logBuf)
	if err == nil {
		t.Errorf("Expected error when starting disabled tsnet server")
	}
}
