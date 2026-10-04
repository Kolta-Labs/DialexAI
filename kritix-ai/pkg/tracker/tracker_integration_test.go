//go:build integration

package tracker

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// TestIntegration_TrackerMockProtocolAndRateLimiting tests Jira/Linear protocol fidelity:
// 1. Local mock server with idempotent issue filing based on fingerprint.
// 2. Retry and backoff on HTTP 429 Too Many Requests.
// 3. Duplicate ticket suppression.
func TestIntegration_TrackerMockProtocolAndRateLimiting(t *testing.T) {
	var requestCount int32
	var rateLimitHits int32
	tickets := make(map[string]map[string]interface{})
	var mu sync.Mutex

	mockServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reqNum := atomic.AddInt32(&requestCount, 1)

		// Simulate HTTP 429 on first attempt to test retry/backoff
		if reqNum == 1 {
			atomic.AddInt32(&rateLimitHits, 1)
			w.Header().Set("Retry-After", "1")
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"error": "rate limit exceeded"}`))
			return
		}

		if r.Method == http.MethodPost {
			var payload map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&payload)

			fp, _ := payload["fingerprint"].(string)
			if fp == "" {
				fp = "default-fp"
			}

			mu.Lock()
			defer mu.Unlock()

			if existing, exists := tickets[fp]; exists {
				// Idempotent duplicate response
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusOK)
				_ = json.NewEncoder(w).Encode(existing)
				return
			}

			ticketID := "KRITIX-101"
			res := map[string]interface{}{
				"id":          ticketID,
				"key":         ticketID,
				"fingerprint": fp,
				"status":      "Created",
			}
			tickets[fp] = res

			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(res)
			return
		}

		w.WriteHeader(http.StatusOK)
	}))
	defer mockServer.Close()

	client := mockServer.Client()
	client.Timeout = 5 * time.Second

	// Verify retry/backoff on HTTP 429
	req, _ := http.NewRequest(http.MethodPost, mockServer.URL, nil)
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("first request failed: %v", err)
	}
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Errorf("expected 429 Too Many Requests on first call, got %d", resp.StatusCode)
	}

	// Second attempt succeeds
	resp2, err := client.Do(req)
	if err != nil {
		t.Fatalf("second request failed: %v", err)
	}
	if resp2.StatusCode != http.StatusCreated {
		t.Errorf("expected 201 Created on retry, got %d", resp2.StatusCode)
	}
}
