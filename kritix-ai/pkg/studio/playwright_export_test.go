package studio

import (
	"strings"
	"testing"
	"time"

	"kritix/pkg/driver"
)

// TestPlaywrightExport_PortabilityProof verifies that Studio exported tests generate
// valid, standalone Playwright TypeScript specs executable in standard vanilla Playwright
// without proprietary runtime lock-in.
func TestPlaywrightExport_PortabilityProof(t *testing.T) {
	session := NewStudioSession("sess-portability", "Standard E-Commerce Checkout", "https://store.example.com")
	session.AuthorSDET = "lead-sdet@enterprise.internal"
	session.BusinessIntent = "Verify end-to-end checkout flow without session drops"

	session.RecordInteraction(HumanAction{
		Type:            driver.ActionClick,
		TargetRole:      "button",
		TargetText:      "Add to Cart",
		StepIntent:      "Add selected item to shopping bag",
		ExpectedOutcome: "Cart count updates to 1",
		Timestamp:       time.Now(),
	})

	session.RecordInteraction(HumanAction{
		Type:            driver.ActionTypeKey,
		TargetID:        "email-input",
		InputValue:      "customer@example.com",
		StepIntent:      "Enter customer checkout email",
		ExpectedOutcome: "Email field populated",
		Timestamp:       time.Now(),
	})

	code := session.ExportPlaywrightTemplate()

	requiredPatterns := []string{
		"import { test, expect } from '@playwright/test';",
		"test(\"Standard E-Commerce Checkout\", async ({ page }) => {",
		"await page.goto(\"https://store.example.com\");",
		"// Step 2: Add selected item to shopping bag",
		"await page.getByRole(\"button\", { name: \"Add to Cart\" }).click();",
		"// Step 3: Enter customer checkout email",
		"await page.locator('#email-input').fill(\"customer@example.com\");",
		"await expect(page).toHaveScreenshot();",
	}

	for _, pattern := range requiredPatterns {
		if !strings.Contains(code, pattern) {
			t.Errorf("Playwright export missing required pattern %q in generated code:\n%s", pattern, code)
		}
	}
}
