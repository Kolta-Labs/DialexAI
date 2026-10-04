package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"kritix/pkg/auth"
)

func createTestServerAndTokens(t *testing.T) (*Server, string, string, string) {
	t.Helper()
	am, err := auth.NewEnterpriseAuthManager("enterprise-super-secret-key-minimum-32-bytes-long!")
	if err != nil {
		t.Fatalf("failed to create auth manager: %v", err)
	}

	srv := NewServerWithConfig(ServerConfig{
		Host:           "127.0.0.1",
		Port:           0,
		AllowedOrigins: []string{"http://localhost:3000", "http://127.0.0.1:9090"},
		AuthManager:    am,
		OIDCClient: auth.NewOIDCClient(auth.OIDCProviderConfig{
			IssuerURL:       "https://sso.enterprise.internal",
			ClientID:        "kritix-enterprise",
			AllowMockTokens: true,
		}),
	})

	// Mint tokens
	adminToken, err := am.GenerateToken(auth.UserIdentity{
		ID:    "user-admin",
		Email: "admin@enterprise.com",
		Role:  auth.RoleAdmin,
		Squad: "platform",
	}, 2*time.Hour)
	if err != nil {
		t.Fatalf("failed to generate admin token: %v", err)
	}

	testerSquadAToken, err := am.GenerateToken(auth.UserIdentity{
		ID:    "user-tester-a",
		Email: "tester-a@enterprise.com",
		Role:  auth.RoleTester,
		Squad: "squad-checkout",
	}, 2*time.Hour)
	if err != nil {
		t.Fatalf("failed to generate tester squad A token: %v", err)
	}

	viewerToken, err := am.GenerateToken(auth.UserIdentity{
		ID:    "user-viewer",
		Email: "viewer@enterprise.com",
		Role:  auth.RoleViewer,
		Squad: "squad-checkout",
	}, 2*time.Hour)
	if err != nil {
		t.Fatalf("failed to generate viewer token: %v", err)
	}

	return srv, adminToken, testerSquadAToken, viewerToken
}

func TestServerHealth(t *testing.T) {
	srv, _, _, _ := createTestServerAndTokens(t)

	req := httptest.NewRequest("GET", "/api/v1/health", nil)
	w := httptest.NewRecorder()

	srv.handleHealth(w, req)

	resp := w.Result()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var body map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if body["status"] != "online" {
		t.Errorf("expected status 'online', got %v", body["status"])
	}
}

func TestServer_AuthN_401_Unauthenticated(t *testing.T) {
	srv, _, _, _ := createTestServerAndTokens(t)

	// 1. Missing Authorization header
	req := httptest.NewRequest("GET", "/api/v1/blueprints", nil)
	w := httptest.NewRecorder()

	handler := srv.withAuth(auth.PermViewReports, http.HandlerFunc(srv.handleBlueprints))
	handler.ServeHTTP(w, req)

	if w.Result().StatusCode != http.StatusUnauthorized {
		t.Errorf("expected 401 Unauthorized for missing token, got %d", w.Result().StatusCode)
	}

	// 2. Malformed token
	req2 := httptest.NewRequest("GET", "/api/v1/blueprints", nil)
	req2.Header.Set("Authorization", "Bearer invalid-tampered-token")
	w2 := httptest.NewRecorder()

	handler.ServeHTTP(w2, req2)
	if w2.Result().StatusCode != http.StatusUnauthorized {
		t.Errorf("expected 401 Unauthorized for invalid token, got %d", w2.Result().StatusCode)
	}
}

func TestServer_RBAC_403_Forbidden(t *testing.T) {
	srv, _, _, viewerToken := createTestServerAndTokens(t)

	// Viewer attempts to run blueprint (requires PermExecuteWorkflows)
	payload := []byte(`{"blueprint_id":"pr-smoke-guard","target_url":"http://localhost:3000"}`)
	req := httptest.NewRequest("POST", "/api/v1/blueprints/run", bytes.NewBuffer(payload))
	req.Header.Set("Authorization", "Bearer "+viewerToken)
	w := httptest.NewRecorder()

	handler := srv.withAuth(auth.PermExecuteWorkflows, http.HandlerFunc(srv.handleRunBlueprint))
	handler.ServeHTTP(w, req)

	if w.Result().StatusCode != http.StatusForbidden {
		t.Errorf("expected 403 Forbidden for Viewer running blueprint, got %d", w.Result().StatusCode)
	}
}

func TestServer_TenantSessionIsolation_CrossTenantAccess(t *testing.T) {
	srv, adminToken, testerSquadAToken, _ := createTestServerAndTokens(t)

	// 1. Tester in squad-checkout records an action in their session
	actionPayload := []byte(`{
		"type": "click",
		"target_id": "#checkout-btn",
		"step_intent": "Tester clicks checkout"
	}`)
	reqA := httptest.NewRequest("POST", "/api/v1/studio/action", bytes.NewBuffer(actionPayload))
	reqA.Header.Set("Authorization", "Bearer "+testerSquadAToken)
	reqA.Header.Set("X-Session-ID", "sess-alpha")
	wA := httptest.NewRecorder()

	handler := srv.withAuth(auth.PermRecordJourneys, http.HandlerFunc(srv.handleStudioAction))
	handler.ServeHTTP(wA, reqA)

	if wA.Result().StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK recording action for squad-checkout, got %d", wA.Result().StatusCode)
	}

	// 2. Tester attempts to maliciously access squad-billing session -> 403 Forbidden
	reqCross := httptest.NewRequest("POST", "/api/v1/studio/action", bytes.NewBuffer(actionPayload))
	reqCross.Header.Set("Authorization", "Bearer "+testerSquadAToken)
	reqCross.Header.Set("X-Tenant-ID", "squad-billing")
	wCross := httptest.NewRecorder()

	handler.ServeHTTP(wCross, reqCross)
	if wCross.Result().StatusCode != http.StatusForbidden {
		t.Errorf("expected 403 Forbidden when accessing another squad's session, got %d", wCross.Result().StatusCode)
	}

	// 3. Admin can access with explicit tenant ID
	reqAdmin := httptest.NewRequest("POST", "/api/v1/studio/action", bytes.NewBuffer(actionPayload))
	reqAdmin.Header.Set("Authorization", "Bearer "+adminToken)
	reqAdmin.Header.Set("X-Tenant-ID", "squad-checkout")
	wAdmin := httptest.NewRecorder()

	handler.ServeHTTP(wAdmin, reqAdmin)
	if wAdmin.Result().StatusCode != http.StatusOK {
		t.Errorf("expected Admin to succeed, got %d", wAdmin.Result().StatusCode)
	}
}

