package model

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"
)

// NewEgressClient returns an http.Client that can only dial allowlisted hosts (or loopback).
// Empty allowlist = air-gapped: every non-loopback dial fails, so a council fallback cannot leak.
// ponytail: process-level enforcement; certification still needs a network namespace / eBPF egress policy.
func NewEgressClient(allowedHosts []string, timeout time.Duration) *http.Client {
	d := &net.Dialer{}
	return &http.Client{Timeout: timeout, Transport: &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			host, _, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			if !egressAllowed(host, allowedHosts) {
				return nil, fmt.Errorf("egress blocked: %q is not on the allowlist", host)
			}
			return d.DialContext(ctx, network, addr)
		},
	}}
}

func egressAllowed(host string, allowed []string) bool {
	if ip := net.ParseIP(host); (ip != nil && ip.IsLoopback()) || host == "localhost" {
		return true
	}
	for _, a := range allowed {
		if strings.EqualFold(host, a) {
			return true
		}
	}
	return false
}
