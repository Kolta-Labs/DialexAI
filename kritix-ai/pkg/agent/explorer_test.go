package agent

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"kritix/pkg/driver"
	"kritix/pkg/model"
)

func TestExplorerInvariantCrashDetection(t *testing.T) {
	vDriver := driver.NewVirtualDriver()
	vDriver.SetVirtualDOM([]driver.Element{
		{Tag: "button", Text: "Pay Now", Role: "button"},
	}, nil)

	router := model.NewRouter(model.DefaultRouterConfig())

	explorer := NewExplorer(router, vDriver, ExplorerConfig{
		TargetURL: "https://shop.example.com/pay",
		Goal:      "Verify payment flow handles edge cases",
		MaxSteps:  5,
		Timeout:   10 * time.Second,
		SessionID: "test-invariants",
	})

	// Inject a 500 server crash into the virtual driver on next action
	ctx := context.Background()
	_ = vDriver.Start(ctx)
	defer vDriver.Stop(ctx)

	// Simulate navigating then crashing on API
	_, _ = vDriver.Navigate(ctx, "https://shop.example.com/pay")
	// Execute action that logs a 500
	_, _ = vDriver.ExecuteAction(ctx, driver.Action{
		Type:       driver.ActionClick,
		TargetRole: "button",
		TargetText: "Pay Now",
		Timestamp:  time.Now(),
	})

	// Manually set network activity with 500
	state, _ := vDriver.GetState(ctx)
	state.NetworkActivity = append(state.NetworkActivity, driver.NetworkEvent{
		URL:        "https://shop.example.com/api/v1/charge",
		Method:     "POST",
		StatusCode: 500,
	})

	bug := explorer.inspectInvariants(state, []driver.Action{
		{Type: driver.ActionNavigate, Value: "https://shop.example.com/pay"},
		{Type: driver.ActionClick, TargetRole: "button", TargetText: "Pay Now"},
	})

	if bug == nil {
		t.Fatalf("expected invariant check to detect HTTP 500 defect")
	}

	if bug.Severity != "blocker" {
		t.Errorf("expected blocker severity, got %s", bug.Severity)
	}

	if !strings.Contains(bug.PlaywrightRepro, "await page.getByRole(\"button\", { name: \"Pay Now\" }).click();") {
		t.Errorf("missing click step in generated Playwright repro script: %s", bug.PlaywrightRepro)
	}
}

func TestExplorerDecisionLoopWithMockModel(t *testing.T) {
	// Create mock model server returning structured JSON action
	mockServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{
			"choices": [{
				"message": {
					"content": "{\"action_type\": \"click\", \"target_query\": \"Add to Cart\", \"reasoning\": \"Add product to cart\", \"is_goal_met\": false, \"bug_detected\": false}"
				}
			}],
			"usage": {"prompt_tokens": 50, "completion_tokens": 20}
		}`))
	}))
	defer mockServer.Close()

	router := model.NewRouter(model.RouterConfig{
		DefaultMode: model.ModeAPI,
		Stages: map[model.Stage]model.StageConfig{
			model.StageExplorer: {
				Stage:    model.StageExplorer,
				Mode:     model.ModeAPI,
				Provider: model.ProviderCustom,
				Model:    "test-explorer-vlm",
				Endpoint: mockServer.URL,
			},
		},
	})

	vDriver := driver.NewVirtualDriver()
	vDriver.SetVirtualDOM([]driver.Element{
		{Tag: "button", Text: "Add to Cart", Role: "button"},
	}, nil)

	explorer := NewExplorer(router, vDriver, ExplorerConfig{
		TargetURL: "https://store.example.com/item/42",
		Goal:      "Test Add to Cart button",
		MaxSteps:  2,
		Timeout:   5 * time.Second,
	})

	ctx := context.Background()
	trace, err := explorer.Run(ctx)
	if err != nil {
		t.Fatalf("explorer run failed: %v", err)
	}

	if trace.TotalSteps < 2 {
		t.Errorf("expected at least 2 steps (navigation + action), got %d", trace.TotalSteps)
	}

	lastAction := trace.Snapshots[len(trace.Snapshots)-1].Action
	if lastAction.Type != driver.ActionClick {
		t.Errorf("expected last action to be Click, got %s", lastAction.Type)
	}
}
