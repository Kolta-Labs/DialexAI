package workflow

import (
	"testing"

	"kritix/pkg/driver"
	"kritix/pkg/spec"
)

func TestValidateGherkinAgainstAXTree_BoundSuccess(t *testing.T) {
	axRoot := &driver.AXNode{
		ID:   "root",
		Role: "document",
		Name: "Checkout Page",
		Children: []driver.AXNode{
			{ID: "coupon-input", Role: "textbox", Name: "Promo code"},
			{ID: "btn-apply", Role: "button", Name: "Apply"},
			{ID: "banner-status", Role: "status", Name: "Promo code has expired. Please try another."},
		},
	}

	elements := []driver.Element{
		{ID: "coupon-input", Role: "textbox", Text: "Promo code", TestID: "coupon-field"},
		{ID: "btn-apply", Role: "button", Text: "Apply", TestID: "apply-button"},
	}

	criteria := []spec.AcceptanceCriterion{
		{
			ID:    "AC-1",
			Given: "user enters expired coupon into 'Promo code'",
			When:  "they click 'Apply'",
			Then:  "banner displays 'Promo code has expired. Please try another.'",
		},
	}

	results := ValidateGherkinAgainstAXTree(criteria, axRoot, elements)
	if len(results) != 1 {
		t.Fatalf("expected 1 scenario result, got %d", len(results))
	}

	if results[0].Status != StatusBoundVerified {
		t.Errorf("expected StatusBoundVerified, got %s (clarification: %s)", results[0].Status, results[0].Clarification)
	}
	if len(results[0].UnboundSteps) > 0 {
		t.Errorf("expected 0 unbound steps, got %v", results[0].UnboundSteps)
	}
}

func TestValidateGherkinAgainstAXTree_NeedsClarification(t *testing.T) {
	axRoot := &driver.AXNode{
		ID:   "root",
		Role: "document",
		Name: "Home Page",
		Children: []driver.AXNode{
			{ID: "search-box", Role: "textbox", Name: "Search products"},
		},
	}

	elements := []driver.Element{
		{ID: "search-box", Role: "textbox", Text: "Search products"},
	}

	// Scenario references nonexistent 'Crypto Wallet Connect' button
	criteria := []spec.AcceptanceCriterion{
		{
			ID:    "AC-Unbound",
			Given: "user is on the Home Page",
			When:  "they click 'Connect Phantom Wallet'", // NOT in AXTree
			Then:  "modal displays 'Wallet Connected'",
		},
	}

	results := ValidateGherkinAgainstAXTree(criteria, axRoot, elements)
	if len(results) != 1 {
		t.Fatalf("expected 1 scenario result, got %d", len(results))
	}

	if results[0].Status != StatusNeedsClarification {
		t.Errorf("expected StatusNeedsClarification for unbound step, got %s", results[0].Status)
	}
	if len(results[0].UnboundSteps) == 0 {
		t.Errorf("expected unbound steps recorded in result")
	}
}
