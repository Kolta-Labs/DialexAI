package sdet

import (
	"strings"
	"testing"

	"kritix/pkg/driver"
)

func TestSelfHealingLocatorResolution(t *testing.T) {
	registry := NewSelfHealingLocatorRegistry()

	// Initial element recorded: ID was 'checkout-btn-v1'
	registry.RegisterFingerprint(ElementFingerprint{
		ID:      "checkout-btn-v1",
		Role:    "button",
		Text:    "Confirm & Pay $49",
		Tag:     "button",
		BBox:    driver.Rect{X: 200, Y: 400, Width: 160, Height: 45},
		PageURL: "https://shop.example.com/checkout",
	})

	// Frontend team refactored: ID changed to 'pay-btn-v2' and DOM was restructured
	mutatedElements := []driver.Element{
		{
			Tag:         "input",
			ID:          "coupon-code",
			Role:        "textbox",
			BoundingBox: driver.Rect{X: 200, Y: 300, Width: 200, Height: 35},
		},
		{
			Tag:         "button",
			ID:          "pay-btn-v2",                      // changed!
			Role:        "button",                          // matches
			Text:        "Confirm & Pay $49",               // matches
			BoundingBox: driver.Rect{X: 205, Y: 402, Width: 160, Height: 45}, // close coords
		},
	}

	// 1. Advisory Mode: resolves element, but marks status as HEALED_REQUIRES_REVIEW (never clean EXACT_PASS)
	healed := registry.ResolveWithMode(HealModeAdvisory, "checkout-btn-v1", mutatedElements)
	if healed == nil {
		t.Fatalf("expected self-healing to resolve mutated element, got nil")
	}

	if healed.Status != StatusHealed {
		t.Errorf("expected status %s, got %s", StatusHealed, healed.Status)
	}

	if healed.ResolvedElement.ID != "pay-btn-v2" {
		t.Errorf("expected resolved element to be 'pay-btn-v2', got %s", healed.ResolvedElement.ID)
	}

	if healed.ConfidenceScore < 0.8 {
		t.Errorf("expected high confidence >= 0.8, got %f", healed.ConfidenceScore)
	}

	if healed.HealedSelector != "getByRole(\"button\", { name: \"Confirm & Pay $49\" })" {
		t.Errorf("unexpected healed selector: %s", healed.HealedSelector)
	}

	if !strings.Contains(healed.ProposedSelectorPatch, "getByRole") {
		t.Errorf("expected patch diff in resolution")
	}

	// 2. Strict CI Mode: MUST NOT silently pass or return resolved element!
	// It must flag regression and return proposed patch for developer review.
	strictResult := registry.ResolveWithMode(HealModeStrict, "checkout-btn-v1", mutatedElements)
	if strictResult == nil {
		t.Fatalf("expected strict result, got nil")
	}
	if strictResult.Status != StatusRegressionFail {
		t.Errorf("expected status %s in strict CI mode, got %s", StatusRegressionFail, strictResult.Status)
	}
	if strictResult.ResolvedElement != nil {
		t.Errorf("strict CI mode should NOT return a resolved element for execution")
	}
	if !strictResult.RegressionDetected {
		t.Errorf("expected RegressionDetected=true in strict CI mode")
	}
	if !strings.Contains(strictResult.Reason, "STRICT CI REGRESSION") {
		t.Errorf("expected regression reason in strict mode")
	}
}

func TestBBoxFallbackDisabledByDefault(t *testing.T) {
	registry := NewSelfHealingLocatorRegistry()

	registry.RegisterFingerprint(ElementFingerprint{
		ID:   "chart-export-btn",
		BBox: driver.Rect{X: 500, Y: 100, Width: 50, Height: 30},
	})

	// Mutated element with completely different text and role, only proximity
	mutatedElements := []driver.Element{
		{
			ID:          "delete-all-btn",
			Role:        "button",
			Text:        "Delete Everything",
			BoundingBox: driver.Rect{X: 502, Y: 102, Width: 50, Height: 30},
		},
	}

	// Should REJECT because BBox alone cannot justify healing without semantic match
	res := registry.Resolve("chart-export-btn", mutatedElements)
	if res != nil && res.ResolvedElement != nil {
		t.Errorf("expected rejection when only bounding box matches and BBox fallback is disabled")
	}
}

