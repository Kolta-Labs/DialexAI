package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestWebUIServing(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/")
	if err != nil {
		t.Fatalf("GET / failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	contentType := resp.Header.Get("Content-Type")
	if !strings.Contains(contentType, "text/html") {
		t.Fatalf("expected Content-Type text/html, got %s", contentType)
	}

	body, _ := io.ReadAll(resp.Body)
	if !strings.Contains(string(body), "Dialex Server") {
		t.Fatalf("expected body to contain 'Dialex Server', got %s", string(body)[:200])
	}
}

func TestAdminStats(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	resp, err := http.Get(srv.URL + "/api/v1/admin/stats")
	if err != nil {
		t.Fatalf("GET /api/v1/admin/stats failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var stats adminStatsResponse
	if err := json.NewDecoder(resp.Body).Decode(&stats); err != nil {
		t.Fatalf("failed to decode stats: %v", err)
	}

	if stats.Status != "healthy" {
		t.Fatalf("expected status 'healthy', got %q", stats.Status)
	}
	if stats.Version != "1.0.0" {
		t.Fatalf("expected version '1.0.0', got %q", stats.Version)
	}
}

func TestAdminPasswordChange(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, st)

	// 1. Wrong current password should fail
	reqBody := changePasswordRequest{
		CurrentPassword: "wrongpassword",
		NewPassword:     "newsecret123",
	}
	req, _ := http.NewRequest("POST", srv.URL+"/api/v1/admin/password", mustJSON(t, reqBody))
	req.Header.Set("Authorization", "Bearer "+token)

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("password change request failed: %v", err)
	}
	resp.Body.Close()

	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("expected 401 for wrong password, got %d", resp.StatusCode)
	}

	// 2. Correct current password should succeed
	reqBody.CurrentPassword = "hunter22"
	req, _ = http.NewRequest("POST", srv.URL+"/api/v1/admin/password", mustJSON(t, reqBody))
	req.Header.Set("Authorization", "Bearer "+token)

	resp, err = client.Do(req)
	if err != nil {
		t.Fatalf("password change request failed: %v", err)
	}
	resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	// 3. Login with new password should now succeed
	loginResp, err := http.Post(srv.URL+"/auth/login", "application/json", mustJSON(t, loginRequest{
		Username: "admin",
		Password: "newsecret123",
	}))
	if err != nil {
		t.Fatalf("login failed: %v", err)
	}
	loginResp.Body.Close()

	if loginResp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK with new password, got %d", loginResp.StatusCode)
	}
}

func TestAdminPairingEndpoint(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, st)

	req, _ := http.NewRequest("GET", srv.URL+"/api/v1/admin/pairing", nil)
	req.Header.Set("Authorization", "Bearer "+token)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("pairing request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var pairing pairingResponse
	if err := json.NewDecoder(resp.Body).Decode(&pairing); err != nil {
		t.Fatalf("failed to decode pairing: %v", err)
	}

	if !strings.HasPrefix(pairing.PairingPayload, "dialex://pair?") {
		t.Fatalf("expected pairingPayload to start with dialex://pair?, got %q", pairing.PairingPayload)
	}
	if pairing.Username != "admin" {
		t.Fatalf("expected username 'admin', got %q", pairing.Username)
	}
}

func TestAdminLogsAndUserManagement(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()

	token := loginAndGetToken(t, srv, s, st)

	// Append log directly
	GlobalLogBuffer.AppendDirect("INFO", "Test log message for unit test")

	req, _ := http.NewRequest("GET", srv.URL+"/api/v1/admin/logs", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("logs request failed: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	// Create user
	createReq, _ := http.NewRequest("POST", srv.URL+"/api/v1/admin/users", mustJSON(t, createUserRequest{
		Username: "teammate",
		Password: "password123",
	}))
	createReq.Header.Set("Authorization", "Bearer "+token)
	cResp, err := http.DefaultClient.Do(createReq)
	if err != nil {
		t.Fatalf("create user failed: %v", err)
	}
	cResp.Body.Close()
	if cResp.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201 Created, got %d", cResp.StatusCode)
	}

	// List users
	listReq, _ := http.NewRequest("GET", srv.URL+"/api/v1/admin/users", nil)
	listReq.Header.Set("Authorization", "Bearer "+token)
	lResp, err := http.DefaultClient.Do(listReq)
	if err != nil {
		t.Fatalf("list users failed: %v", err)
	}
	defer lResp.Body.Close()

	var users []map[string]string
	json.NewDecoder(lResp.Body).Decode(&users)
	found := false
	for _, u := range users {
		if u["username"] == "teammate" {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("expected teammate in user list, got %+v", users)
	}
}

func TestUnauthenticatedStatsAreLivenessOnly(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)

	var anon, authed adminStatsResponse
	resp, _ := http.Get(srv.URL + "/api/v1/admin/stats")
	json.NewDecoder(resp.Body).Decode(&anon)
	resp.Body.Close()
	if anon.Status != "healthy" || anon.GoVersion != "" || anon.OS != "" || anon.Goroutines != 0 || anon.MemoryAllocMB != 0 {
		t.Fatalf("anonymous stats leak runtime details: %+v", anon)
	}

	resp2, _ := http.DefaultClient.Do(authedRequest(t, "GET", srv.URL+"/api/v1/admin/stats", token, nil))
	json.NewDecoder(resp2.Body).Decode(&authed)
	resp2.Body.Close()
	if authed.GoVersion == "" || authed.Goroutines == 0 {
		t.Fatalf("signed-in stats should be complete: %+v", authed)
	}
}

func TestCliProbesRequireAuth(t *testing.T) {
	s, _ := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	for _, path := range []string{"/api/v1/cli/status", "/api/v1/cli/logins"} {
		resp, _ := http.Get(srv.URL + path)
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("%s without a token = %d, want 401", path, resp.StatusCode)
		}
		resp.Body.Close()
	}
}

func TestTokenInQueryOnlyAllowedOnStreams(t *testing.T) {
	s, st := newTestServer(t)
	srv := httptest.NewServer(s.Router())
	defer srv.Close()
	token := loginAndGetToken(t, srv, s, st)

	resp, _ := http.Get(srv.URL + "/debates?token=" + token)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("?token= on a normal route = %d, want 401", resp.StatusCode)
	}
	resp.Body.Close()

	// a stream route accepts it (404 because the debate does not exist, but it got past auth)
	resp2, _ := http.Get(srv.URL + "/debates/nope/stream?token=" + token)
	if resp2.StatusCode == http.StatusUnauthorized {
		t.Errorf("?token= on /stream must authenticate, got 401")
	}
	resp2.Body.Close()
}
