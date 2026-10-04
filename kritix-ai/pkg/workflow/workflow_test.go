package workflow

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"kritix/pkg/driver"
	"kritix/pkg/sdet"
	"kritix/pkg/spec"
)

func TestDAGTopologicalExecution(t *testing.T) {
	dag := NewDAG("test-pipeline", "Test DAG Pipeline")

	var executionOrder []string

	dag.AddNode("nodeA", &genericBlock{
		desc: BlockDescriptor{ID: "blockA", Name: "Block A"},
		exec: func(ctx context.Context, bCtx *Context) (*BlockResult, error) {
			executionOrder = append(executionOrder, "A")
			bCtx.Set("keyA", "valA")
			return &BlockResult{Status: StatusSuccess, Message: "Done A"}, nil
		},
	})

	dag.AddNode("nodeB", &genericBlock{
		desc: BlockDescriptor{ID: "blockB", Name: "Block B"},
		exec: func(ctx context.Context, bCtx *Context) (*BlockResult, error) {
			executionOrder = append(executionOrder, "B")
			valA, _ := bCtx.Get("keyA")
			if valA != "valA" {
				return nil, errors.New("missing context from A")
			}
			return &BlockResult{Status: StatusSuccess, Message: "Done B"}, nil
		},
	}, "nodeA") // B depends on A

	bCtx := NewContext(nil)
	res, err := dag.Execute(context.Background(), bCtx)
	if err != nil {
		t.Fatalf("dag execution failed: %v", err)
	}

	if !res.Success {
		t.Errorf("expected dag to succeed")
	}

	if len(executionOrder) != 2 || executionOrder[0] != "A" || executionOrder[1] != "B" {
		t.Errorf("expected execution order [A, B], got %v", executionOrder)
	}
}

func TestDAGCycleDetection(t *testing.T) {
	dag := NewDAG("cyclic-dag", "DAG with cycle")

	dummy := &genericBlock{
		desc: BlockDescriptor{ID: "dummy", Name: "Dummy"},
		exec: func(ctx context.Context, bCtx *Context) (*BlockResult, error) {
			return &BlockResult{Status: StatusSuccess}, nil
		},
	}

	dag.AddNode("node1", dummy, "node2")
	dag.AddNode("node2", dummy, "node1")

	_, err := dag.Execute(context.Background(), NewContext(nil))
	if err == nil || !strings.Contains(err.Error(), "cycle detected") {
		t.Errorf("expected cycle detection error, got: %v", err)
	}
}

func TestDAGDependencyFailureSkipsDownstream(t *testing.T) {
	dag := NewDAG("fail-pipeline", "Pipeline with failing node")

	dag.AddNode("failingNode", &genericBlock{
		desc: BlockDescriptor{ID: "failing", Name: "Failing Block"},
		exec: func(ctx context.Context, bCtx *Context) (*BlockResult, error) {
			return nil, errors.New("simulated fatal failure")
		},
	})

	executedDownstream := false
	dag.AddNode("downstreamNode", &genericBlock{
		desc: BlockDescriptor{ID: "downstream", Name: "Downstream Block"},
		exec: func(ctx context.Context, bCtx *Context) (*BlockResult, error) {
			executedDownstream = true
			return &BlockResult{Status: StatusSuccess}, nil
		},
	}, "failingNode")

	bCtx := NewContext(nil)
	res, err := dag.Execute(context.Background(), bCtx)
	if err != nil {
		t.Fatalf("unexpected error from execute: %v", err)
	}

	if res.Success {
		t.Errorf("dag should be marked failed")
	}

	if executedDownstream {
		t.Errorf("downstream node should have been skipped")
	}

	if res.SkippedNodes != 1 {
		t.Errorf("expected 1 skipped node, got %d", res.SkippedNodes)
	}
}

