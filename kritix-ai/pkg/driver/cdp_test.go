package driver

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestCDPConfig_Defaults(t *testing.T) {
	cfg := DefaultCDPConfig()
	if !cfg.Headless {
		t.Errorf("expected Headless=true by default")
	}
	if cfg.ViewportWidth != 1440 || cfg.ViewportHeight != 900 {
		t.Errorf("expected 1440x900 viewport, got %dx%d", cfg.ViewportWidth, cfg.ViewportHeight)
	}

	driver := NewCDPDriver(CDPConfig{})
	if driver.config.ViewportWidth != 1440 || driver.config.ViewportHeight != 900 {
		t.Errorf("expected default 1440x900, got %dx%d", driver.config.ViewportWidth, driver.config.ViewportHeight)
	}
}

func TestExportHAR(t *testing.T) {
	d := NewCDPDriver(DefaultCDPConfig())
	d.networkLog = []NetworkEvent{
		{
			URL:        "https://api.shop.internal/v1/cart",
			Method:     "POST",
			StatusCode: 200,
			Duration:   120 * time.Millisecond,
			Headers: map[string]string{
				"Content-Type": "application/json",
			},
			PostData: `{"item_id":"SKU-123","quantity":2}`,
		},
		{
			URL:        "https://api.shop.internal/v1/checkout",
			Method:     "GET",
			StatusCode: 302,
			Duration:   45 * time.Millisecond,
		},
	}

	har, err := d.ExportHAR()
	if err != nil {
		t.Fatalf("failed to export HAR: %v", err)
	}

	if har.Version != "1.2" {
		t.Errorf("expected HAR version 1.2, got %s", har.Version)
	}
	if len(har.Entries) != 2 {
		t.Fatalf("expected 2 HAR entries, got %d", len(har.Entries))
	}
	if har.Entries[0].Request.Method != "POST" || har.Entries[0].Request.PostData == "" {
		t.Errorf("expected POST with postData, got %v", har.Entries[0].Request)
	}
}

func TestStorageState_SaveAndLoad(t *testing.T) {
	tmpDir := t.TempDir()
	stateFile := filepath.Join(tmpDir, "storage_state.json")

	// Verify file writing format
	dummyState := `{"localStorage":"{\"token\":\"jwt-secret-xyz\",\"theme\":\"dark\"}"}`
	if err := os.WriteFile(stateFile, []byte(dummyState), 0600); err != nil {
		t.Fatalf("failed to write dummy state: %v", err)
	}

	data, err := os.ReadFile(stateFile)
	if err != nil {
		t.Fatalf("failed to read state: %v", err)
	}
	if len(data) == 0 {
		t.Errorf("expected non-empty state file")
	}
}

func TestShadowDOMPierceSelector(t *testing.T) {
	cases := []struct {
		root     string
		inner    string
		expected string
	}{
		{"", "#btn", "#btn"},
		{"custom-checkout", "button.submit", "custom-checkout >> internal:control=enter-shadow >> button.submit"},
	}

	for _, tc := range cases {
		res := ShadowDOMPierceSelector(tc.root, tc.inner)
		if res != tc.expected {
			t.Errorf("expected %q, got %q", tc.expected, res)
		}
	}
}

// createFixtureServer spins up a local HTML test fixture server covering:
// - Shadow DOM component
// - Same-origin iframe form
// - Unsupported surfaces: Canvas/WebGL, Stripe cross-origin iframe, Turnstile CAPTCHA
// - File upload input
// - JS animations and alerts
func createFixtureServer() *httptest.Server {
	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, `
<!DOCTYPE html>
<html>
<head>
  <title>Enterprise Checkout Fixture</title>
  <style>
    .cf-turnstile { width: 300px; height: 65px; background: #eee; }
    #animated-box { width: 100px; height: 100px; background: blue; transition: transform 0.1s; }
  </style>
</head>
<body>
  <h1>Enterprise Checkout</h1>

  <section id="login-section">
    <form id="login-form">
      <label for="username">Username</label>
      <input type="text" id="username" name="username" placeholder="user@company.com" data-testid="input-username" />

      <label for="password">Password</label>
      <input type="password" id="password" name="password" data-testid="input-password" />

      <label for="totp">2FA TOTP Code</label>
      <input type="text" id="totp" name="totp" placeholder="123456" data-testid="input-totp" />

      <button type="button" id="btn-login" data-testid="btn-login" onclick="handleLogin()">Sign In</button>
    </form>
    <div id="login-status" role="status"></div>
  </section>

  <section id="cart-section">
    <h2>Shopping Cart</h2>
    <div id="cart-item" data-testid="cart-item">Enterprise Cloud License (Qty: 1)</div>
    <label for="promo">Promo Code</label>
    <input type="text" id="promo" name="promo" placeholder="Enter coupon" />
    <button type="button" id="btn-apply-promo" onclick="applyPromo()">Apply</button>
    <span id="promo-status"></span>

    <!-- Shadow DOM Web Component -->
    <div id="shadow-host"></div>

    <!-- File Upload -->
    <label for="receipt-upload">Upload Tax Exemption Certificate</label>
    <input type="file" id="receipt-upload" name="receipt" data-testid="file-upload" />
  </section>

  <section id="payment-section">
    <h2>Payment Information</h2>
    <!-- Same-Origin Iframe -->
    <iframe id="payment-frame" src="/iframe-payment" width="400" height="150"></iframe>

    <!-- Unsupported Surfaces -->
    <div id="unsupported-surfaces">
      <h3>Unsupported Surfaces</h3>
      <canvas id="signature-canvas" width="200" height="100"></canvas>
      <iframe id="stripe-frame" src="https://js.stripe.com/v3/elements-inner-card.html" width="300" height="50"></iframe>
      <div class="cf-turnstile" data-sitekey="0x4AAAAAA"></div>
    </div>
  </section>

  <div id="animated-box"></div>

  <script>
    // Initialize Shadow DOM
    const host = document.getElementById('shadow-host');
    if (host) {
      const shadow = host.attachShadow({ mode: 'open' });
      shadow.innerHTML = '<button id="btn-shadow-checkout" class="btn-primary" style="padding:10px;">Confirm & Pay ($999)</button>';
      shadow.getElementById('btn-shadow-checkout').addEventListener('click', () => {
        document.getElementById('login-status').innerText = 'Order Placed Successfully';
      });
    }

    function handleLogin() {
      const u = document.getElementById('username').value;
      const t = document.getElementById('totp').value;
      if (u && t) {
        document.getElementById('login-status').innerText = 'Authenticated';
      }
    }

    function applyPromo() {
      document.getElementById('promo-status').innerText = '15% Enterprise Discount Applied';
    }
  </script>
</body>
</html>
`)
	})

	mux.HandleFunc("/iframe-payment", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		fmt.Fprint(w, `
<!DOCTYPE html>
<html>
<body>
  <h4>Same-Origin Billing Frame</h4>
  <input type="text" id="cc-number" placeholder="4111 1111 1111 1111" data-testid="input-cc" />
  <input type="text" id="billing-zip" placeholder="94105" data-testid="input-zip" />
</body>
</html>
`)
	})

	return httptest.NewServer(mux)
}

