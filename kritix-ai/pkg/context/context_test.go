package context

import (
	"testing"
)

func TestTestArchitectBuildsValidContext(t *testing.T) {
	architect := NewTestArchitect("Staff QA Architect")
	fc := architect.BuildECommerceCheckoutContext()

	if fc.Domain != DomainECommerce {
		t.Errorf("expected DomainECommerce, got %s", fc.Domain)
	}

	if len(fc.Transitions) == 0 {
		t.Errorf("expected transitions defined")
	}

	// 1. Legal transition
	valid, reason := fc.ValidateTransition("cart_view", "shipping_entry", "click_checkout")
	if !valid {
		t.Errorf("expected legal transition to pass: %s", reason)
	}

	// 2. Illegal transition (skipping shipping straight to payment)
	invalid, reason := fc.ValidateTransition("cart_view", "payment_entry", "click_checkout")
	if invalid {
		t.Errorf("expected illegal transition to fail")
	}
	if reason == "" {
		t.Errorf("expected reason for illegal transition")
	}
}
