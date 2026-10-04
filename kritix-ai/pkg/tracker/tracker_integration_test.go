//go:build integration

package tracker

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"kritix/pkg/triage"
)

// TestIntegration_TrackerMockProtocolAndRateLimiting tests Jira/Linear protocol fidelity:
// 1. Local mock server simulating Jira REST API.
// 2. Retry and backoff on HTTP 429 Too Many Requests.
// 3. Idempotent defect deduplication: filing same defect twice returns existing ticket ID.
// 4. Rate-limit and retry invariants.
func TestIntegration_TrackerMockProtocolAndRateLimiting(t *testing.T) {
	var requestCount int32
	var rateLimitHits int32
	var mu sync.Mutex
	createdIssues := make(map[string]string) // fingerprint -> issueKey

	mockJira := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		reqNum := atomic.AddInt32(&requestCount, 1)

		// 1. Simulate rate limiting on first attempt
		if reqNum == 1 {
			atomic.AddInt32(&rateLimitHits, 1)
			w.Header().Set("Retry-After", "1")
			w.WriteHeader(http.StatusTooManyRequests)
			_, _ = w.Write([]byte(`{"errorMessages": ["Rate limit exceeded"]}`))
			return
		}

		// Auth check
		authH := r.Header.Get("Authorization")
		if authH == "" || !strings.HasPrefix(authH, "Basic ") {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		// Search endpoint for fingerprint label
		if r.Method == http.MethodGet && strings.HasPrefix(r.URL.Path, "/rest/api/2/search") {
			jql := r.URL.Query().Get("jql")
			mu.Lock()
			defer mu.Unlock()

			var matchedKey string
			for fp, key := range createdIssues {
				if strings.Contains(jql, "fingerprint-"+fp) {
					matchedKey = key
					break
				}
			}

			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusOK)
			if matchedKey != "" {
				_ = json.NewEncoder(w).Encode(map[string]interface{}{
					"issues": []map[string]string{{"key": matchedKey}},
				})
			} else {
				_ = json.NewEncoder(w).Encode(map[string]interface{}{
					"issues": []map[string]string{},
				})
			}
			return
		}

		// Issue creation endpoint
		if r.Method == http.MethodPost && r.URL.Path == "/rest/api/2/issue" {
			var payload struct {
				Fields struct {
					Labels []string `json:"labels"`
				} `json:"fields"`
			}
			_ = json.NewDecoder(r.Body).Decode(&payload)

			var fp string
			for _, l := range payload.Fields.Labels {
				if strings.HasPrefix(l, "fingerprint-") {
					fp = strings.TrimPrefix(l, "fingerprint-")
					break
				}
			}

			mu.Lock()
			defer mu.Unlock()
			newKey := fmtIssueKey(len(createdIssues) + 1)
			if fp != "" {
				createdIssues[fp] = newKey
			}

			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(map[string]string{
				"id":  "1000" + newKey,
				"key": newKey,
			})
			return
		}

		// Comment endpoint
		if r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/comment") {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(map[string]string{"id": "comment-001"})
			return
		}

		w.WriteHeader(http.StatusOK)
	}))
	defer mockJira.Close()

	cfg := JiraConfig{
		BaseURL:    mockJira.URL,
		UserEmail:  "qa-bot@enterprise.internal",
		APIToken:   "secret-api-token-xyz",
		ProjectKey: "KRITIX",
		IssueType:  "Bug",
	}

	tracker := NewJiraTracker(cfg, mockJira.Client())
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	defect := triage.DefectReport{
		Title:            "Checkout button unresponsive",
		Category:         triage.CategoryCodeRegression,
		ActualBehavior:   "Click on checkout does not advance to payment",
		ExpectedBehavior: "Click opens stripe elements",
		StepsToReproduce: []string{"Open cart", "Click checkout"},
	}

	// 1. First filing: triggers HTTP 429, backs off, retries, creates issue
	res1, err := tracker.CreateIssue(ctx, defect)
	if err != nil {
		t.Fatalf("First CreateIssue failed: %v", err)
	}
	if res1.IssueID != "KRITIX-1" {
		t.Fatalf("Expected issue key KRITIX-1, got: %s", res1.IssueID)
	}
	if atomic.LoadInt32(&rateLimitHits) != 1 {
		t.Fatalf("Expected 1 rate-limit 429 retry hit, got: %d", rateLimitHits)
	}

	// 2. Second filing with identical defect: must be idempotent and return existing KRITIX-1
	res2, err := tracker.CreateIssue(ctx, defect)
	if err != nil {
		t.Fatalf("Second CreateIssue failed: %v", err)
	}
	if res2.IssueID != res1.IssueID {
		t.Fatalf("IDEMPOTENCY VIOLATION: Duplicate defect created new ticket %s instead of reusing %s", res2.IssueID, res1.IssueID)
	}
}

func fmtIssueKey(n int) string {
	return "KRITIX-" + string(rune('0'+n))
}
