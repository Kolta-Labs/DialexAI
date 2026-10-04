package optimizer

import (
	"strings"
	"testing"

	"kritix/pkg/driver"
	"kritix/pkg/model"
)

func TestCompressDOMForVision(t *testing.T) {
	optimizer := NewTokenCostOptimizer()

	elements := []driver.Element{
		{Tag: "button", Text: "Checkout Now", Role: "button", XPath: "/html/body/div[1]/main/div/section/form/div[4]/button"},
		{Tag: "input", ID: "email-input", Role: "textbox", Placeholder: "user@domain.com", XPath: "/html/body/div[1]/main/div/section/form/div[1]/input"},
		{Tag: "div", Disabled: true, XPath: "/html/body/div[1]/footer/div[2]/div[1]"}, // Noise element
	}

	compressed, saved := optimizer.CompressDOMForVision(elements)
	if !strings.Contains(compressed, "[button] \"Checkout Now\" role=button") {
		t.Errorf("missing compressed button element: %s", compressed)
	}
	if !strings.Contains(compressed, "[input] \"email-input\" role=textbox") {
		t.Errorf("missing compressed input element: %s", compressed)
	}
	if saved <= 0 {
		t.Errorf("expected positive token savings from pruning DOM noise, got %d", saved)
	}
}

func TestSemanticDecisionCaching(t *testing.T) {
	optimizer := NewTokenCostOptimizer()

	elements := []driver.Element{
		{Tag: "button", Text: "Submit"},
	}

	hash := ComputeStateHash("https://example.com/form", elements)
	if hash == "" {
		t.Fatalf("expected non-empty state hash")
	}

	// 1. Initial lookup -> miss
	_, found := optimizer.GetCachedDecision(hash)
	if found {
		t.Errorf("expected cache miss on initial lookup")
	}

	// 2. Store decision
	optimizer.CacheDecision(hash, model.Response{Content: "click Submit"})

	// 3. Second lookup -> hit (0 tokens spent!)
	resp, found := optimizer.GetCachedDecision(hash)
	if !found || resp.Content != "click Submit" {
		t.Errorf("expected cache hit with matching content")
	}
}

func TestRecordOperationAndSavingsSummary(t *testing.T) {
	optimizer := NewTokenCostOptimizer()

	// 1. Deterministic Go native operations (e.g. A11y calculation, regex, TOTP)
	optimizer.RecordDeterministicBypass(5000) // Saved 5000 tokens

	// 2. Developer CLI subshell ($0 bill)
	optimizer.RecordOperation(model.ModeCLI, 4000)

	// 3. Local offline model ($0 bill)
	optimizer.RecordOperation(model.ModeLocal, 6000)

	// 4. Cloud API operation
	optimizer.RecordOperation(model.ModeAPI, 1000)

	report := optimizer.GetSavingsReport()
	if report.TotalRequestsHandled != 4 {
		t.Errorf("expected 4 total requests, got %d", report.TotalRequestsHandled)
	}
	if report.DeterministicZeroTokenOps != 1 {
		t.Errorf("expected 1 deterministic operation, got %d", report.DeterministicZeroTokenOps)
	}
	if report.EstimatedDollarSavingsUSD <= 0 {
		t.Errorf("expected calculated dollar savings > 0")
	}

	summary := report.FormatSavingsSummary()
	if !strings.Contains(summary, "KRITIX AI TOKEN EFFICIENCY & ROI REPORT") {
		t.Errorf("summary missing title")
	}
	if !strings.Contains(summary, "Deterministic Go Native (0 Tokens): 1") {
		t.Errorf("summary missing deterministic metric")
	}
}
