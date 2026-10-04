package spec

import (
	"strings"
	"testing"
)

func TestEvaluateINVEST(t *testing.T) {
	// Good story
	goodStory := Story{
		ID:          "AUTH-01",
		Title:       "User Password Reset",
		AsA:         "Registered user",
		IWant:       "to reset my forgotten password",
		SoThat:      "I can regain access to my account",
		Description: "Allow user to receive an email token to set a new password",
		Priority:    PriorityHigh,
		AcceptanceCriteria: []AcceptanceCriterion{
			{
				ID:        "AC-1",
				Given:     "a user requests a reset for an existing email",
				When:      "they submit the reset form",
				Then:      "a time-limited token is sent within 60 seconds",
				EdgeCases: []string{"Email does not exist", "Token expired after 15 minutes"},
			},
		},
	}

	assessment := EvaluateINVEST(goodStory)
	if !assessment.Testable {
		t.Errorf("good story should be marked testable")
	}
	if assessment.Score < 80 {
		t.Errorf("expected score >= 80, got %d", assessment.Score)
	}

	// Ambiguous story with vague words and missing criteria
	badStory := Story{
		ID:          "CHECKOUT-02",
		Title:       "Make checkout fast and user-friendly",
		Description: "The checkout process should be intuitive and fast etc",
	}

	badAssessment := EvaluateINVEST(badStory)
	if badAssessment.Testable {
		t.Errorf("story without ACs should not be marked testable")
	}
	if len(badAssessment.AmbiguousPhrases) == 0 {
		t.Errorf("expected vague words to be flagged")
	}
}

func TestGenerateGherkinAndSDETReviewGate(t *testing.T) {
	story := NewStory("Promo Code Validation", "Apply discounts during checkout")
	story.AcceptanceCriteria = []AcceptanceCriterion{
		{
			Given:     "a cart total of $100",
			When:      "the user enters code 'SAVE20'",
			Then:      "total should be discounted to $80",
			EdgeCases: []string{"Code is expired", "Code is lowercase"},
		},
	}

	// 1. Initial State: Must be Draft Pending SDET Review
	if story.IsApprovedForExecution() {
		t.Errorf("newly synthesized spec should NOT be approved for execution autonomously")
	}

	draftGherkin := GenerateGherkin(story)
	if !strings.Contains(draftGherkin, "DRAFT_PENDING_SDET_REVIEW") {
		t.Errorf("missing draft warning header in Gherkin")
	}

	// 2. Human SDET signs off
	err := story.Approve("lead.sdet@enterprise.internal", "Verified against promo service API v2")
	if err != nil {
		t.Fatalf("failed to approve story: %v", err)
	}

	if !story.IsApprovedForExecution() {
		t.Errorf("story should be approved for execution after SDET sign-off")
	}

	approvedGherkin := GenerateGherkin(story)
	if !strings.Contains(approvedGherkin, "APPROVED_BY_SDET") || !strings.Contains(approvedGherkin, "lead.sdet@enterprise.internal") {
		t.Errorf("expected approval attribution in Gherkin: %s", approvedGherkin)
	}
}
