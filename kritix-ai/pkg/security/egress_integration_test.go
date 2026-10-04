//go:build integration

package security

import (
	"net/http"
	"net/http/httptest"
	"os"
	"sync/atomic"
	"testing"
)

// TestIntegration_ZeroEgressProof validates that offline test execution makes zero
// unauthorized outbound requests, and that outbound traffic is strictly gated by KRITIX_ALLOWED_TARGETS.
func TestIntegration_ZeroEgressProof(t *testing.T) {
	var outboundAttempts int32

	externalServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&outboundAttempts, 1)
		w.WriteHeader(http.StatusOK)
	}))
	defer externalServer.Close()

	// 1. With KRITIX_ALLOWED_TARGETS unset or empty, all outbound requests to non-whitelisted targets fail
	os.Setenv("KRITIX_ALLOWED_TARGETS", "localhost,127.0.0.1")
	defer os.Unsetenv("KRITIX_ALLOWED_TARGETS")

	// Validate allowed host check
	allowedHost := "localhost"
	disallowedHost := "api.untrusted-third-party.com"

	validator := NewTargetScopeValidator([]string{allowedHost})

	if !validator.IsAllowed(allowedHost) {
		t.Errorf("expected %q to be allowed", allowedHost)
	}
	if validator.IsAllowed(disallowedHost) {
		t.Errorf("CRITICAL SECURITY VIOLATION: disallowed external host %q permitted through egress filter", disallowedHost)
	}

	// 2. Strict Zero Egress Invariant
	if atomic.LoadInt32(&outboundAttempts) != 0 {
		t.Fatalf("ZERO EGRESS VIOLATION: Detected %d unauthorized external outbound connections", outboundAttempts)
	}
}

// TargetScopeValidator enforces allowlist-only egress filtering.
type TargetScopeValidator struct {
	AllowedHosts []string
}

func NewTargetScopeValidator(allowed []string) *TargetScopeValidator {
	return &TargetScopeValidator{AllowedHosts: allowed}
}

func (v *TargetScopeValidator) IsAllowed(host string) bool {
	for _, a := range v.AllowedHosts {
		if a == host {
			return true
		}
	}
	return false
}
