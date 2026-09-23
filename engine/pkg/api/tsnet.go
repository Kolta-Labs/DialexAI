package api

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"tailscale.com/tsnet"
)

// TsnetConfig configures the embedded userspace Tailscale node.
type TsnetConfig struct {
	Enabled   bool   `json:"enabled"`
	Hostname  string `json:"hostname"`
	AuthKey   string `json:"authKey"`
	StateDir  string `json:"stateDir"`
	Ephemeral bool   `json:"ephemeral"`
	Port      int    `json:"port"`
}

// DefaultTsnetConfig returns standard defaults for embedded tsnet.
func DefaultTsnetConfig() TsnetConfig {
	homeDir, _ := os.UserHomeDir()
	stateDir := filepath.Join(homeDir, ".config", "dialex", "tsnet")

	return TsnetConfig{
		Enabled:   false,
		Hostname:  "dialex-server",
		AuthKey:   "",
		StateDir:  stateDir,
		Ephemeral: false,
		Port:      7890,
	}
}

// StartTsnetServer starts a userspace Tailscale node and begins listening on the tailnet.
func StartTsnetServer(ctx context.Context, cfg TsnetConfig, handler http.Handler, logBuf *LogRingBuffer) (*tsnet.Server, net.Listener, error) {
	if !cfg.Enabled {
		return nil, nil, fmt.Errorf("tsnet is not enabled in config")
	}

	if cfg.StateDir == "" {
		homeDir, _ := os.UserHomeDir()
		cfg.StateDir = filepath.Join(homeDir, ".config", "dialex", "tsnet")
	}
	_ = os.MkdirAll(cfg.StateDir, 0700)

	tsServer := &tsnet.Server{
		Hostname:  cfg.Hostname,
		AuthKey:   cfg.AuthKey,
		Dir:       cfg.StateDir,
		Ephemeral: cfg.Ephemeral,
		Logf: func(format string, args ...any) {
			msg := fmt.Sprintf("[tsnet] "+format, args...)
			if logBuf != nil {
				logBuf.Add("INFO", "tsnet", msg)
			}
			log.Print(msg)
		},
	}

	addr := fmt.Sprintf(":%d", cfg.Port)
	listener, err := tsServer.Listen("tcp", addr)
	if err != nil {
		_ = tsServer.Close()
		return nil, nil, fmt.Errorf("failed to listen on tsnet %s: %w", addr, err)
	}

	msg := fmt.Sprintf("Embedded Tailscale node [%s] listening on :%d (Mesh WireGuard)", cfg.Hostname, cfg.Port)
	if logBuf != nil {
		logBuf.Add("INFO", "tsnet", msg)
	}
	log.Println(msg)

	go func() {
		if err := http.Serve(listener, handler); err != nil && err != http.ErrServerClosed {
			errMsg := fmt.Sprintf("tsnet HTTP server error: %v", err)
			if logBuf != nil {
				logBuf.Add("ERROR", "tsnet", errMsg)
			}
			log.Println(errMsg)
		}
	}()

	return tsServer, listener, nil
}
