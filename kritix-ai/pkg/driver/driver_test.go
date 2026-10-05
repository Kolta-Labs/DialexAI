package driver

import (
	"context"
	"testing"
	"time"
)

func TestVirtualDriverNavigation(t *testing.T) {
	driver := NewVirtualDriver()
	ctx := context.Background()

	if err := driver.Start(ctx); err != nil {
		t.Fatalf("failed to start driver: %v", err)
	}
	defer driver.Stop(ctx)

	state, err := driver.Navigate(ctx, "https://example.com/checkout")
	if err != nil {
		t.Fatalf("navigation failed: %v", err)
	}

	if state.URL != "https://example.com/checkout" {
		t.Errorf("expected URL https://example.com/checkout, got %s", state.URL)
	}

	if len(state.NetworkActivity) == 0 {
		t.Errorf("expected network event logged on navigation")
	}
}

func TestVirtualDriverActionsAndSemanticMatch(t *testing.T) {
	driver := NewVirtualDriver()
	ctx := context.Background()

	// Seed virtual elements
	driver.SetVirtualDOM([]Element{
		{
			Tag:    "button",
			ID:     "btn-checkout",
			Role:   "button",
			Text:   "Complete Order",
			TestID: "order-submit-button",
			BoundingBox: Rect{X: 100, Y: 200, Width: 150, Height: 40},
		},
		{
			Tag:         "input",
			ID:          "coupon-input",
			Role:        "textbox",
			Placeholder: "Promo code",
			BoundingBox: Rect{X: 100, Y: 150, Width: 200, Height: 35},
		},
	}, &AXNode{
		ID:   "root",
		Role: "document",
		Name: "Checkout Page",
		Children: []AXNode{
			{ID: "1", Role: "textbox", Name: "Promo code"},
			{ID: "2", Role: "button", Name: "Complete Order"},
		},
	})

	state, err := driver.GetState(ctx)
	if err != nil {
		t.Fatalf("failed to get state: %v", err)
	}

	// 1. Semantic match test
	btn := state.FindElementBySemanticMatch("Complete Order")
	if btn == nil {
		t.Fatalf("failed to find element by text 'Complete Order'")
	}
	if btn.TestID != "order-submit-button" {
		t.Errorf("expected test-id order-submit-button, got %s", btn.TestID)
	}

	// 2. Action execution test
	newState, err := driver.ExecuteAction(ctx, Action{
		Type:       ActionClick,
		TargetRole: "button",
		TargetText: "Complete Order",
		Timestamp:  time.Now(),
	})
	if err != nil {
		t.Fatalf("failed to execute click action: %v", err)
	}

	if len(newState.ConsoleLogs) == 0 {
		t.Errorf("expected console log recorded for click action")
	}
}

func TestDetectUnsupportedSurfaces_Classification(t *testing.T) {
	// 1. Canvas
	f1 := ClassifyUnsupportedSurface("canvas", "", "")
	if f1 == nil || f1.Type != "CANVAS_WEBGL" {
		t.Errorf("expected CANVAS_WEBGL, got %+v", f1)
	}

	// 2. Cross-origin Stripe iframe
	f2 := ClassifyUnsupportedSurface("iframe", "https://js.stripe.com/v3/elements-inner-card.html", "")
	if f2 == nil || f2.Type != "CROSS_ORIGIN_IFRAME" {
		t.Errorf("expected CROSS_ORIGIN_IFRAME, got %+v", f2)
	}

	// 3. Cloudflare Turnstile CAPTCHA
	f3 := ClassifyUnsupportedSurface("div", "", "cf-turnstile")
	if f3 == nil || f3.Type != "CAPTCHA_TURNSTILE" {
		t.Errorf("expected CAPTCHA_TURNSTILE, got %+v", f3)
	}
}

