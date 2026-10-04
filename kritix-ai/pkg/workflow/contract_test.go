package workflow_test

import (
	"context"
	"os"
	"testing"
	"time"

	"kritix/pkg/perf"
	"kritix/pkg/spec"
	"kritix/pkg/tracker"
	"kritix/pkg/triage"
	"kritix/pkg/workflow"
)

// TestBlockContract_EmptyEnvironment verifies that no registered block returns
// a simulated/canned StatusPassed when invoked with a nil/empty context.
func TestBlockContract_EmptyEnvironment(t *testing.T) {
	blueprints := workflow.ListBlueprints()
	testedBlocks := make(map[string]bool)
	var violations []string

	for _, bpDesc := range blueprints {
		bp, ok := workflow.GetBlueprint(bpDesc.ID)
		if !ok {
			t.Fatalf("blueprint %s not found in registry", bpDesc.ID)
		}
		dag, err := bp.BuildDAG()
		if err != nil {
			t.Fatalf("failed to build DAG for %s: %v", bpDesc.ID, err)
		}

		for _, node := range dag.Nodes {
			blk := node.Block
			if blk == nil {
				continue
			}
			desc := blk.Descriptor()
			if testedBlocks[desc.ID] {
				continue
			}
			testedBlocks[desc.ID] = true

			t.Run(desc.ID, func(t *testing.T) {
				ctx := context.Background()
				bCtx := workflow.NewContext(nil)

				res, err := blk.Execute(ctx, bCtx)
				if err == nil && res != nil && (res.Status == workflow.StatusPassed || res.Status == workflow.StatusSuccess) {
					violations = append(violations, desc.ID)
					if os.Getenv("KRITIX_PHASE0_BASELINE") != "skip_error" {
						t.Errorf("contract violation: block %q (%s) returned PASSED with empty environment; expected StatusFailed, StatusSkipped, or missing input error",
							desc.ID, desc.Name)
					}
				}
			})
		}
	}

	if len(violations) > 0 {
		t.Logf("Truth Audit Notice: %d block(s) currently return fake PASSED on empty env: %v", len(violations), violations)
	}
}

// TestBlockContract_SideEffectHonesty verifies that blocks with external side effects
// (such as sync.jira or perf.k6-spike) report StatusSimulated when unconfigured or when binaries are absent,
// and never report StatusPassed for unperformed side effects.
func TestBlockContract_SideEffectHonesty(t *testing.T) {
	ctx := context.Background()

	// 1. sync.jira with ticket_id but no tracker credentials
	jiraBlock := &workflow.SyncJiraBlock{}
	bCtx := workflow.NewContext(map[string]interface{}{
		"ticket_id": "TICKET-123",
	})
	res, err := jiraBlock.Execute(ctx, bCtx)
	if err != nil {
		t.Fatalf("expected nil error on simulated sync.jira execution, got: %v", err)
	}
	if res.Status == workflow.StatusPassed {
		t.Errorf("contract violation: sync.jira returned StatusPassed without configured tracker credentials")
	}
	if res.Status != workflow.StatusSimulated || !res.Simulated {
		t.Errorf("expected StatusSimulated with Simulated=true, got status=%s, simulated=%v", res.Status, res.Simulated)
	}

	// 2. sync.jira with real tracker client
	mockTracker := &fakeTracker{}
	bCtxWithTracker := workflow.NewContext(map[string]interface{}{
		"ticket_id":      "TICKET-123",
		"tracker_client": mockTracker,
	})
	bCtxWithTracker.AddFailure("Crash on step 2")
	resWithTracker, err := jiraBlock.Execute(ctx, bCtxWithTracker)
	if err != nil {
		t.Fatalf("sync.jira with tracker returned unexpected error: %v", err)
	}
	if resWithTracker.Status != workflow.StatusPassed {
		t.Errorf("expected StatusPassed when defect is logged via tracker, got: %s", resWithTracker.Status)
	}
	if !mockTracker.called {
		t.Errorf("expected tracker.CreateIssue to have been called")
	}

	// 3. perf.k6-spike without k6 binary / mock
	k6Block := &workflow.PerfK6SpikeBlock{}
	k6Ctx := workflow.NewContext(map[string]interface{}{
		"target_url": "http://localhost:3000",
	})
	k6Res, err := k6Block.Execute(ctx, k6Ctx)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !perf.IsK6Installed() {
		if k6Res.Status == workflow.StatusPassed {
			t.Errorf("contract violation: perf.k6-spike returned StatusPassed while k6 binary is not installed")
		}
		if k6Res.Status != workflow.StatusSimulated || !k6Res.Simulated {
			t.Errorf("expected StatusSimulated for uninstalled k6, got status=%s, simulated=%v", k6Res.Status, k6Res.Simulated)
		}
	}
}

type fakeTracker struct {
	called bool
}

func (f *fakeTracker) CreateIssue(ctx context.Context, report triage.DefectReport) (*tracker.IssueResult, error) {
	f.called = true
	return &tracker.IssueResult{
		Tracker:   tracker.TrackerLocal,
		IssueID:   "DEFECT-" + report.ID,
		IssueURL:  "http://tracker.local/DEFECT-" + report.ID,
		Title:     report.Title,
		CreatedAt: time.Now(),
	}, nil
}

func (f *fakeTracker) IngestStory(ctx context.Context, ticketID string) (*spec.Story, error) {
	return &spec.Story{ID: ticketID, Title: "Story"}, nil
}
