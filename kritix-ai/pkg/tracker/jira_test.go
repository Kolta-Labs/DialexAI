package tracker

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"kritix/pkg/spec"
	"kritix/pkg/triage"
)

func TestJiraTracker_CreateIssueAndIdempotency(t *testing.T) {
	searched := false
	created := false
	commented := false

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Verify auth header
		auth := r.Header.Get("Authorization")
		if auth == "" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/rest/api/2/search":
			searched = true
			// First time no issues, second time returns existing
			if created {
				resp := map[string]interface{}{
					"issues": []map[string]interface{}{
						{"key": "PROJ-101"},
					},
				}
				_ = json.NewEncoder(w).Encode(resp)
				return
			}
			_ = json.NewEncoder(w).Encode(map[string]interface{}{"issues": []interface{}{}})
		case r.Method == http.MethodPost && r.URL.Path == "/rest/api/2/issue":
			created = true
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"id":   "10001",
				"key":  "PROJ-101",
				"self": "https://test.atlassian.net/rest/api/2/issue/10001",
			})
		case r.Method == http.MethodPost && r.URL.Path == "/rest/api/2/issue/PROJ-101/comment":
			commented = true
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(map[string]interface{}{"id": "20001"})
		case r.Method == http.MethodGet && r.URL.Path == "/rest/api/2/issue/PROJ-101":
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"key": "PROJ-101",
				"fields": map[string]interface{}{
					"summary":     "User checkout flow story",
					"description": "As a user I want to checkout",
					"priority":    map[string]interface{}{"name": "High"},
				},
			})
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	cfg := JiraConfig{
		BaseURL:    server.URL,
		UserEmail:  "bot@company.com",
		APIToken:   "test-token",
		ProjectKey: "PROJ",
	}

	tracker := NewJiraTracker(cfg, server.Client())
	ctx := context.Background()

	report := triage.DefectReport{
		ID:               "DEF-1",
		Title:            "Checkout 500 error on payment",
		Category:         triage.CategoryCodeRegression,
		TargetURL:        "https://app.local/checkout",
		ActualBehavior:   "Server returned HTTP 500 Internal Server Error",
		StepsToReproduce: []string{"Add to cart", "Click Pay"},
		DiscoveredAt:     time.Now(),
	}

	// 1. Initial create
	res1, err := tracker.CreateIssue(ctx, report)
	if err != nil {
		t.Fatalf("first CreateIssue failed: %v", err)
	}
	if res1.IssueID != "PROJ-101" {
		t.Errorf("expected PROJ-101, got %s", res1.IssueID)
	}
	if !searched || !created {
		t.Errorf("expected search and create to have executed")
	}

	// 2. Second create with same defect fingerprint -> should comment/deduplicate
	res2, err := tracker.CreateIssue(ctx, report)
	if err != nil {
		t.Fatalf("second CreateIssue failed: %v", err)
	}
	if res2.IssueID != "PROJ-101" {
		t.Errorf("expected deduplicated PROJ-101, got %s", res2.IssueID)
	}
	if !commented {
		t.Errorf("expected comment on existing defect for idempotency")
	}

	// 3. Ingest story
	story, err := tracker.IngestStory(ctx, "PROJ-101")
	if err != nil {
		t.Fatalf("IngestStory failed: %v", err)
	}
	if story.ID != "PROJ-101" || story.Title != "User checkout flow story" || story.Priority != spec.PriorityHigh {
		t.Errorf("unexpected story ingest outcome: %+v", story)
	}
}

func TestJiraTracker_NegativeAuth(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()

	tracker := NewJiraTracker(JiraConfig{
		BaseURL:   server.URL,
		UserEmail: "invalid@company.com",
		APIToken:  "wrong",
	}, server.Client())

	_, err := tracker.CreateIssue(context.Background(), triage.DefectReport{Title: "Test"})
	if err != ErrJiraAuthFailed {
		t.Errorf("expected ErrJiraAuthFailed, got %v", err)
	}
}
