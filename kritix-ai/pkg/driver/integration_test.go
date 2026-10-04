//go:build integration

package driver

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestIntegration_CheckoutFlowWithHeadlessChrome(t *testing.T) {
	ts := createFixtureServer()
	defer ts.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	driver := NewCDPDriver(DefaultCDPConfig())
	err := driver.Start(ctx)
	if err != nil {
		t.Skipf("PREREQUISITE_MISSING: chrome (%v - Ensure Chromium/Google Chrome is installed)", err)
	}
	defer driver.Stop(ctx)

	// Step 1: Navigate to checkout fixture page
	state, err := driver.Navigate(ctx, ts.URL)
	if err != nil {
		t.Fatalf("Navigation failed: %v", err)
	}
	if state.Title != "Enterprise Checkout Fixture" {
		t.Errorf("Expected title 'Enterprise Checkout Fixture', got %q", state.Title)
	}

	// Step 2: Login with credentials + TOTP
	_, err = driver.ExecuteAction(ctx, Action{
		Type:        ActionTypeKey,
		TargetXPath: "#username",
		Value:       "engineer@enterprise.internal",
	})
	if err != nil {
		t.Fatalf("Failed to enter username: %v", err)
	}

	_, err = driver.ExecuteAction(ctx, Action{
		Type:        ActionTypeKey,
		TargetXPath: "#password",
		Value:       "P@ssw0rdEnterprise2026!",
	})
	if err != nil {
		t.Fatalf("Failed to enter password: %v", err)
	}

	_, err = driver.ExecuteAction(ctx, Action{
		Type:        ActionTypeKey,
		TargetXPath: "#totp",
		Value:       "482910",
	})
	if err != nil {
		t.Fatalf("Failed to enter TOTP: %v", err)
	}

	_, err = driver.ExecuteAction(ctx, Action{
		Type:        ActionClick,
		TargetXPath: "#btn-login",
	})
	if err != nil {
		t.Fatalf("Failed to click login button: %v", err)
	}

	// Step 3: Apply Promo Code
	_, err = driver.ExecuteAction(ctx, Action{
		Type:        ActionTypeKey,
		TargetXPath: "#promo",
		Value:       "ENTERPRISE15",
	})
	if err != nil {
		t.Fatalf("Failed to type promo code: %v", err)
	}

	_, err = driver.ExecuteAction(ctx, Action{
		Type:        ActionClick,
		TargetXPath: "#btn-apply-promo",
	})
	if err != nil {
		t.Fatalf("Failed to click apply promo button: %v", err)
	}

	// Step 4: File Upload
	tmpDir := t.TempDir()
	certFile := filepath.Join(tmpDir, "tax_exemption_cert.pdf")
	if err := os.WriteFile(certFile, []byte("%PDF-1.4 Dummy Tax Certificate"), 0644); err != nil {
		t.Fatalf("Failed to create temporary cert file: %v", err)
	}

	_, err = driver.ExecuteAction(ctx, Action{
		Type:        ActionUpload,
		TargetXPath: "#receipt-upload",
		Value:       certFile,
	})
	if err != nil {
		t.Fatalf("Failed to upload tax cert: %v", err)
	}

	// Step 5: Pierce Shadow DOM Button and Click
	shadowHTML, err := driver.PierceShadowDOM(ctx, "#shadow-host", "#btn-shadow-checkout")
	if err != nil {
		t.Fatalf("Failed to pierce shadow DOM: %v", err)
	}
	if shadowHTML == "" {
		t.Errorf("Expected shadow DOM element HTML")
	}

	// Step 6: Verify Unsupported Surfaces Detection
	findings, err := driver.DetectUnsupportedSurfaces(ctx)
	if err != nil {
		t.Fatalf("Failed to detect unsupported surfaces: %v", err)
	}
	if len(findings) < 3 {
		t.Errorf("Expected at least 3 unsupported surfaces (Canvas, Stripe, Turnstile), found %d: %v", len(findings), findings)
	}

	// Step 7: Animation stabilization
	err = driver.Stabilize(ctx, AnimationStabilizationOptions{
		StabilizationDelay: 100 * time.Millisecond,
		MaxWait:            2 * time.Second,
	})
	if err != nil {
		t.Fatalf("Stabilization failed: %v", err)
	}

	// Step 8: Capture Real Screenshot
	shotB64, err := driver.CaptureScreenshot(ctx)
	if err != nil {
		t.Fatalf("Failed to capture screenshot: %v", err)
	}
	if len(shotB64) < 1000 {
		t.Errorf("Screenshot data size (%d bytes) suspiciously small for full page PNG", len(shotB64))
	}

	// Step 9: Export HAR network archive
	har, err := driver.ExportHAR()
	if err != nil {
		t.Fatalf("Failed to export HAR: %v", err)
	}
	if len(har.Entries) == 0 {
		t.Errorf("Expected network requests captured in HAR report")
	}
}
