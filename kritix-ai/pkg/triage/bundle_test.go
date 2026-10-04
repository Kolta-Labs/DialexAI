package triage

import (
	"strings"
	"testing"
	"time"

	"kritix/pkg/driver"
)

func TestGeneratePlaywrightRepro(t *testing.T) {
	actions := []driver.Action{
		{
			Type:        driver.ActionClick,
			TargetRole:  "button",
			TargetText:  "Checkout",
			Description: "Click checkout CTA",
			Timestamp:   time.Now(),
		},
		{
			Type:        driver.ActionTypeKey,
			TargetXPath: "#discount-code",
			Value:       "INVALID_CODE",
			Description: "Enter bad discount code",
			Timestamp:   time.Now(),
		},
	}

	repro := GeneratePlaywrightRepro("https://shop.example.com/cart", actions, "Should display friendly error message instead of 500")

	if !strings.Contains(repro, "import { test, expect } from '@playwright/test';") {
		t.Errorf("missing Playwright import")
	}
	if !strings.Contains(repro, "await page.goto(\"https://shop.example.com/cart\");") {
		t.Errorf("missing navigation step")
	}
	if !strings.Contains(repro, "await page.getByRole(\"button\", { name: \"Checkout\" }).click();") {
		t.Errorf("missing role-based click step")
	}
	if !strings.Contains(repro, "await page.locator(\"#discount-code\").fill(\"INVALID_CODE\");") {
		t.Errorf("missing fill step")
	}
}

func TestGenerateCurlRepro(t *testing.T) {
	event := driver.NetworkEvent{
		URL:        "https://api.example.com/v1/checkout",
		Method:     "POST",
		StatusCode: 500,
		Headers: map[string]string{
			"Authorization": "Bearer tok_123",
			"Content-Type":  "application/json",
		},
	}

	curl := GenerateCurlRepro(event)
	if !strings.Contains(curl, "curl -i -X POST \"https://api.example.com/v1/checkout\"") {
		t.Errorf("missing base curl command: got %s", curl)
	}
	if !strings.Contains(curl, "-H \"Authorization: Bearer tok_123\"") {
		t.Errorf("missing headers in curl repro")
	}
}

func TestDefectReportConfidenceGating(t *testing.T) {
	// 1. Low confidence defect (<85%): MUST suppress suggested diff!
	lowConfReport := DefectReport{
		Title:           "Cart total incorrect",
		Severity:        SeverityMajor,
		Category:        CategoryCodeRegression,
		ConfidenceScore: 0.72,
		PlaywrightRepro: "// repro test",
		ProposedFixDiff: "- return subtotal\n+ return subtotal - discount",
		Tags:            []string{"kritix-ai-generated", "needs-triage"},
	}

	if lowConfReport.ShouldEmitSuggestedDiff() {
		t.Errorf("suggested diff should be suppressed for low confidence (72%%)")
	}

	jiraMD := lowConfReport.FormatJiraMarkdown()
	if strings.Contains(jiraMD, "- return subtotal") {
		t.Errorf("Jira report must NOT display code diff when confidence < 85%%")
	}
	if !strings.Contains(jiraMD, "below threshold to prevent incorrect code suggestions") {
		t.Errorf("missing suppression explanation in Jira report")
	}

	// 2. High confidence defect (>=85%): MUST display suggested diff!
	highConfReport := DefectReport{
		Title:           "Cart total calculation bug",
		Severity:        SeverityCritical,
		Category:        CategoryCodeRegression,
		ConfidenceScore: 0.92,
		PlaywrightRepro: "// repro test",
		ProposedFixDiff: "- return subtotal\n+ return subtotal - discount",
		Tags:            []string{"kritix-ai-generated", "needs-triage"},
	}

	if !highConfReport.ShouldEmitSuggestedDiff() {
		t.Errorf("high confidence report (92%%) should emit suggested diff")
	}
	highJiraMD := highConfReport.FormatJiraMarkdown()
	if !strings.Contains(highJiraMD, "- return subtotal") {
		t.Errorf("missing code diff in high-confidence Jira markdown")
	}

	// 3. Infrastructure failure (502 Gateway error): MUST suppress diff regardless of confidence!
	infraReport := DefectReport{
		Title:           "Checkout API 502 Bad Gateway",
		Severity:        SeverityBlocker,
		Category:        CategoryInfraFlake,
		ConfidenceScore: 0.95,
		ProposedFixDiff: "+ addRetryLogic()",
	}

	if infraReport.ShouldEmitSuggestedDiff() {
		t.Errorf("infrastructure failure must NEVER emit a suggested application code diff")
	}
}

func TestClassifyFailure(t *testing.T) {
	// Infra 502
	infraEvents := []driver.NetworkEvent{
		{StatusCode: 502, URL: "https://api.internal/checkout"},
	}
	cat := ClassifyFailure(nil, infraEvents)
	if cat != CategoryInfraFlake {
		t.Errorf("expected CategoryInfraFlake on 502, got %s", cat)
	}

	// Code regression
	codeEvents := []driver.NetworkEvent{
		{StatusCode: 400, URL: "https://api.internal/login"},
	}
	cat2 := ClassifyFailure([]string{"AssertionError: expected 'Welcome'"}, codeEvents)
	if cat2 != CategoryCodeRegression {
		t.Errorf("expected CategoryCodeRegression, got %s", cat2)
	}
}
