package spec

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"kritix/pkg/model"
)

func TestCouncilClientWithMockSocratix(t *testing.T) {
	mockSocratix := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v1/deliberate" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{
			"consensus": "Require rate-limiting and duplicate idempotency tokens",
			"dissent": ["Edge case: concurrent requests from mobile and web", "Unchecked null postal codes"]
		}`))
	}))
	defer mockSocratix.Close()

	router := model.NewRouter(model.DefaultRouterConfig())
	client := NewCouncilClient(router, mockSocratix.URL)

	story := Story{
		Title:       "Checkout Payment Processing",
		Description: "Process payments via Stripe",
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	interrogation, err := client.InterrogateStory(ctx, story)
	if err != nil {
		t.Fatalf("interrogation failed: %v", err)
	}

	if interrogation.StoryTitle != "Checkout Payment Processing" {
		t.Errorf("expected title Checkout Payment Processing, got %s", interrogation.StoryTitle)
	}

	if len(interrogation.UncoveredGaps) != 2 {
		t.Errorf("expected 2 uncovered gaps from mock Socratix, got %d", len(interrogation.UncoveredGaps))
	}
}

func TestCouncilClientFallbackToHeuristic(t *testing.T) {
	// Point to unreachable Socratix and mock router model failure
	router := model.NewRouter(model.DefaultRouterConfig())
	client := NewCouncilClient(router, "http://127.0.0.1:59999") // closed port

	story := Story{
		Title:       "User Registration",
		Description: "New user sign up flow",
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	interrogation, err := client.InterrogateStory(ctx, story)
	if err != nil {
		t.Fatalf("expected graceful fallback, got error: %v", err)
	}

	if len(interrogation.UncoveredGaps) == 0 {
		t.Errorf("expected heuristic gaps to be generated")
	}
	if len(interrogation.RefinedCriteria) == 0 {
		t.Errorf("expected heuristic criteria to be generated")
	}
}
