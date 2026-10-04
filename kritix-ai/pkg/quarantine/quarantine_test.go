package quarantine

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

func TestFlakeEvaluator(t *testing.T) {
	evaluator := NewFlakeEvaluator(3, 1*time.Millisecond)
	ctx := context.Background()

	// 1. Stable Pass Test
	stableRes := evaluator.EvaluateTest(ctx, "test_login_success", func(attempt int) error {
		return nil
	})
	if stableRes.Classification != ClassificationStablePass || stableRes.ShouldQuarantine {
		t.Errorf("expected stable pass, got %s", stableRes.Classification)
	}

	// 2. True Regression Test (100% fail)
	regressionRes := evaluator.EvaluateTest(ctx, "test_order_broken", func(attempt int) error {
		return errors.New("HTTP 500: Database constraint violation")
	})
	if regressionRes.Classification != ClassificationTrueRegression || regressionRes.ShouldQuarantine {
		t.Errorf("expected true regression, got %s", regressionRes.Classification)
	}

	// 3. Flaky Test (fails attempt 1, passes attempt 2 and 3)
	flakyRes := evaluator.EvaluateTest(ctx, "test_network_jitter", func(attempt int) error {
		if attempt == 1 {
			return errors.New("timeout waiting for animation")
		}
		return nil
	})
	if flakyRes.Classification != ClassificationFlaky || !flakyRes.ShouldQuarantine {
		t.Errorf("expected flaky classification with quarantine recommendation, got %s", flakyRes.Classification)
	}

	// 4. Test Quarantine Registry with SLA & Tech Debt
	registry := NewQuarantineRegistry()
	registry.AddQuarantineWithSLA(flakyRes, "checkout-squad", "JIRA-7890", 50*time.Millisecond)

	if !registry.IsQuarantined("test_network_jitter") {
		t.Errorf("expected test to be marked as quarantined")
	}

	// Build should NOT fail initially (SLA active)
	fail, reason := registry.ShouldFailBuild("test_network_jitter")
	if fail {
		t.Errorf("expected build to NOT fail while SLA is valid")
	}
	if !strings.Contains(reason, "checkout-squad") {
		t.Errorf("expected squad attribution in reason")
	}

	// Wait for SLA to expire
	time.Sleep(60 * time.Millisecond)

	// Now build MUST fail to prevent permanent quarantine debt!
	fail, reason = registry.ShouldFailBuild("test_network_jitter")
	if !fail {
		t.Errorf("expected build to fail when quarantine SLA is exceeded")
	}
	if !strings.Contains(reason, "QUARANTINE SLA EXCEEDED") {
		t.Errorf("expected SLA exceeded message, got: %s", reason)
	}

	exceeded := registry.GetExceededSLAs()
	if len(exceeded) != 1 {
		t.Errorf("expected 1 exceeded SLA, got %d", len(exceeded))
	}
}

func TestQuarantinePurgeAndLeadershipScorecard(t *testing.T) {
	registry := NewQuarantineRegistry()

	// 1. Add fresh flaky test (5 days old)
	registry.AddQuarantineWithSLA(FlakeAnalysis{
		TestID:    "test_fresh_flaky",
		FlakeRate: 0.60,
	}, "payments-team", "PAY-101", 14*24*time.Hour)

	// 2. Add rotted test (35 days old, exceeds 30-day hard limit)
	rottedItem := QuarantinedItem{
		TestID: "test_rotted_checkout",
		Analysis: FlakeAnalysis{
			TestID:    "test_rotted_checkout",
			FlakeRate: 0.80,
		},
		QuarantinedAt:    time.Now().Add(-35 * 24 * time.Hour), // 35 days ago
		SLA:              14 * 24 * time.Hour,
		AssignedSquad:    "checkout-team",
		TechDebtTicketID: "CHK-999",
	}
	registry.quarantined[rottedItem.TestID] = rottedItem

	// 3. Generate leadership scorecard
	card := registry.GenerateLeadershipScorecard()
	if card.TotalQuarantined != 2 {
		t.Errorf("expected 2 quarantined tests in scorecard, got %d", card.TotalQuarantined)
	}
	if card.ExceededSLACount != 1 {
		t.Errorf("expected 1 test exceeding 14d SLA, got %d", card.ExceededSLACount)
	}
	if card.PendingPurgeCount != 1 {
		t.Errorf("expected 1 test pending auto-deletion (>20d), got %d", card.PendingPurgeCount)
	}
	if len(card.HighFlakeTests) != 2 {
		t.Errorf("expected 2 severe flaky tests (>=50%%), got %d", len(card.HighFlakeTests))
	}

	scorecardMD := card.FormatMarkdownScorecard()
	if !strings.Contains(scorecardMD, "Weekly Engineering Flakiness") || !strings.Contains(scorecardMD, "checkout-team") {
		t.Errorf("invalid markdown scorecard: %s", scorecardMD)
	}

	// 4. Run slow weekly cadence
	ranTests := make(map[string]bool)
	cadenceResults := registry.RunSlowWeeklyCadence(context.Background(), func(testID string) error {
		ranTests[testID] = true
		return nil
	})
	if len(cadenceResults) != 2 || !ranTests["test_fresh_flaky"] || !ranTests["test_rotted_checkout"] {
		t.Errorf("expected all quarantined tests to run in slow cadence: %+v", cadenceResults)
	}

	// 5. Purge expired tests (>30 days) -> rotted test deleted!
	purged := registry.PurgeExpiredTests(30 * 24 * time.Hour)
	if len(purged) != 1 || purged[0].TestID != "test_rotted_checkout" {
		t.Fatalf("expected rotted test to be purged, got: %+v", purged)
	}

	// Verify rotted test no longer in registry
	if registry.IsQuarantined("test_rotted_checkout") {
		t.Errorf("rotted test should have been permanently deleted from quarantine registry")
	}

	// Fresh test remains
	if !registry.IsQuarantined("test_fresh_flaky") {
		t.Errorf("fresh test should remain in quarantine")
	}
}
