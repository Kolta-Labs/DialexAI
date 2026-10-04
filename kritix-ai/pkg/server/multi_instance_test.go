package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"kritix/pkg/auth"
	"kritix/pkg/driver"
	"kritix/pkg/studio"
)

func TestMultiInstance_SharedStoreAndIsolation(t *testing.T) {
	tempDir, err := os.MkdirTemp("", "kritix_multi_instance_*")
	if err != nil {
		t.Fatalf("failed to create temp store dir: %v", err)
	}
	defer os.RemoveAll(tempDir)

	sharedStore, err := NewPersistentFileStore(tempDir)
	if err != nil {
		t.Fatalf("failed to create shared store: %v", err)
	}

	am, err := auth.NewEnterpriseAuthManager("enterprise-super-secret-key-minimum-32-bytes-long!")
	if err != nil {
		t.Fatalf("failed to create auth manager: %v", err)
	}

	// Instance 1
	srv1 := NewServerWithConfig(ServerConfig{
		Host:        "127.0.0.1",
		Port:        0,
		AuthManager: am,
		Store:       sharedStore,
	})

	// Instance 2 (Simulating horizontal scale)
	srv2 := NewServerWithConfig(ServerConfig{
		Host:        "127.0.0.1",
		Port:        0,
		AuthManager: am,
		Store:       sharedStore,
	})

	// 1. Instance 1 records action for squad-checkout
	s1 := srv1.getSession("squad-checkout", "session-42")
	s1.RecordInteraction(studio.HumanAction{
		Type:            driver.ActionClick,
		TargetRole:      "button",
		TargetText:      "Pay Now",
		StepIntent:      "Execute instant settlement",
		ExpectedOutcome: "Order confirmed",
		Timestamp:       time.Now(),
	})
	if err := sharedStore.SaveSession("squad-checkout", s1); err != nil {
		t.Fatalf("failed to save session on instance 1: %v", err)
	}

	// 2. Instance 2 retrieves the same session across shared store via srv2
	s2 := srv2.getSession("squad-checkout", "session-42")
	if s2 == nil || len(s2.Actions) != 1 || s2.Actions[0].TargetText != "Pay Now" {
		t.Errorf("instance 2 read corrupted session data: %+v", s2)
	}

	// 3. Instance 2 attempts cross-tenant retrieval on squad-payments
	_, err = sharedStore.GetSession("squad-payments", "session-42")
	if err != ErrSessionNotFound {
		t.Errorf("expected ErrSessionNotFound for nonexistent cross-tenant session, got: %v", err)
	}
}

func TestOIDC_LoginAndCallbackFlow(t *testing.T) {
	srv, _, _, _ := createTestServerAndTokens(t)

	// 1. Test OIDC Login Redirect
	loginReq := httptest.NewRequest("GET", "/api/v1/auth/oidc/login?redirect_uri=/callback&state=test-state", nil)
	loginRec := httptest.NewRecorder()
	srv.handleOIDCLogin(loginRec, loginReq)

	if loginRec.Code != http.StatusFound {
		t.Errorf("expected 302 redirect for OIDC login, got %d", loginRec.Code)
	}
	loc := loginRec.Header().Get("Location")
	if !stringsContains(loc, "sso.enterprise.internal") || !stringsContains(loc, "client_id=kritix-enterprise") {
		t.Errorf("unexpected OIDC redirect URL: %s", loc)
	}

	// 2. Test OIDC Callback & Token Generation
	callbackReq := httptest.NewRequest("GET", "/api/v1/auth/oidc/callback?code=alice-1234&state=test-state", nil)
	callbackRec := httptest.NewRecorder()
	srv.handleOIDCCallback(callbackRec, callbackReq)

	if callbackRec.Code != http.StatusOK {
		t.Fatalf("expected 200 OK for valid OIDC callback, got %d (body: %s)", callbackRec.Code, callbackRec.Body.String())
	}

	var resp map[string]any
	if err := json.NewDecoder(callbackRec.Body).Decode(&resp); err != nil {
		t.Fatalf("failed to decode OIDC callback response: %v", err)
	}

	token, ok := resp["access_token"].(string)
	if !ok || token == "" {
		t.Errorf("expected access_token in OIDC response: %+v", resp)
	}

	// Validate token works with server auth
	user, err := srv.AuthManager().ValidateToken(token)
	if err != nil {
		t.Fatalf("minted OIDC token failed validation: %v", err)
	}
	if user.Email != "user-alice-1234@enterprise.internal" {
		t.Errorf("unexpected user email from OIDC: %s", user.Email)
	}
}

func TestRateLimiterAndKillSwitch(t *testing.T) {
	// 1. Rate Limiting Test
	limiter := NewRateLimiter(2.0) // 2 req/sec
	if !limiter.Allow() || !limiter.Allow() {
		t.Errorf("expected first 2 requests to be allowed")
	}
	if limiter.Allow() {
		t.Errorf("expected 3rd immediate request to be rate limited")
	}

	// 2. Kill Switch Test
	killFile := filepath.Join(os.TempDir(), "kritix.kill")
	_ = os.WriteFile(killFile, []byte("HALT"), 0644)
	defer os.Remove(killFile)

	if !isKillSwitchActive() {
		t.Errorf("expected kill switch to be active when /tmp/kritix.kill exists")
	}
}

func TestPrometheusMetricsAndHealthz(t *testing.T) {
	srv, _, _, _ := createTestServerAndTokens(t)

	// Test /metrics
	req := httptest.NewRequest("GET", "/metrics", nil)
	w := httptest.NewRecorder()
	srv.handleMetricsPrometheus(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200 OK for /metrics, got %d", w.Code)
	}
	if !stringsContains(w.Body.String(), "kritix_server_up 1") {
		t.Errorf("missing kritix_server_up metric in output:\n%s", w.Body.String())
	}
}

func stringsContains(s, substr string) bool {
	return bytes.Contains([]byte(s), []byte(substr))
}
