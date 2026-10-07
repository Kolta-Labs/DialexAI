package main

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestBuildDaemonServer_Success(t *testing.T) {
	tmpDir := t.TempDir()
	server, err := BuildDaemonServer(DaemonOptions{
		Addr:      ":9090",
		GHSecret:  "whsec_12345",
		GLToken:   "gl_tok_67890",
		WorkDir:   tmpDir,
		JobsToken: "jobs_auth_token",
	})
	if err != nil {
		t.Fatalf("expected successful BuildDaemonServer, got: %v", err)
	}
	if server == nil {
		t.Fatalf("expected server to be non-nil")
	}

	handler := server.Handler()

	// Verify healthz endpoint responds 200 OK
	req := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Errorf("expected 200 OK from /healthz, got %d", rec.Code)
	}
}

func TestBuildDaemonServer_FailsWithoutSecretInEnterpriseMode(t *testing.T) {
	t.Setenv("ARTIX_ENTERPRISE", "1")

	server, err := BuildDaemonServer(DaemonOptions{
		Addr:     ":9090",
		GHSecret: "",
		GLToken:  "",
	})
	if err == nil {
		t.Fatalf("expected error when building daemon server without secrets in enterprise mode, got nil")
	}
	if server != nil {
		t.Errorf("expected server to be nil on error")
	}
}

func TestDaemonServer_JobsRouteAuth(t *testing.T) {
	tmpDir := t.TempDir()
	server, err := BuildDaemonServer(DaemonOptions{
		Addr:      ":9090",
		GHSecret:  "whsec_12345",
		WorkDir:   tmpDir,
		JobsToken: "secret-jobs-token",
	})
	if err != nil {
		t.Fatalf("BuildDaemonServer failed: %v", err)
	}

	handler := server.Handler()

	// 1. Unauthenticated /jobs request -> 401
	unauthReq := httptest.NewRequest(http.MethodGet, "/jobs", nil)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, unauthReq)
	if rec.Code != http.StatusUnauthorized {
		t.Errorf("expected 401 Unauthorized for /jobs without token, got %d", rec.Code)
	}

	// 2. Authenticated with token and tenant -> 200
	authReq := httptest.NewRequest(http.MethodGet, "/jobs?tenant=default", nil)
	authReq.Header.Set("Authorization", "Bearer secret-jobs-token")
	recAuth := httptest.NewRecorder()
	handler.ServeHTTP(recAuth, authReq)
	if recAuth.Code != http.StatusOK {
		t.Errorf("expected 200 OK for authenticated /jobs request, got %d", recAuth.Code)
	}
}

func TestBuildDaemonServer_RefusesNonLoopbackWithoutAuth(t *testing.T) {
	// Ensure not in enterprise mode
	t.Setenv("ARTIX_ENTERPRISE", "")
	t.Setenv("KRITIX_ENTERPRISE", "")

	// 1. Non-loopback (:8080 or 0.0.0.0:8080) without webhook secret -> rejected
	_, err := BuildDaemonServer(DaemonOptions{
		Addr:      ":8080",
		GHSecret:  "",
		GLToken:   "",
		JobsToken: "some-jobs-token",
	})
	if err == nil {
		t.Errorf("expected non-loopback bind without webhook secret to be rejected")
	}

	// 2. Non-loopback without jobs token -> rejected
	_, err = BuildDaemonServer(DaemonOptions{
		Addr:      "0.0.0.0:8080",
		GHSecret:  "whsec_123",
		JobsToken: "",
	})
	if err == nil {
		t.Errorf("expected non-loopback bind without jobs token to be rejected")
	}

	// 3. Loopback without auth in non-enterprise dev mode -> permitted
	server, err := BuildDaemonServer(DaemonOptions{
		Addr:      "127.0.0.1:8080",
		GHSecret:  "",
		GLToken:   "",
		JobsToken: "",
	})
	if err != nil {
		t.Errorf("expected loopback bind in non-enterprise mode to succeed, got: %v", err)
	}
	if server == nil {
		t.Errorf("expected server to be non-nil")
	}
}

