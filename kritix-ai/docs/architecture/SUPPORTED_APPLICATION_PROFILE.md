# Supported Application Profile & CDP Boundary Declaration

**Platform**: Kritix AI Enterprise Testing Engine  
**Target Audience**: Principal Test Architects, Lead SDETs, and VP of Engineering  
**Version**: 1.0 (Production Pilot Readiness)  

To ensure complete architectural honesty during enterprise procurement and pilot evaluation, this document formally delineates what Kritix AI natively supports versus what is **strictly out-of-scope** or requires dedicated environment-level instrumentation.

---

## 1. Executive Capability Matrix

| Architecture / Component | Native Support | Out-of-Scope / Requires Instrumentation | Enterprise Remediation Strategy |
|:---|:---:|:---:|:---|
| **Standard HTML5 / DOM** | ✅ Full | — | Native CDP + Accessibility Tree (AXTree) inspection. |
| **Shadow DOM v2 (Web Components)** | ✅ Full | — | Uses Playwright shadow-piercing selectors (`pierce/`, `>>>`). Fully supports Salesforce Lightning and SAP Fiori. |
| **Canvas / WebGL UIs** | ❌ None (Semantic) | ⚠️ Out-of-Scope for Semantic Locators | Canvas elements contain 0 accessibility nodes. Handled via visual screenshot pixel-diff only; no element clicks. |
| **Cross-Origin Payment Iframes** (Stripe Elements, PayPal, Adyen) | ❌ Blocked by Browser Sandbox | ⚠️ Out-of-Scope for Direct DOM Piercing | Use Stripe Test Mode bypass tokens (`tok_visa`) or inject mock payment gateways at the API/network layer. |
| **Bot Protection & CAPTCHAs** (Cloudflare Turnstile, reCAPTCHA v3, hCAPTCHA) | ❌ Blocked | ⚠️ Out-of-Scope for Autonomous Bypasses | Must be bypassed via IP allowlisting, staging test-keys, or disabling Turnstile in staging headers. |
| **Heavily Animated SPAs** (React / Framer Motion / GSAP) | ⚠️ Conditional | Requires Animation Settlement | Built-in `Stabilize()` hook pauses for CSS transition ends and requestAnimationFrame completion before clicking. |
| **Distributed Microservice Rollbacks** (Kafka, RabbitMQ, Outbound Webhooks) | ❌ Not Rollable | ⚠️ Out-of-Scope for Database Rollbacks | Immutable event streams cannot be rolled back. Use synthetic tenant scoping (`X-Test-Tenant-ID`) and mock webhook sinks. |

---

## 2. Technical Deep-Dives on Edge Cases

### A. Canvas & WebGL Data Visualizations (Trading Grids, CAD, Charts)
- **The Reality**: Modern trading applications, TradingView charts, and interactive canvas charts render raw pixels directly into an HTML5 `<canvas>` element. The Chrome DevTools Protocol (CDP) Accessibility Tree inspects the DOM and returns a single node: `<canvas role="img" aria-label="Chart">`. There are no DOM nodes for internal bars, candlesticks, or buttons.
- **Architectural Boundary**: Kritix AI **does not support semantic assertion or clicking on Canvas internal nodes**. Visual regression testing for Canvas is supported solely via baseline screenshot comparison (`expect(page).toHaveScreenshot()`).

### B. Cross-Origin Payment Iframes (Stripe Elements, Adyen, PayPal)
- **The Reality**: To comply with PCI-DSS, payment fields (`#card-number`, `#cvc`) are hosted inside an iframe served from `https://js.stripe.com/`. Browsers strictly enforce the Same-Origin Policy (SOP). The parent page's DOM cannot inspect, click, or read inputs from a cross-origin iframe.
- **Architectural Boundary**: Testing checkout flows containing Stripe Elements requires the staging environment to:
  1. Use Stripe Mock Service Virtualization, or
  2. Inject test payment tokens via network request mocking (`page.route('**/v1/charges', ...)`), or
  3. Pre-fill payment credentials using Stripe's official automated test API keys. Direct autonomous DOM scraping of payment iframes is unsupported by design.

### C. Anti-Bot CAPTCHAs (Cloudflare Turnstile, reCAPTCHA v3)
- **The Reality**: Headless Chromium instances launched in Docker or CI environments exhibit non-standard TLS fingerprints and missing WebGL extensions that trip bot mitigation shields.
- **Architectural Boundary**: Kritix AI will **not** attempt to solve CAPTCHAs autonomously (which violates terms of service and degrades test reliability). Staging environments must allowlist CI runner egress IPs or disable CAPTCHA verification when a signed test header (`X-Kritix-Test-Token`) is present.

### D. Single-Page App (SPA) Animation Latency
- **The Reality**: In modern React or Vue frontends, navigation triggers route transitions, layout morphing, and modal opacity fades. Dispatching a click event during an active CSS transition causes missed clicks or detached element errors.
- **Engine Instrumentation**: Kritix AI includes native animation settlement hooks (`pkg/driver/cdp.go:Stabilize`). The engine verifies that pending microtasks, network requests, and requestAnimationFrames have subsided before dispatching interactions.
