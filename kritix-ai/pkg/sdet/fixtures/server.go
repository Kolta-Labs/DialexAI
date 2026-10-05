package fixtures

import (
	"fmt"
	"net/http"
	"net/http/httptest"
)

// FixtureServer serves real HTML test pages covering every category in the locator safety taxonomy.
type FixtureServer struct {
	Server *httptest.Server
	URL    string
}

// StartFixtureServer initializes and runs the local taxonomy fixture site.
func StartFixtureServer() *FixtureServer {
	mux := http.NewServeMux()

	mux.HandleFunc("/", handleIndex)
	mux.HandleFunc("/same-role", handleSameRole)
	mux.HandleFunc("/ab-reorder", handleABReorder)
	mux.HandleFunc("/duplicate-rows", handleDuplicateRows)
	mux.HandleFunc("/moved-renamed", handleMovedRenamed)
	mux.HandleFunc("/hidden-overlay", handleHiddenOverlay)
	mux.HandleFunc("/animation", handleAnimation)
	mux.HandleFunc("/shadow-dom", handleShadowDOM)
	mux.HandleFunc("/iframes", handleIframes)
	mux.HandleFunc("/i18n", handleI18n)

	ts := httptest.NewServer(mux)
	return &FixtureServer{
		Server: ts,
		URL:    ts.URL,
	}
}

// Close shuts down the fixture server.
func (f *FixtureServer) Close() {
	if f.Server != nil {
		f.Server.Close()
	}
}

func handleIndex(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head><title>Kritix UI Safety Fixture Suite</title></head>
<body>
<h1>Kritix Taxonomy Fixture Suite</h1>
<ul>
  <li><a href="/same-role">Same-Role Different-Action</a></li>
  <li><a href="/ab-reorder">A/B DOM Reorder</a></li>
  <li><a href="/duplicate-rows">Duplicate Row Actions</a></li>
  <li><a href="/moved-renamed">Moved / Renamed Selectors</a></li>
  <li><a href="/hidden-overlay">Hidden / Modal Overlays</a></li>
  <li><a href="/animation">Animation & Transitions</a></li>
  <li><a href="/shadow-dom">Shadow DOM Components</a></li>
  <li><a href="/iframes">iFrame Boundaries</a></li>
  <li><a href="/i18n">Internationalization (i18n)</a></li>
</ul>
</body>
</html>`)
}

func handleSameRole(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head><title>Same-Role Contradictory Actions</title></head>
<body>
  <h2>Order Confirmation</h2>
  <div id="checkout-actions">
    <button id="btn-pay" role="button" class="btn btn-primary" onclick="alert('Paid')">Pay $149.00</button>
    <button id="btn-cancel" role="button" class="btn btn-danger" onclick="alert('Cancelled')">Cancel Order</button>
    <button id="btn-delete" role="button" class="btn btn-outline-danger" onclick="alert('Deleted')">Delete Account</button>
    <button id="btn-save" role="button" class="btn btn-secondary" onclick="alert('Saved')">Save For Later</button>
  </div>
</body>
</html>`)
}

func handleABReorder(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head><title>A/B Reorder Page</title></head>
<body>
  <h2>A/B Swapped Sequence</h2>
  <!-- Swapped position: Cancel is first in DOM, but visually styled -->
  <div class="dialog-footer" style="display: flex; gap: 10px;">
    <button id="btn-dialog-cancel" role="button">Cancel</button>
    <button id="btn-dialog-submit" role="button" class="btn-primary">Confirm & Submit</button>
  </div>
</body>
</html>`)
}

func handleDuplicateRows(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head><title>Duplicate Rows Page</title></head>
<body>
  <h2>Subscription Management</h2>
  <table>
    <tr data-item-id="sub-101">
      <td>Enterprise Cloud Plan ($500/mo)</td>
      <td><button class="btn-cancel" role="button">Cancel Subscription</button></td>
    </tr>
    <tr data-item-id="sub-102">
      <td>Dev Sandbox Tier ($15/mo)</td>
      <td><button class="btn-cancel" role="button">Cancel Subscription</button></td>
    </tr>
    <tr data-item-id="sub-103">
      <td>Production API Addon ($200/mo)</td>
      <td><button class="btn-cancel" role="button">Cancel Subscription</button></td>
    </tr>
  </table>
</body>
</html>`)
}

func handleMovedRenamed(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head><title>Moved / Renamed Selectors</title></head>
<body>
  <h2>Modernized Layout (React 19 migration)</h2>
  <!-- Old: #checkout-box .btn-submit -> New: [data-slot="order-action"] -->
  <div data-testid="modern-checkout-container" data-slot="order-action">
    <button data-testid="checkout-submit-v2" class="cta-primary-modern" role="button">
      Place Order
    </button>
  </div>
</body>
</html>`)
}

func handleHiddenOverlay(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head><title>Hidden / Modal Overlay</title></head>
<body>
  <h2>Account Dashboard</h2>
  <button id="btn-background" role="button">Background Action</button>
  <div id="modal-backdrop" style="position:fixed; top:0; left:0; width:100%; height:100%; background:rgba(0,0,0,0.5); z-index:999;">
    <div style="background:#fff; margin:100px auto; width:300px; padding:20px;">
      <h3>Session Expired</h3>
      <button id="btn-modal-relogin" role="button">Log In Again</button>
    </div>
  </div>
</body>
</html>`)
}

func handleAnimation(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head>
  <title>CSS Animation & Transitions</title>
  <style>
    @keyframes slideIn { from { transform: translateY(-50px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
    .animated-banner { animation: slideIn 0.3s ease-out; }
  </style>
</head>
<body>
  <div class="animated-banner">
    <button id="btn-promo-claim" role="button">Claim 20% Discount</button>
  </div>
</body>
</html>`)
}

func handleShadowDOM(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head><title>Shadow DOM Web Component</title></head>
<body>
  <h2>Web Components Checkout</h2>
  <custom-checkout-card id="checkout-card"></custom-checkout-card>
  <script>
    class CheckoutCard extends HTMLElement {
      connectedCallback() {
        const shadow = this.attachShadow({mode: 'open'});
        shadow.innerHTML = '<div style="padding:10px; border:1px solid #ccc;">' +
                           '<h4>Shadow Checkout</h4>' +
                           '<button id="shadow-btn-submit" role="button">Pay in Shadow Root</button>' +
                           '</div>';
      }
    }
    customElements.define('custom-checkout-card', CheckoutCard);
  </script>
</body>
</html>`)
}

func handleIframes(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head><title>iFrame Boundary Surfaces</title></head>
<body>
  <h2>Third-Party Payment Embed</h2>
  <iframe id="payment-iframe" src="/same-role" width="500" height="300"></iframe>
</body>
</html>`)
}

func handleI18n(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, `<!DOCTYPE html>
<html>
<head><title>i18n Multilingual Actions</title></head>
<body>
  <h2>Multilingual CTAs</h2>
  <div class="i18n-grid">
    <button id="cta-en" lang="en" role="button">Complete Purchase</button>
    <button id="cta-es" lang="es" role="button">Completar compra</button>
    <button id="cta-de" lang="de" role="button">Kauf abschließen</button>
    <button id="cta-fr" lang="fr" role="button">Finaliser l'achat</button>
    <button id="cta-ja" lang="ja" role="button">注文を確定する</button>
  </div>
</body>
</html>`)
}
