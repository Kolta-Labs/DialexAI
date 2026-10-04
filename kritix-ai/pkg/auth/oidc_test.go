package auth

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func createTestJWT(iss, sub, aud, email string, groups []string, exp time.Time) string {
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`))
	claims := map[string]any{
		"iss":       iss,
		"sub":       sub,
		"aud":       aud,
		"email":     email,
		"name":      "Test User",
		"groups":    groups,
		"tenant_id": "squad-checkout",
		"exp":       exp.Unix(),
	}
	claimsBytes, _ := json.Marshal(claims)
	payload := base64.RawURLEncoding.EncodeToString(claimsBytes)
	sig := base64.RawURLEncoding.EncodeToString([]byte("signature_bytes"))
	return fmt.Sprintf("%s.%s.%s", header, payload, sig)
}

func TestOIDCClient_ExchangeCode_Success(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if err := r.ParseForm(); err != nil {
			http.Error(w, "bad form", http.StatusBadRequest)
			return
		}
		code := r.Form.Get("code")
		if code != "valid_auth_code_123" {
			http.Error(w, `{"error":"invalid_grant"}`, http.StatusBadRequest)
			return
		}

		idToken := createTestJWT(
			"https://auth.enterprise.internal",
			"usr-sso-999",
			"kritix-client-id",
			"lead-architect@enterprise.internal",
			[]string{"okta:qa-architects"},
			time.Now().Add(1*time.Hour),
		)

		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(OIDCTokenResponse{
			AccessToken: "access-token-xyz",
			TokenType:   "Bearer",
			ExpiresIn:   3600,
			IDToken:     idToken,
		})
	}))
	defer ts.Close()

	client := NewOIDCClient(OIDCProviderConfig{
		IssuerURL:     "https://auth.enterprise.internal",
		ClientID:      "kritix-client-id",
		ClientSecret:  "super-secret-key",
		RedirectURI:   "https://kritix.internal/api/v1/auth/oidc/callback",
		TokenEndpoint: ts.URL,
		HTTPClient:    ts.Client(),
	})

	identity, tokenResp, err := client.ExchangeCode(context.Background(), "valid_auth_code_123")
	if err != nil {
		t.Fatalf("unexpected exchange error: %v", err)
	}

	if identity.ID != "usr-sso-999" {
		t.Errorf("expected identity ID usr-sso-999, got %s", identity.ID)
	}
	if identity.Role != RoleTestArchitect {
		t.Errorf("expected RoleTestArchitect, got %v", identity.Role)
	}
	if tokenResp.AccessToken != "access-token-xyz" {
		t.Errorf("expected token xyz, got %s", tokenResp.AccessToken)
	}
}

func TestOIDCClient_ExchangeCode_InvalidGrant(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, `{"error":"invalid_grant","error_description":"Code expired"}`, http.StatusBadRequest)
	}))
	defer ts.Close()

	client := NewOIDCClient(OIDCProviderConfig{
		IssuerURL:     "https://auth.enterprise.internal",
		ClientID:      "kritix-client-id",
		TokenEndpoint: ts.URL,
		HTTPClient:    ts.Client(),
	})

	_, _, err := client.ExchangeCode(context.Background(), "expired_code")
	if err == nil {
		t.Fatal("expected error on invalid code exchange, got nil")
	}
}

func TestOIDCClient_ParseAndValidateIDToken_Mismatches(t *testing.T) {
	client := NewOIDCClient(OIDCProviderConfig{
		IssuerURL: "https://auth.enterprise.internal",
		ClientID:  "expected-client-id",
	})

	// 1. Expired Token
	expiredJWT := createTestJWT(
		"https://auth.enterprise.internal",
		"usr-1",
		"expected-client-id",
		"a@b.com",
		[]string{"devs"},
		time.Now().Add(-10*time.Minute),
	)
	if _, err := client.ParseAndValidateIDToken(expiredJWT); err != ErrOIDCTokenExpired {
		t.Errorf("expected ErrOIDCTokenExpired, got %v", err)
	}

	// 2. Issuer Mismatch
	wrongIssJWT := createTestJWT(
		"https://hostile-idp.attacker.com",
		"usr-1",
		"expected-client-id",
		"a@b.com",
		[]string{"devs"},
		time.Now().Add(10*time.Minute),
	)
	if _, err := client.ParseAndValidateIDToken(wrongIssJWT); err == nil {
		t.Error("expected error on issuer mismatch, got nil")
	}

	// 3. Audience Mismatch
	wrongAudJWT := createTestJWT(
		"https://auth.enterprise.internal",
		"usr-1",
		"other-client-id",
		"a@b.com",
		[]string{"devs"},
		time.Now().Add(10*time.Minute),
	)
	if _, err := client.ParseAndValidateIDToken(wrongAudJWT); err == nil {
		t.Error("expected error on audience mismatch, got nil")
	}
}