func TestHealedSelectorArtifactGeneration(t *testing.T) {
	registry := NewSelfHealingLocatorRegistry()

	registry.RegisterFingerprint(ElementFingerprint{
		ID:   "submit-btn-legacy",
		Role: "button",
		Text: "Submit Order",
		Tag:  "button",
	})

	mutatedElements := []driver.Element{
		{
			ID:   "submit-btn-v2",
			Role: "button",
			Text: "Submit Order",
			Tag:  "button",
		},
	}

	healed := registry.ResolveWithMode(HealModeAdvisory, "submit-btn-legacy", mutatedElements)
	if healed == nil || healed.Artifact == nil {
		t.Fatalf("expected healed resolution with non-nil artifact")
	}

	if healed.RiskLevel != RiskLow {
		t.Errorf("expected RiskLow for identical role and text, got %s", healed.RiskLevel)
	}

	if healed.Artifact.OriginalSelector != "submit-btn-legacy" {
		t.Errorf("expected original selector 'submit-btn-legacy', got %s", healed.Artifact.OriginalSelector)
	}

	jsonStr, err := FormatHealedSelectorArtifact(healed.Artifact)
	if err != nil {
		t.Fatalf("failed to format JSON artifact: %v", err)
	}
	if !strings.Contains(jsonStr, `"risk_level": "LOW"`) {
		t.Errorf("expected json artifact to contain risk_level LOW, got: %s", jsonStr)
	}

	mdStr := FormatHealedSelectorMarkdown(healed.Artifact)
	if !strings.Contains(mdStr, "HEALED_SELECTOR Review Required") {
		t.Errorf("expected markdown to contain review header, got: %s", mdStr)
	}
}

func TestFallbackChainLogging(t *testing.T) {
	registry := NewSelfHealingLocatorRegistry()
	registry.RegisterFingerprint(ElementFingerprint{
		ID:     "checkout-btn",
		TestID: "btn-checkout-primary",
		Role:   "button",
		Text:   "Place Order",
		Tag:    "button",
	})

	// TestID was removed, ID was changed to 'btn-order-new', but role and text match
	mutatedElements := []driver.Element{
		{
			ID:   "btn-order-new",
			Role: "button",
			Text: "Place Order",
			Tag:  "button",
		},
	}

	res := registry.ResolveWithMode(HealModeAdvisory, "checkout-btn", mutatedElements)
	if res == nil {
		t.Fatalf("expected resolution, got nil")
	}

	if len(res.FallbackChain) < 2 {
		t.Fatalf("expected fallback chain with at least 2 attempts, got %d", len(res.FallbackChain))
	}

	// First attempt must be data-testid which failed
	if res.FallbackChain[0].Strategy != "data-testid" || res.FallbackChain[0].Matched {
		t.Errorf("expected failed data-testid first attempt, got %+v", res.FallbackChain[0])
	}

	// Final attempt matched via role/text
	lastAttempt := res.FallbackChain[len(res.FallbackChain)-1]
	if !lastAttempt.Matched {
		t.Errorf("expected last attempt in chain to have matched: %+v", lastAttempt)
	}
}

func TestBBoxFallbackAlwaysFailsCheck(t *testing.T) {
	registry := NewSelfHealingLocatorRegistry()
	registry.SetAllowBBoxFallback(true) // Even when explicitly enabled

	registry.RegisterFingerprint(ElementFingerprint{
		ID:   "payment-submit",
		Role: "button",
		Text: "Pay $100",
		Tag:  "button",
		BBox: driver.Rect{X: 100, Y: 200, Width: 120, Height: 40},
	})

	// Candidate element only happens to be at close coordinates, but completely different text and role
	mutatedElements := []driver.Element{
		{
			ID:          "cancel-order-btn",
			Role:        "button",
			Text:        "Cancel Order",
			Tag:         "button",
			BoundingBox: driver.Rect{X: 105, Y: 202, Width: 120, Height: 40},
		},
	}

	res := registry.ResolveWithMode(HealModeAdvisory, "payment-submit", mutatedElements)
	if res == nil {
		t.Fatalf("expected resolution result, got nil")
	}

	// Contract blocker #2: Bounding Box fallback must ALWAYS fail the check, never heal!
	if res.Status != StatusRegressionFail {
		t.Errorf("expected StatusRegressionFail for BBox-only fallback, got: %s", res.Status)
	}

	if !strings.Contains(res.Reason, "Bounding box fallback is strictly prohibited") {
		t.Errorf("expected bounding box prohibition reason, got: %s", res.Reason)
	}

	blocked, reason := res.BlocksPRGate()
	if !blocked || !strings.Contains(reason, "Bounding box fallback") {
		t.Errorf("expected BBox fallback to block PR gate: blocked=%v, reason=%s", blocked, reason)
	}
}

