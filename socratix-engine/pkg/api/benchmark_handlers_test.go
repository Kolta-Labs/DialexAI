package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"dialex/pkg/benchmark"
)

func TestBenchmarkHandlers(t *testing.T) {
	s, _ := newTestServer(t)
	ts := httptest.NewServer(s.Router())
	defer ts.Close()

	token, err := s.issueToken("testuser")
	if err != nil {
		t.Fatalf("failed to issue token: %v", err)
	}

	// 1. GET /api/v1/benchmarks/cases
	req, _ := http.NewRequest("GET", ts.URL+"/api/v1/benchmarks/cases", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET /cases failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}

	var cases []benchmark.BenchmarkCase
	if err := json.NewDecoder(resp.Body).Decode(&cases); err != nil {
		t.Fatalf("failed to decode cases: %v", err)
	}
	resp.Body.Close()

	if len(cases) != 10 {
		t.Fatalf("expected 10 bundled cases, got %d", len(cases))
	}

	// 2. POST /api/v1/benchmarks/cases (Custom Case)
	customCase := benchmark.BenchmarkCase{
		Title:   "Custom Kafka Ingestion Pipeline",
		Domain:  "MESSAGING",
		Dilemma: "100k events/sec with ordering constraints",
	}
	body, _ := json.Marshal(customCase)
	req, _ = http.NewRequest("POST", ts.URL+"/api/v1/benchmarks/cases", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("POST /cases failed: %v", err)
	}
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("expected 201, got %d", resp.StatusCode)
	}
	resp.Body.Close()

	// 3. GET /api/v1/benchmarks/summary
	req, _ = http.NewRequest("GET", ts.URL+"/api/v1/benchmarks/summary", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET /summary failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	var summary benchmark.BenchmarkSummary
	_ = json.NewDecoder(resp.Body).Decode(&summary)
	resp.Body.Close()

	// 4. GET /api/v1/benchmarks/export (Markdown format)
	req, _ = http.NewRequest("GET", ts.URL+"/api/v1/benchmarks/export?format=markdown", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("GET /export failed: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("expected 200, got %d", resp.StatusCode)
	}
	resp.Body.Close()
}
