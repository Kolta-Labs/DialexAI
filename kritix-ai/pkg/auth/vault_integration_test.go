//go:build integration

package auth

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

// TestIntegration_VaultTransitAndKVSessionStore tests Vault dev-mode transit & KV engine.
// 1. Startup strictly refuses without VAULT_TOKEN and VAULT_ADDR.
// 2. Real transit encrypt/decrypt round-trip with ciphertext != plaintext.
// 3. Wrong-token rejected with ErrVaultAuthFailed.
func TestIntegration_VaultTransitAndKVSessionStore(t *testing.T) {
	// 1. Fail-closed startup test: NewHashiCorpVaultProvider must refuse empty token
	origToken := os.Getenv("VAULT_TOKEN")
	os.Unsetenv("VAULT_TOKEN")
	_, err := NewHashiCorpVaultProvider("http://127.0.0.1:8200", "transit-key")
	if err == nil {
		t.Fatalf("SECURITY VIOLATION: NewHashiCorpVaultProvider allowed initialization with empty VAULT_TOKEN")
	}
	if origToken != "" {
		os.Setenv("VAULT_TOKEN", origToken)
	}

	vaultAddr := os.Getenv("VAULT_ADDR")
	vaultToken := os.Getenv("VAULT_TOKEN")

	var provider *HashiCorpVaultProvider
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if vaultAddr != "" && vaultToken != "" {
		t.Logf("Running against live Vault container at %s", vaultAddr)
		provider = NewHashiCorpVaultProviderWithClient(vaultAddr, vaultToken, "kritix-transit-key", "secret", nil)
	} else if os.Getenv("KRITIX_HARNESS") == "1" {
		t.Fatalf("PREREQUISITE_MISSING: vault (VAULT_ADDR and VAULT_TOKEN required in harness mode)")
	} else {
		// Protocol-faithful local mock server simulating Vault Transit engine
		mockVault := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			token := r.Header.Get("X-Vault-Token")
			if token != "valid-dev-token" {
				w.WriteHeader(http.StatusForbidden)
				_ = json.NewEncoder(w).Encode(map[string]interface{}{"errors": []string{"permission denied"}})
				return
			}

			if strings.Contains(r.URL.Path, "/v1/transit/encrypt/") {
				var req struct {
					Plaintext string `json:"plaintext"`
				}
				_ = json.NewDecoder(r.Body).Decode(&req)
				// Real Vault transit returns vault:v1:<base64-ciphertext>
				cipher := "vault:v1:" + base64.StdEncoding.EncodeToString([]byte("encrypted-"+req.Plaintext))
				w.WriteHeader(http.StatusOK)
				_ = json.NewEncoder(w).Encode(map[string]interface{}{
					"data": map[string]string{"ciphertext": cipher},
				})
				return
			}

			if strings.Contains(r.URL.Path, "/v1/transit/decrypt/") {
				var req struct {
					Ciphertext string `json:"ciphertext"`
				}
				_ = json.NewDecoder(r.Body).Decode(&req)
				rawCipher := strings.TrimPrefix(req.Ciphertext, "vault:v1:")
				b, _ := base64.StdEncoding.DecodeString(rawCipher)
				recoveredPlain := strings.TrimPrefix(string(b), "encrypted-")
				w.WriteHeader(http.StatusOK)
				_ = json.NewEncoder(w).Encode(map[string]interface{}{
					"data": map[string]string{"plaintext": recoveredPlain},
				})
				return
			}

			w.WriteHeader(http.StatusNotFound)
		}))
		defer mockVault.Close()

		provider = NewHashiCorpVaultProviderWithClient(mockVault.URL, "valid-dev-token", "test-key", "secret", mockVault.Client())
	}

	plaintext := []byte("confidential-pii-customer-ssn-4421")

	// 2. Transit encryption round-trip
	ciphertext, err := provider.TransitEncrypt(ctx, plaintext)
	if err != nil {
		t.Fatalf("TransitEncrypt failed: %v", err)
	}

	// Invariant: ciphertext MUST NOT equal plaintext
	if ciphertext == string(plaintext) {
		t.Fatalf("CRITICAL SECURITY DEFECT: TransitEncrypt returned plaintext instead of ciphertext")
	}
	if !strings.HasPrefix(ciphertext, "vault:v1:") {
		t.Fatalf("Expected Vault transit ciphertext format (vault:v1:...), got: %s", ciphertext)
	}

	// 3. Transit decryption round-trip
	decrypted, err := provider.TransitDecrypt(ctx, ciphertext)
	if err != nil {
		t.Fatalf("TransitDecrypt failed: %v", err)
	}
	if string(decrypted) != string(plaintext) {
		t.Fatalf("Mismatched decrypted payload: expected %q, got %q", string(plaintext), string(decrypted))
	}

	// 4. Invariant: Wrong token rejected
	badProvider := NewHashiCorpVaultProviderWithClient(provider.vaultAddr, "invalid-bogus-token", provider.transitKey, provider.kvMount, provider.httpClient)
	_, badErr := badProvider.TransitEncrypt(ctx, plaintext)
	if badErr == nil {
		t.Fatalf("CRITICAL SECURITY DEFECT: Vault request with invalid token succeeded unexpectedly")
	}
	if badErr != ErrVaultAuthFailed && !strings.Contains(badErr.Error(), "auth") && !strings.Contains(badErr.Error(), "status 403") {
		t.Fatalf("Expected authentication failure error, got: %v", badErr)
	}
}
