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

func TestLinearTracker_CreateIssueAndIngest(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "lin_api_test_123" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		var req struct {
			Query string `json:"query"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)

		if req.Query != "" && len(req.Query) > 0 {
			if req.Query[0:8] == "mutation" {
				resp := map[string]interface{}{
					"data": map[string]interface{}{
						"issueCreate": map[string]interface{}{
							"success": true,
							"issue": map[string]interface{}{
								"id":         "lin-uuid-1",
								"identifier": "ENG-42",
								"url":        "https://linear.app/team/issue/ENG-42",
								"title":      "[Kritix QA] UI Regression on Cart",
							},
						},
					},
				}
				_ = json.NewEncoder(w).Encode(resp)
				return
			} else {
				resp := map[string]interface{}{
					"data": map[string]interface{}{
						"issue": map[string]interface{}{
							"id":          "lin-uuid-1",
							"identifier":  "ENG-42",
							"title":       "Cart discount computation",
							"description": "Compute cart total with voucher",
							"priority":    2.0,
						},
					},
				}
				_ = json.NewEncoder(w).Encode(resp)
				return
			}
		}
	}))
	defer server.Close()

	tracker := NewLinearTracker(LinearConfig{
		APIKey:  "lin_api_test_123",
		TeamID:  "team-engineering",
		BaseURL: server.URL,
	}, server.Client())

	ctx := context.Background()

	// 1. Create defect
	report := triage.DefectReport{
		ID:               "1",
		Title:            "UI Regression on Cart",
		Category:         triage.CategoryCodeRegression,
		TargetURL:        "https://app.local/cart",
		ActualBehavior:   "Discount not applied",
		StepsToReproduce: []string{"Add voucher"},
		DiscoveredAt:     time.Now(),
	}

	res, err := tracker.CreateIssue(ctx, report)
	if err != nil {
		t.Fatalf("CreateIssue failed: %v", err)
	}
	if res.IssueID != "ENG-42" || res.Tracker != TrackerLinear {
		t.Errorf("unexpected issue creation result: %+v", res)
	}

	// 2. Ingest story
	story, err := tracker.IngestStory(ctx, "ENG-42")
	if err != nil {
		t.Fatalf("IngestStory failed: %v", err)
	}
	if story.ID != "ENG-42" || story.Priority != spec.PriorityHigh {
		t.Errorf("unexpected ingested story: %+v", story)
	}
}
