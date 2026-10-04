package spec

import (
	"strings"
	"testing"
)

func TestMultiArtifactCrossReferencing(t *testing.T) {
	bundle := MultiArtifactBundle{
		TicketID:         "PROJ-402",
		FeatureDesignDoc: "Checkout discount code system",
		CopyMatrix: map[string]string{
			"error_expired_code": "Promo code has expired. Please try another.",
			"success_applied":    "Promo code applied successfully!",
		},
		AnalyticsEvents: []AnalyticsEventDef{
			{
				EventName:     "ecommerce.coupon_applied",
				TriggerAction: "apply_coupon_success",
			},
			{
				EventName:     "ecommerce.coupon_failed",
				TriggerAction: "apply_coupon_error",
			},
		},
		AccessibilityNotes: []string{
			"Input must have aria-describedby pointing to error banner",
		},
	}

	// 1. Scenarios with missing tag and hallucinated copy string
	imperfectScenarios := []AcceptanceCriterion{
		{
			Given: "user enters expired code",
			When:  "they click apply",
			Then:  "banner displays 'Invalid code'", // Wrong copy!
		},
		{
			Given: "user enters valid code",
			When:  "apply_coupon_success happens",
			Then:  "Promo code applied successfully!",
		},
	}

	result := bundle.CrossReferenceValidations(imperfectScenarios)
	if result.ExactCopyMatched {
		t.Errorf("expected copy mismatch to be detected for error_expired_code")
	}
	if len(result.CopyMismatches) != 1 {
		t.Errorf("expected exactly 1 copy mismatch, got %d", len(result.CopyMismatches))
	}
	if len(result.MissingTags) != 1 {
		t.Errorf("expected 1 missing tag (coupon_failed), got %d", len(result.MissingTags))
	}

	// 2. Perfect scenarios adhering to Copy Doc and Tagging Doc
	perfectScenarios := []AcceptanceCriterion{
		{
			Given: "user enters expired code",
			When:  "apply_coupon_error triggers",
			Then:  "error shows 'Promo code has expired. Please try another.' and ecommerce.coupon_failed fires",
		},
		{
			Given: "user enters valid code",
			When:  "apply_coupon_success triggers",
			Then:  "banner shows 'Promo code applied successfully!' and ecommerce.coupon_applied fires",
		},
	}

	perfectResult := bundle.CrossReferenceValidations(perfectScenarios)
	if !perfectResult.ExactCopyMatched {
		t.Errorf("expected perfect scenarios to match all copy strings: %v", perfectResult.CopyMismatches)
	}
	if len(perfectResult.MissingTags) != 0 {
		t.Errorf("expected 0 missing tags, got %d", len(perfectResult.MissingTags))
	}
}

func TestConflictEscalationHaltsOnContradiction(t *testing.T) {
	// Contradictory specs:
	// FDD mandates redirect to thank-you page
	// Jira description mandates modal popup
	// Copy matrix mandates button text 'Place Order', but Jira mentions 'Submit Payment' button
	bundle := MultiArtifactBundle{
		TicketID:        "PROJ-891",
		JiraDescription: "User clicks Submit Payment button and remains in modal to view confirmation",
		FeatureDesignDoc: "Checkout completes and browser redirects to /order/confirmation thank you page",
		CopyMatrix: map[string]string{
			"checkout_cta_btn": "Place Order",
		},
		AmbiguityThreshold: 0.85,
	}

	report, err := bundle.ReconcileAndAudit(0.85)
	if err != nil {
		t.Fatalf("unexpected error during reconciliation: %v", err)
	}

	if !report.HaltedForHumanResolution {
		t.Errorf("expected reconciliation to HALT when contradictory documents exist")
	}

	if report.CalculatedConfidence >= 0.85 {
		t.Errorf("expected confidence to drop below threshold, got %f", report.CalculatedConfidence)
	}

	if len(report.Conflicts) != 2 {
		t.Errorf("expected exactly 2 conflicts (navigation + button text), got %d", len(report.Conflicts))
	}

	// Verify JSON formatting
	jsonStr, err := FormatConflictReportJSON(report)
	if err != nil {
		t.Fatalf("failed to format JSON conflict report: %v", err)
	}
	if !strings.Contains(jsonStr, `"halted_for_human_resolution": true`) {
		t.Errorf("expected JSON to show halted status")
	}

	// Verify Markdown formatting
	mdStr := FormatConflictReportMarkdown(report)
	if !strings.Contains(mdStr, "CONFLICT_REPORT") || !strings.Contains(mdStr, "HALTED_FOR_HUMAN_REVIEW") {
		t.Errorf("expected Markdown report to show review warning, got: %s", mdStr)
	}
}

