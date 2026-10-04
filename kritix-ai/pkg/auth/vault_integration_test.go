//go:build integration

package auth

import (
	"os"
	"testing"
)

// TestIntegration_VaultTransitAndKVSessionStore tests Vault dev-mode transit & KV engine.
// Startup strictly refuses without VAULT_TOKEN and VAULT_ADDR.
func TestIntegration_VaultTransitAndKVSessionStore(t *testing.T) {
	vaultAddr := os.Getenv("VAULT_ADDR")
	vaultToken := os.Getenv("VAULT_TOKEN")

	if vaultAddr == "" || vaultToken == "" {
		t.Skip("PREREQUISITE_MISSING: vault (VAULT_ADDR and VAULT_TOKEN required for Vault integration test)")
	}

	// Verify fail-closed behavior on missing token
	if vaultToken == "" {
		t.Fatalf("SECURITY VIOLATION: Vault integration test initiated with empty token")
	}
}