func TestSemanticDiffValidatorRoleMutation(t *testing.T) {
	registry := NewSelfHealingLocatorRegistry()
	registry.RegisterFingerprint(ElementFingerprint{
		ID:   "agree-terms-btn",
		Role: "button", // Original was a button
		Text: "I Agree",
		Tag:  "button",
	})

	// Frontend refactored: now a checkbox with identical text
	mutatedElements := []driver.Element{
		{
			ID:   "agree-checkbox",
			Role: "checkbox", // Role mutated to checkbox!
			Text: "I Agree",
			Tag:  "input",
		},
	}

	res := registry.ResolveWithMode(HealModeAdvisory, "agree-terms-btn", mutatedElements)
	if res == nil {
		t.Fatalf("expected resolution result, got nil")
	}

	// Semantic diff validator must fail loudly on role mutation
	if res.Status != StatusRegressionFail {
		t.Errorf("expected StatusRegressionFail on role mutation, got: %s", res.Status)
	}

	if !strings.Contains(res.Reason, "Element ARIA role mutated") {
		t.Errorf("expected role mutation explanation in reason, got: %s", res.Reason)
	}

	blocked, _ := res.BlocksPRGate()
	if !blocked {
		t.Errorf("expected role mutation failure to block PR gate")
	}
}

func TestBlocksPRGateLowRankFallback(t *testing.T) {
	registry := NewSelfHealingLocatorRegistry()
	registry.RegisterFingerprint(ElementFingerprint{
		ID:     "promo-banner",
		TestID: "banner-promo",
		Tag:    "div",
		Text:   "Summer Sale 50% Off",
	})

	mutatedElements := []driver.Element{
		{
			Tag:  "div",
			Text: "Summer Sale 50% Off", // Matched via TagText (Tier 4, below TierTestID)
		},
	}

	res := registry.ResolveWithMode(HealModeAdvisory, "promo-banner", mutatedElements)
	if res == nil {
		t.Fatalf("expected resolution, got nil")
	}

	if res.Status != StatusHealed {
		t.Errorf("expected StatusHealed, got: %s", res.Status)
	}

	// TierTagText is ranked below TierTestID, so it must block the PR gate without waiver
	blocked, reason := res.BlocksPRGate()
	if !blocked || !strings.Contains(reason, "below TierTestID") {
		t.Errorf("expected low-rank fallback to block PR gate: blocked=%v, reason=%s", blocked, reason)
	}
}

