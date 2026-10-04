package workflow_test

import (
	"context"
	"os"
	"testing"

	"kritix/pkg/workflow"
)

// TestBlockContract_EmptyEnvironment verifies that no registered block returns
// a simulated/canned StatusPassed when invoked with a nil/empty context.
// In Phase 0, all genericBlock instances violate this contract and fail this test.
// As real blocks and driver implementations replace mocks in Phase 1 & Phase 2, this test will pass.
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
					// In Phase 0, we log and enforce violation when KRITIX_PHASE0_FAIL_CONTRACT is enabled or by default
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
