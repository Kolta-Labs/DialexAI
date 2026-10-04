package server

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestServerHealth(t *testing.T) {
	srv := NewServer(0)

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

func TestServerBlueprints(t *testing.T) {
	srv := NewServer(0)

	req := httptest.NewRequest("GET", "/api/v1/blueprints", nil)
	w := httptest.NewRecorder()

	srv.handleBlueprints(w, req)

	resp := w.Result()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var body map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	count, ok := body["count"].(float64)
	if !ok || count <= 0 {
		t.Errorf("expected count > 0, got %v", body["count"])
	}
}

func TestServerRunTest(t *testing.T) {
	srv := NewServer(0)

	payload := []byte(`{"target_url":"http://localhost:3000","goal":"smoke","max_steps":2}`)
	req := httptest.NewRequest("POST", "/api/v1/test/run", bytes.NewBuffer(payload))
	w := httptest.NewRecorder()

	srv.handleRunTest(w, req)

	resp := w.Result()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var body map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	if body["status"] != "completed" {
		t.Errorf("expected status 'completed', got %v", body["status"])
	}
}

func TestServerFuzz(t *testing.T) {
	srv := NewServer(0)

	payload := []byte(`{"target_url":"http://localhost:3000"}`)
	req := httptest.NewRequest("POST", "/api/v1/fuzz/run", bytes.NewBuffer(payload))
	w := httptest.NewRecorder()

	srv.handleRunFuzz(w, req)

	resp := w.Result()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var body map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	findings, ok := body["findings"].([]any)
	if !ok || len(findings) == 0 {
		t.Errorf("expected findings in security response, got %v", body["findings"])
	}
}

func TestServerPerf(t *testing.T) {
	srv := NewServer(0)

	payload := []byte(`{"target_url":"http://localhost:3000"}`)
	req := httptest.NewRequest("POST", "/api/v1/perf/run", bytes.NewBuffer(payload))
	w := httptest.NewRecorder()

	srv.handleRunPerf(w, req)

	resp := w.Result()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200 OK, got %d", resp.StatusCode)
	}

	var body map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("failed to decode response: %v", err)
	}

	script, ok := body["script"].(string)
	if !ok || len(script) == 0 {
		t.Errorf("expected k6 script in response, got %v", body["script"])
	}
}

func TestServerStudioLifecycle(t *testing.T) {
	srv := NewServer(0)

	// 1. Record an action
	actionPayload := []byte(`{
		"type": "click",
		"target_id": "#submit-btn",
		"step_intent": "User clicks submit to complete order",
		"expected_outcome": "Order confirmation modal appears"
	}`)
	req := httptest.NewRequest("POST", "/api/v1/studio/action", bytes.NewBuffer(actionPayload))
	w := httptest.NewRecorder()
	srv.handleStudioAction(w, req)

	if w.Result().StatusCode != http.StatusOK {
		t.Fatalf("failed to record action: %d", w.Result().StatusCode)
	}

	// 2. Synthesize specs
	synthReq := httptest.NewRequest("POST", "/api/v1/studio/synthesize", nil)
	synthW := httptest.NewRecorder()
	srv.handleStudioSynthesize(synthW, synthReq)

	if synthW.Result().StatusCode != http.StatusOK {
		t.Fatalf("failed to synthesize: %d", synthW.Result().StatusCode)
	}

	var synthBody map[string]any
	if err := json.NewDecoder(synthW.Result().Body).Decode(&synthBody); err != nil {
		t.Fatalf("failed to decode synth response: %v", err)
	}

	playwright, ok := synthBody["playwright_code"].(string)
	if !ok || len(playwright) == 0 {
		t.Errorf("expected synthesized playwright code, got empty")
	}
}

func TestServerLifecycle(t *testing.T) {
	// Pick an available port
	srv := NewServer(19092)
	if err := srv.Start(); err != nil {
		t.Fatalf("failed to start server: %v", err)
	}

	time.Sleep(50 * time.Millisecond)

	// Verify root page loads embedded index.html
	resp, err := http.Get("http://localhost:19092/")
	if err != nil {
		t.Fatalf("failed to fetch root page: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		t.Errorf("expected 200 OK for embedded index.html, got %d", resp.StatusCode)
	}

	// Shutdown
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := srv.Stop(ctx); err != nil {
		t.Errorf("failed to cleanly stop server: %v", err)
	}
}
