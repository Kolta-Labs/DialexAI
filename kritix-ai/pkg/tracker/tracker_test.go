package tracker

import (
	"context"
	"strings"
	"testing"
	"time"

	"kritix/pkg/triage"
)

func TestLocalFileTracker(t *testing.T) {
	tracker := NewLocalFileTracker("/tmp/kritix_tickets")

	report := triage.DefectReport{
		ID:               "101",
		Title:            "Null pointer on checkout",
		Severity:         triage.SeverityBlocker,
		TargetURL:        "https://example.com/checkout",
		StepsToReproduce: []string{"Open checkout", "Click Pay"},
		ExpectedBehavior: "Successful order",
		ActualBehavior:     "500 crash",
		AIDiagnosisComment: "Discount calculation does not guard against null coupon object when expired",
		ProposedFixDiff:    "- const val = coupon.discount;\n+ const val = coupon?.discount ?? 0;",
		DiscoveredAt:       time.Now(),
	}

	result, err := tracker.CreateIssue(context.Background(), report)
	if err != nil {
		t.Fatalf("failed to create issue: %v", err)
	}

	if result.IssueID != "DEFECT-101" {
		t.Errorf("expected DEFECT-101, got %s", result.IssueID)
	}

	markdown := FormatMarkdownIssue(report)
	if !strings.Contains(markdown, "Bug Report: [KRITIX-AI: UNREVIEWED] Null pointer on checkout") {
		t.Errorf("markdown missing title: %s", markdown)
	}
	if !strings.Contains(markdown, "**Status**: `[KRITIX-AI: UNREVIEWED]`") {
		t.Errorf("markdown missing unreviewed status tag")
	}
	if !strings.Contains(markdown, "AI Root Cause Diagnosis & Recommended Fix") {
		t.Errorf("markdown missing AI diagnosis title")
	}
	if !strings.Contains(markdown, "Proposed Code Diff (⚠️ DO NOT APPLY WITHOUT REVIEW)") {
		t.Errorf("markdown missing proposed diff title with warning")
	}
	if !strings.Contains(markdown, "Must be reviewed and verified by an SDET") {
		t.Errorf("markdown missing mandatory SDET review warning")
	}
}

func TestCorpusGovernanceRegistryLifecycle(t *testing.T) {
	registry := NewTestCorpusGovernanceRegistry()

	// Register 2 AI generated tests
	t1 := &AITestArtifact{
		TestID:      "test_checkout_01",
		FilePath:    "tests/e2e/checkout_01.spec.ts",
		GeneratedBy: "qwen2.5-coder-32b",
		CreatedAt:   time.Now().Add(-80 * time.Hour), // Older than 72h SLA
	}
	t2 := &AITestArtifact{
		TestID:      "test_login_02",
		FilePath:    "tests/e2e/login_02.spec.ts",
		GeneratedBy: "qwen2.5-coder-32b",
		CreatedAt:   time.Now().Add(-10 * time.Hour), // Fresh
	}

	if err := registry.RegisterAITest(t1); err != nil {
		t.Fatalf("failed to register t1: %v", err)
	}
	if err := registry.RegisterAITest(t2); err != nil {
		t.Fatalf("failed to register t2: %v", err)
	}

	// Verify both are unreviewed
	pending := registry.ListPendingReview()
	if len(pending) != 2 {
		t.Fatalf("expected 2 pending review, got %d", len(pending))
	}

	// Sign-off t2 by SDET
	if err := registry.SignOffTest("test_login_02", "sdet_jane_doe"); err != nil {
		t.Fatalf("failed to sign off t2: %v", err)
	}

	signedOff, err := registry.GetTest("test_login_02")
	if err != nil || signedOff.SignoffStatus != SignoffVerified || signedOff.SignedOffBy != "sdet_jane_doe" {
		t.Fatalf("test_login_02 signoff verification failed: %+v", signedOff)
	}

	// Pending should now be 1 (only t1)
	pending = registry.ListPendingReview()
	if len(pending) != 1 || pending[0].TestID != "test_checkout_01" {
		t.Fatalf("expected only test_checkout_01 pending, got %d", len(pending))
	}

	// Purge unreviewed tests older than 72h SLA
	purged, err := registry.PurgeUnreviewed(CorpusSignoffSLA)
	if err != nil {
		t.Fatalf("purge failed: %v", err)
	}
	if len(purged) != 1 || purged[0] != "test_checkout_01" {
		t.Fatalf("expected test_checkout_01 to be purged, got %v", purged)
	}

	// t2 (which is verified) must remain intact!
	active := registry.ListActiveTests()
	if len(active) != 1 || active[0].TestID != "test_login_02" {
		t.Fatalf("expected active test_login_02 to remain, got %v", active)
	}
}
