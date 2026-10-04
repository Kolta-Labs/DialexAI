package security

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

var (
	interceptorOnce sync.Once
	connectLogMu    sync.Mutex
)

// ConnectAttempt records an intercepted network socket connection.
type ConnectAttempt struct {
	Timestamp time.Time `json:"timestamp"`
	Network   string    `json:"network"`
	Address   string    `json:"address"`
	Host      string    `json:"host"`
	Port      string    `json:"port"`
	Status    string    `json:"status"` // "ALLOWED" or "BLOCKED"
	Reason    string    `json:"reason"`
}

// SetupEgressInterceptor installs a zero-egress / allowlist-enforcing DialContext into http.DefaultTransport.
// Activated when KRITIX_CONNECT_LOG, KRITIX_ZERO_EGRESS, or KRITIX_ALLOWED_TARGETS is set.
func SetupEgressInterceptor() {
	interceptorOnce.Do(func() {
		logPath := os.Getenv("KRITIX_CONNECT_LOG")
		zeroEgress := os.Getenv("KRITIX_ZERO_EGRESS") == "true" || os.Getenv("KRITIX_AIRGAP") == "true"
		allowedEnv := os.Getenv("KRITIX_ALLOWED_TARGETS")

		if logPath == "" && !zeroEgress && allowedEnv == "" {
			return
		}

		var allowedHosts []string
		if allowedEnv != "" {
			for _, part := range strings.Split(allowedEnv, ",") {
				p := strings.TrimSpace(part)
				if p != "" {
					allowedHosts = append(allowedHosts, p)
				}
			}
		}

		defaultDialer := &net.Dialer{
			Timeout:   10 * time.Second,
			KeepAlive: 30 * time.Second,
		}

		origTransport, ok := http.DefaultTransport.(*http.Transport)
		if !ok || origTransport == nil {
			origTransport = &http.Transport{}
		}

		cloned := origTransport.Clone()
		cloned.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, _, err := net.SplitHostPort(addr)
			if err != nil {
				host = addr
			}

			isLoopback := false
			if ip := net.ParseIP(host); ip != nil {
				isLoopback = ip.IsLoopback()
			} else if strings.EqualFold(host, "localhost") {
				isLoopback = true
			}

			status := "ALLOWED"
			reason := "authorized"

			if zeroEgress {
				status = "BLOCKED"
				reason = "zero-egress policy active: all external network calls prohibited"
			} else if len(allowedHosts) > 0 {
				allowed := isLoopback
				for _, h := range allowedHosts {
					if strings.EqualFold(host, h) || addr == h {
						allowed = true
						break
					}
				}
				if !allowed {
					status = "BLOCKED"
					reason = fmt.Sprintf("host %q not on KRITIX_ALLOWED_TARGETS allowlist (%v)", host, allowedHosts)
				}
			}

			// Log attempt to file if configured
			if logPath != "" {
				connectLogMu.Lock()
				f, fErr := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
				if fErr == nil {
					fmt.Fprintf(f, "%s|%s|%s|%s|%s|%s\n",
						time.Now().UTC().Format(time.RFC3339Nano),
						network,
						addr,
						host,
						status,
						reason,
					)
					_ = f.Close()
				}
				connectLogMu.Unlock()
			}

			if status == "BLOCKED" {
				return nil, fmt.Errorf("KRITIX EGRESS BLOCKED: %s (target: %s)", reason, addr)
			}

			return defaultDialer.DialContext(ctx, network, addr)
		}

		http.DefaultTransport = cloned
	})
}