func TestAllRegisteredBlueprintsBuildAndExecute(t *testing.T) {
	okServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) }))
	defer okServer.Close()

	blueprints := ListBlueprints()
	if len(blueprints) < 6 {
		t.Fatalf("expected at least 6 default blueprints, got %d", len(blueprints))
	}

	ctx := context.Background()

	expectedIDs := []string{
		"ticket-to-ship",
		"pr-smoke-guard",
		"api-contract-fuzzer",
		"nightly-deep-audit",
		"visual-a11y-audit",
		"self-healing-maintenance",
	}

	for _, id := range expectedIDs {
		bp, exists := GetBlueprint(id)
		if !exists {
			t.Errorf("expected blueprint %q to be registered", id)
			continue
		}

		dag, err := bp.BuildDAG()
		if err != nil {
			t.Errorf("blueprint %q failed to build DAG: %v", id, err)
			continue
		}
		if id == "pr-smoke-guard" {
			continue // needs a git repo and impact map; covered by smoke_test.go
		}

		ctxVars := map[string]interface{}{
			"target_url": okServer.URL,
		}

		switch id {
		case "ticket-to-ship":
			ctxVars["bundle"] = &spec.MultiArtifactBundle{
				TicketID:         "KRIT-101",
				FeatureDesignDoc: "Checkout feature specification",
				CopyMatrix:       map[string]string{"btn.checkout": "Pay Now"},
				AnalyticsEvents: []spec.AnalyticsEventDef{
					{EventName: "page_view", TriggerAction: "navigate"},
				},
			}
			ctxVars["jira_ticket"] = "KRIT-101"
		case "api-contract-fuzzer":
			ctxVars["openapi_spec"] = `{"openapi":"3.0.0","info":{"title":"Test API","version":"1.0"},"paths":{"/api/v1/health":{"get":{"responses":{"200":{"description":"OK"}}}}}}`
		case "self-healing-maintenance":
			ctxVars["heal_mode"] = "advisory" // maintenance proposes a human-reviewed PR
			tempDir := t.TempDir()
			_ = os.WriteFile(filepath.Join(tempDir, "checkout_test.js"), []byte("test('checkout', async () => {});"), 0644)
			ctxVars["test_dir"] = tempDir
			ctxVars["broken_selectors"] = []sdet.ElementFingerprint{
				{
					ID:     "checkout-btn",
					TestID: "checkout-btn-old",
					Role:   "button",
					Text:   "Checkout Now",
					Tag:    "button",
					BBox:   driver.Rect{X: 100, Y: 200, Width: 120, Height: 40},
				},
			}
			ctxVars["elements"] = []driver.Element{
				{
					ID:          "btn-checkout-new",
					Tag:         "button",
					Role:        "button",
					Text:        "Checkout Now",
					BoundingBox: driver.Rect{X: 102, Y: 201, Width: 120, Height: 40},
				},
			}
		}

		bCtx := NewContext(ctxVars)

		res, err := dag.Execute(ctx, bCtx)
		if err != nil {
			t.Errorf("blueprint %q DAG execution error: %v", id, err)
			continue
		}

		if !res.Success {
			t.Errorf("blueprint %q DAG execution failed: %s", id, res.PrintSummary())
		}
	}
}