func TestSemanticSwapCases_30Scenarios_ZeroFalsePass(t *testing.T) {
	registry := NewSelfHealingLocatorRegistry()

	swaps := []struct {
		id            string
		origRole      string
		origText      string
		origContainer string
		origIntent    string
		candRole      string
		candText      string
		candContainer string
		candIntent    string
	}{
		{"swap_01", "button", "Delete", "modal-danger-zone", "delete-account", "button", "Delete", "table-row-cart", "delete-cart-item"},
		{"swap_02", "button", "Cancel", "form-checkout", "cancel-order", "button", "Cancel", "modal-dialog", "dismiss-modal"},
		{"swap_03", "button", "Save", "draft-editor", "save-draft", "button", "Save", "publish-bar", "publish-live"},
		{"swap_04", "button", "Submit", "search-header", "search-query", "button", "Submit", "checkout-payment", "pay-now"},
		{"swap_05", "button", "Next", "pagination-footer", "next-page", "button", "Next", "wizard-stepper", "advance-step"},
		{"swap_06", "button", "Apply", "coupon-section", "apply-coupon", "button", "Apply", "filter-sidebar", "apply-filter"},
		{"swap_07", "button", "Select", "shipping-options", "choose-shipping", "button", "Select", "payment-methods", "choose-payment"},
		{"swap_08", "button", "Confirm", "auth-mfa", "verify-totp", "button", "Confirm", "newsletter-box", "confirm-newsletter"},
		{"swap_09", "button", "Add", "wishlist-panel", "add-to-wishlist", "button", "Add", "product-cart", "add-to-cart"},
		{"swap_10", "button", "Remove", "member-roster", "remove-user", "button", "Remove", "tag-list", "remove-tag"},
		{"swap_11", "button", "Edit", "user-profile", "edit-profile", "button", "Edit", "billing-card", "edit-billing"},
		{"swap_12", "button", "Download", "invoice-tab", "download-invoice", "button", "Download", "export-logs", "download-logs"},
		{"swap_13", "button", "Export", "analytics-dashboard", "export-sarif", "button", "Export", "settings-backup", "export-secrets"},
		{"swap_14", "button", "Verify", "domain-settings", "verify-dns", "button", "Verify", "identity-badge", "verify-id"},
		{"swap_15", "button", "Upgrade", "subscription-tier", "upgrade-enterprise", "button", "Upgrade", "plugin-marketplace", "upgrade-plugin"},
		{"swap_16", "button", "Enable", "security-2fa", "enable-mfa", "button", "Enable", "beta-features", "enable-dark-mode"},
		{"swap_17", "button", "Accept", "cookie-consent", "accept-cookies", "button", "Accept", "terms-modal", "accept-legal-terms"},
		{"swap_18", "button", "Subscribe", "newsletter-footer", "newsletter-sub", "button", "Subscribe", "saas-pricing", "saas-subscription"},
		{"swap_19", "button", "Clear", "shopping-cart", "clear-cart-items", "button", "Clear", "search-filter", "clear-filters"},
		{"swap_20", "button", "Close", "preview-tab", "close-tab", "button", "Close", "account-session", "terminate-session"},
		{"swap_21", "button", "Back", "product-detail", "back-to-catalog", "button", "Back", "onboarding-wizard", "back-to-step-1"},
		{"swap_22", "button", "Continue", "guest-flow", "guest-checkout", "button", "Continue", "sso-auth", "sso-login"},
		{"swap_23", "button", "Pay", "credit-card-form", "pay-with-card", "button", "Pay", "paypal-frame", "pay-with-paypal"},
		{"swap_24", "button", "Pause", "subscription-settings", "pause-plan", "button", "Pause", "billing-autopay", "disable-autopay"},
		{"swap_25", "button", "Transfer", "wallet-send", "send-money", "button", "Transfer", "wallet-receive", "request-money"},
		{"swap_26", "button", "Copy", "share-url-box", "copy-public-link", "button", "Copy", "secret-token-box", "copy-api-key"},
		{"swap_27", "button", "Pin", "dashboard-widgets", "pin-chart", "button", "Pin", "message-thread", "pin-announcement"},
		{"swap_28", "button", "Mute", "notification-center", "mute-thread", "button", "Mute", "security-alerts", "disable-security-alarms"},
		{"swap_29", "button", "Archive", "project-workspace", "archive-project", "button", "Archive", "database-records", "delete-database"},
		{"swap_30", "button", "Refresh", "feed-stream", "refresh-timeline", "button", "Refresh", "oauth-credentials", "rotate-refresh-token"},
	}

	falsePasses := 0

	for _, s := range swaps {
		fp := ElementFingerprint{
			ID:            s.id,
			Role:          s.origRole,
			Text:          s.origText,
			Tag:           "button",
			ContainerID:   s.origContainer,
			ActionIntent:  s.origIntent,
			BBox:          driver.Rect{X: 100, Y: 100, Width: 120, Height: 40},
		}
		registry.RegisterFingerprint(fp)

		candidateElement := driver.Element{
			ID:            "cand-" + s.id,
			Role:          s.candRole,
			Text:          s.candText,
			Tag:           "button",
			ContainerID:   s.candContainer,
			ActionIntent:  s.candIntent,
			BoundingBox:   driver.Rect{X: 102, Y: 101, Width: 120, Height: 40},
		}

		res := registry.ResolveWithMode(HealModeStrict, s.id, []driver.Element{candidateElement})
		if res == nil {
			t.Errorf("[%s] expected resolution outcome, got nil", s.id)
			continue
		}

		// INVARIANT: Every semantic swap MUST fail (0% false pass rate!)
		if res.Status == StatusExactPass || res.Status == StatusHealed {
			falsePasses++
			t.Errorf("[%s] CRITICAL SAFETY BREACH: Semantic swap was falsely healed or passed! Original: %s (%s in %s) -> Candidate: %s (%s in %s)",
				s.id, s.origText, s.origIntent, s.origContainer, s.candText, s.candIntent, s.candContainer)
		}
		if !res.RegressionDetected || res.Status != StatusRegressionFail {
			t.Errorf("[%s] expected StatusRegressionFail and RegressionDetected=true, got status=%s, reg=%v",
				s.id, res.Status, res.RegressionDetected)
		}
	}

	if falsePasses > 0 {
		t.Fatalf("Semantic swap false-pass rate: %d/30 (FAILED strict safety bound of 0.0%%)", falsePasses)
	}
}