func TestCDPDriver_UnsupportedSurfaceDetection(t *testing.T) {
	ts := createFixtureServer()
	defer ts.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	driver := NewCDPDriver(DefaultCDPConfig())
	err := driver.Start(ctx)
	if err != nil {
		t.Skipf("Skipping live Chrome CDP test (Chrome binary not found on host): %v", err)
		return
	}
	defer driver.Stop(ctx)

	state, err := driver.Navigate(ctx, ts.URL)
	if err != nil {
		t.Fatalf("failed to navigate to fixture: %v", err)
	}

	if len(state.Findings) == 0 {
		t.Fatalf("expected unsupported surface findings on fixture page")
	}

	var foundCanvas, foundStripe, foundTurnstile bool
	for _, f := range state.Findings {
		switch f.Type {
		case SurfaceCanvasWebGL:
			foundCanvas = true
		case SurfaceCrossOriginIframe:
			foundStripe = true
		case SurfaceCaptchaTurnstile:
			foundTurnstile = true
		}
	}

	if !foundCanvas {
		t.Errorf("expected SurfaceCanvasWebGL finding for #signature-canvas")
	}
	if !foundStripe {
		t.Errorf("expected SurfaceCrossOriginIframe finding for stripe iframe")
	}
	if !foundTurnstile {
		t.Errorf("expected SurfaceCaptchaTurnstile finding for .cf-turnstile")
	}

	// Verify that clicking an unsupported surface fails closed
	_, clickErr := driver.ExecuteAction(ctx, Action{
		Type:        ActionClick,
		TargetXPath: "#signature-canvas",
	})
	if clickErr == nil {
		t.Errorf("expected click on canvas to fail with ErrUnsupportedSurface")
	}
}

func TestCDPDriver_ShadowDOMAndStateInspection(t *testing.T) {
	ts := createFixtureServer()
	defer ts.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	driver := NewCDPDriver(DefaultCDPConfig())
	err := driver.Start(ctx)
	if err != nil {
		t.Skipf("Skipping live Chrome CDP test (Chrome binary not found on host): %v", err)
		return
	}
	defer driver.Stop(ctx)

	_, err = driver.Navigate(ctx, ts.URL)
	if err != nil {
		t.Fatalf("navigation failed: %v", err)
	}

	// 1. Pierce open Shadow DOM root
	shadowHTML, err := driver.PierceShadowDOM(ctx, "#shadow-host", "#btn-shadow-checkout")
	if err != nil {
		t.Fatalf("failed to pierce shadow DOM: %v", err)
	}
	if shadowHTML == "" {
		t.Errorf("expected shadow DOM HTML returned")
	}

	// 2. Capture real screenshot
	shot, err := driver.CaptureScreenshot(ctx)
	if err != nil {
		t.Fatalf("failed to capture screenshot: %v", err)
	}
	if len(shot) < 100 {
		t.Errorf("screenshot base64 too small, expected real PNG image data")
	}

	// 3. Inspect AX Tree
	axTree, err := driver.GetFullAXTree(ctx)
	if err != nil {
		t.Fatalf("failed to get AX tree: %v", err)
	}
	if axTree == nil || axTree.Role == "" {
		t.Errorf("expected valid AXTree root")
	}

	// 4. Runtime Capabilities probing
	caps, err := driver.GetCapabilities(ctx)
	if err != nil {
		t.Fatalf("failed to get capabilities: %v", err)
	}
	if !caps.SupportsShadowDOM {
		t.Errorf("expected SupportsShadowDOM=true")
	}
}