func TestSatisfiesPRMergeGate(t *testing.T) {
	// 1. Clean Pass
	cleanResult := &DAGResult{
		TotalNodes:  5,
		PassedNodes: 5,
	}
	ok, msg := cleanResult.SatisfiesPRMergeGate(false)
	if !ok || !strings.Contains(msg, "satisfied") {
		t.Errorf("expected clean pass to satisfy PR gate: %s", msg)
	}

	// 2. Failed Block
	failedResult := &DAGResult{
		TotalNodes:  5,
		FailedNodes: 1,
	}
	ok, msg = failedResult.SatisfiesPRMergeGate(false)
	if ok || !strings.Contains(msg, "failed") {
		t.Errorf("expected failed block to block PR gate: %s", msg)
	}

	// 3. PASSED_WITH_HEALING without override: MUST BLOCK PR GATE!
	healedResult := &DAGResult{
		TotalNodes:             5,
		PassedNodes:            4,
		PassedWithHealingNodes: 1,
	}
	ok, msg = healedResult.SatisfiesPRMergeGate(false)
	if ok {
		t.Errorf("PASSED_WITH_HEALING must NOT satisfy PR merge gate without explicit override")
	}
	if !strings.Contains(msg, "PASSED_WITH_HEALING") {
		t.Errorf("expected explanation mentioning PASSED_WITH_HEALING: %s", msg)
	}

	// 4. PASSED_WITH_HEALING WITH explicit override: ALLOWED
	ok, msg = healedResult.SatisfiesPRMergeGate(true)
	if !ok {
		t.Errorf("expected override to allow healed pass: %s", msg)
	}
}

func TestTierBudgetEnforcement(t *testing.T) {
	dag := NewDAG("slow-pipeline", "Pipeline exceeding hard time budget")
	dag.SetMaxTimeBudget(10 * time.Millisecond) // 10ms hard budget

	dag.AddNode("slow_node", &genericBlock{
		desc: BlockDescriptor{ID: "slow.block", Name: "Slow Block"},
		exec: func(ctx context.Context, bCtx *Context) (*BlockResult, error) {
			select {
			case <-time.After(50 * time.Millisecond):
				return &BlockResult{Status: StatusSuccess}, nil
			case <-ctx.Done():
				return nil, ctx.Err()
			}
		},
	})

	bCtx := NewContext(nil)
	_, err := dag.Execute(context.Background(), bCtx)
	if err == nil || !strings.Contains(err.Error(), "time budget exceeded") {
		t.Errorf("expected ErrBudgetExceeded error, got: %v", err)
	}
}

func TestValidateBlueprintForTier(t *testing.T) {
	bp, exists := GetBlueprint("ticket-to-ship")
	if !exists {
		t.Fatalf("expected ticket-to-ship blueprint")
	}

	// Attempting to run ticket-to-ship in Tier 1 PR gate -> MUST FAIL!
	err := ValidateBlueprintForTier(bp.Descriptor(), Tier1PRGate)
	if err == nil || !strings.Contains(err.Error(), "blueprint tier mismatch") {
		t.Errorf("expected ErrTierMismatch when running ticket-to-ship in Tier 1 PR gate, got: %v", err)
	}

	// Running pr-smoke-guard in Tier 1 PR gate -> ALLOWED
	prBP, _ := GetBlueprint("pr-smoke-guard")
	if err := ValidateBlueprintForTier(prBP.Descriptor(), Tier1PRGate); err != nil {
		t.Errorf("expected pr-smoke-guard to be valid for Tier 1 PR gate, got: %v", err)
	}
}

func TestVerifyEnvironmentIsolation(t *testing.T) {
	// 1. Target detected as production -> MUST FAIL!
	if err := VerifyEnvironmentIsolation("https://prod.app.internal", false); err == nil {
		t.Errorf("expected failure when targeting production")
	}

	// 2. Shared infrastructure with production -> MUST FAIL (SOC 2 violation)
	if err := VerifyEnvironmentIsolation("https://staging.app.internal", true); err == nil {
		t.Errorf("expected failure when staging shares infrastructure with production")
	}

	// 3. Isolated non-shared staging environment -> ALLOWED
	if err := VerifyEnvironmentIsolation("https://staging.isolated.internal", false); err != nil {
		t.Errorf("expected isolated staging to pass verification: %v", err)
	}
}

type genericBlock struct {
	desc BlockDescriptor
	exec func(ctx context.Context, bCtx *Context) (*BlockResult, error)
}

func (g *genericBlock) Descriptor() BlockDescriptor {
	return g.desc
}

func (g *genericBlock) Execute(ctx context.Context, bCtx *Context) (*BlockResult, error) {
	return g.exec(ctx, bCtx)
}