func TestServer_CORS_AllowedAndDisallowedOrigins(t *testing.T) {
	srv, _, _, _ := createTestServerAndTokens(t)
	corsHandler := srv.withCORS(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	// 1. Allowed origin
	req1 := httptest.NewRequest("GET", "/api/v1/health", nil)
	req1.Header.Set("Origin", "http://localhost:3000")
	w1 := httptest.NewRecorder()
	corsHandler.ServeHTTP(w1, req1)

	if w1.Header().Get("Access-Control-Allow-Origin") != "http://localhost:3000" {
		t.Errorf("expected Access-Control-Allow-Origin: http://localhost:3000, got: %s", w1.Header().Get("Access-Control-Allow-Origin"))
	}

	// 2. Disallowed external/attacker origin -> No Allow-Origin header set!
	req2 := httptest.NewRequest("GET", "/api/v1/health", nil)
	req2.Header.Set("Origin", "http://attacker.com")
	w2 := httptest.NewRecorder()
	corsHandler.ServeHTTP(w2, req2)

	if w2.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Errorf("expected no Access-Control-Allow-Origin header for attacker.com, got: %s", w2.Header().Get("Access-Control-Allow-Origin"))
	}
}

func TestServer_AuthenticatedEndpoints(t *testing.T) {
	srv, adminToken, _, _ := createTestServerAndTokens(t)

	// Blueprints list
	req := httptest.NewRequest("GET", "/api/v1/blueprints", nil)
	req.Header.Set("Authorization", "Bearer "+adminToken)
	w := httptest.NewRecorder()

	srv.withAuth(auth.PermViewReports, http.HandlerFunc(srv.handleBlueprints)).ServeHTTP(w, req)
	if w.Result().StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", w.Result().StatusCode)
	}

	// Autonomous test exploration
	testPayload := []byte(`{"target_url":"http://localhost:3000","goal":"smoke","max_steps":2}`)
	reqTest := httptest.NewRequest("POST", "/api/v1/test/run", bytes.NewBuffer(testPayload))
	reqTest.Header.Set("Authorization", "Bearer "+adminToken)
	wTest := httptest.NewRecorder()

	srv.withAuth(auth.PermExecuteWorkflows, http.HandlerFunc(srv.handleRunTest)).ServeHTTP(wTest, reqTest)
	if wTest.Result().StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", wTest.Result().StatusCode)
	}

	// Security fuzz
	fuzzPayload := []byte(`{"target_url":"http://localhost:3000"}`)
	reqFuzz := httptest.NewRequest("POST", "/api/v1/fuzz/run", bytes.NewBuffer(fuzzPayload))
	reqFuzz.Header.Set("Authorization", "Bearer "+adminToken)
	wFuzz := httptest.NewRecorder()

	srv.withAuth(auth.PermExecuteWorkflows, http.HandlerFunc(srv.handleRunFuzz)).ServeHTTP(wFuzz, reqFuzz)
	if wFuzz.Result().StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", wFuzz.Result().StatusCode)
	}

	// Perf run
	perfPayload := []byte(`{"target_url":"http://localhost:3000"}`)
	reqPerf := httptest.NewRequest("POST", "/api/v1/perf/run", bytes.NewBuffer(perfPayload))
	reqPerf.Header.Set("Authorization", "Bearer "+adminToken)
	wPerf := httptest.NewRecorder()

	srv.withAuth(auth.PermExecuteWorkflows, http.HandlerFunc(srv.handleRunPerf)).ServeHTTP(wPerf, reqPerf)
	if wPerf.Result().StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", wPerf.Result().StatusCode)
	}
}

func TestServerLifecycle(t *testing.T) {
	srv, _, _, _ := createTestServerAndTokens(t)
	srv.cfg.Port = 19093

	if err := srv.Start(); err != nil {
		t.Fatalf("failed to start server: %v", err)
	}

	time.Sleep(50 * time.Millisecond)

	// Verify root page loads embedded index.html on 127.0.0.1
	resp, err := http.Get("http://127.0.0.1:19093/")
	if err != nil {
		t.Fatalf("failed to fetch root page: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 OK for embedded index.html, got %d", resp.StatusCode)
	}

	// Verify health endpoint on 127.0.0.1
	healthResp, err := http.Get("http://127.0.0.1:19093/api/v1/health")
	if err != nil {
		t.Fatalf("failed to fetch health: %v", err)
	}
	defer healthResp.Body.Close()
	if healthResp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 OK for health, got %d", healthResp.StatusCode)
	}

	// Shutdown
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := srv.Stop(ctx); err != nil {
		t.Errorf("failed to cleanly stop server: %v", err)
	}
}
