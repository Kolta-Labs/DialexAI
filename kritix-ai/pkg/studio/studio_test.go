package studio

import (
	"strings"
	"testing"
	"time"

	"kritix/pkg/driver"
)

func TestStudioRecordAndSynthesize(t *testing.T) {
	session := NewStudioSession("sess-rec-01", "Complex VAT Exemption Flow", "https://app.example.com/billing")

	// Missing intent must fail validation
	if err := session.ValidateIntentDocumentation(); err == nil {
		t.Errorf("expected error when validating session without business intent")
	}

	session.AuthorSDET = "sdet_jane_doe"
	session.BusinessIntent = "Verify cross-border European VAT exemption rules and invoice tax breakdown"
	session.VerificationRules = []string{
		"VIES registry check must return valid",
		"0% VAT rate applied to reverse-charge invoice",
	}

	if err := session.ValidateIntentDocumentation(); err != nil {
		t.Fatalf("unexpected validation error: %v", err)
	}

	session.RecordInteraction(HumanAction{
		Type:            driver.ActionClick,
		TargetRole:      "tab",
		TargetText:      "Tax Settings",
		Timestamp:       time.Now(),
		StepIntent:      "Switch to organization tax configuration view",
		ExpectedOutcome: "Tax settings form renders with EU VAT input field",
	})

	session.RecordInteraction(HumanAction{
		Type:            driver.ActionTypeKey,
		TargetID:        "vat-number-input",
		InputValue:      "DE123456789",
		Timestamp:       time.Now(),
		StepIntent:      "Enter German corporate VAT ID for reverse charge",
		ExpectedOutcome: "Input displays green validation checkmark",
	})

	session.RecordInteraction(HumanAction{
		Type:            driver.ActionClick,
		TargetRole:      "button",
		TargetText:      "Apply Exemption",
		Timestamp:       time.Now(),
		StepIntent:      "Submit tax exemption application",
		ExpectedOutcome: "Invoice preview updates to 0% VAT reverse-charge note",
	})

	// 1. Synthesize BDD story
	story := session.SynthesizeAutonomousScenario()
	if story.Title != "Complex VAT Exemption Flow" {
		t.Errorf("expected story title Complex VAT Exemption Flow, got %s", story.Title)
	}
	if len(story.AcceptanceCriteria) != 3 {
		t.Errorf("expected 3 synthesized criteria steps, got %d", len(story.AcceptanceCriteria))
	}
	if !strings.Contains(story.Description, "Author SDET: sdet_jane_doe") {
		t.Errorf("missing author SDET in story description: %s", story.Description)
	}

	// 2. Export Playwright test template
	script := session.ExportPlaywrightTemplate()
	if !strings.Contains(script, "Business Intent: Verify cross-border European VAT exemption rules") {
		t.Errorf("missing business intent comment in script: %s", script)
	}
	if !strings.Contains(script, "Author SDET: sdet_jane_doe") {
		t.Errorf("missing author SDET comment in script: %s", script)
	}
	if !strings.Contains(script, "await page.getByRole(\"tab\", { name: \"Tax Settings\" }).click();") {
		t.Errorf("missing tab click in script: %s", script)
	}
	if !strings.Contains(script, "await page.locator('#vat-number-input').fill(\"DE123456789\");") {
		t.Errorf("missing fill step in script: %s", script)
	}
	if !strings.Contains(script, "// Invariant: Tax settings form renders with EU VAT input field") {
		t.Errorf("missing invariant comment in script: %s", script)
	}
}
