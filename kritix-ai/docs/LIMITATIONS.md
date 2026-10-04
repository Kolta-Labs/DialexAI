# Kritix AI - Capabilities, Boundaries & Limitations

This document explicitly defines what Kritix AI supports, its runtime boundaries, and intentional out-of-scope technical constraints.

---

## 1. Browser & Surface Coverage

| Surface / Technology | Support Level | Behavior & Handling |
|---|---|---|
| **Standard DOM / CSS / HTML5** | Full | Headless Chrome via CDP (`chromedp`), full AXTree extraction, layout stabilization |
| **Open Shadow DOM** | Full | Piercing selector evaluation and flattened AXTree hierarchy |
| **Same-Origin iframes** | Full | Frame tree traversal and child document context binding |
| **Canvas / WebGL** | Partial (Visual Only) | Pixel screenshot diffing only. DOM interactions inside `<canvas>` emit `UNSUPPORTED_SURFACE`. |
| **Cross-Origin Payment iframes (Stripe Elements, PayPal)** | Guarded | Form inputs inside cross-origin iframes cannot be pierced; emitted as `UNSUPPORTED_SURFACE`. Use test API keys or stubbed endpoints. |
| **CAPTCHA / Cloudflare Turnstile** | Detected / Fail-Closed | Explicitly detected and emitted as `UNSUPPORTED_SURFACE`. Kritix does not bypass bot protection. |

---

## 2. Environment Rollback & State Isolation

`SandboxEnvironment` does not snapshot or restore any database, container or service. `MarkCheckpoint`/`ResetInterceptedState` only discard the webhooks the sandbox intercepted in-process. Isolation relies on per-run tenant IDs and WireMock/Pact stubs.

### Supported Scope (1.0 Fidelity):
- Single-service and isolated Docker Compose database containers with SQL schema resets.
- In-memory mock servers and WireMock/Pact stub fixtures.
- Local filesystem temp directories and git worktrees.

### Explicitly Out-of-Scope (Not Rolled Back):
- Distributed Kafka / RabbitMQ topics and append-only event streams.
- External 3rd-party SaaS webhooks and live sandboxes (Stripe live mode, Salesforce CRM, Segment).
- Multi-service distributed saga transactions across heterogeneous datastores.

---

## 3. Pipeline Tiers & PR Merge Gate Invariants

- **Tier 1 (`pr-smoke-guard`)**: Deterministic-only execution (`<= 5 min` SLA). Prohibits blocking LLM calls. Runs impacted tests based on git diff impact map.
- **Tier 2 (`merge-gate`)**: Multi-viewport matrix runs and analytics tag verification. `PASSED_WITH_HEALING` blocks merge by default unless explicitly overridden.
- **Tier 3 (`nightly-deep-audit`, `ticket-to-ship`)**: Exploratory vision crawls, OWASP DAST scans, and Socratic interrogation. Must not block fast PR gates.

---

## 4. Benchmark & Metric Verifiability

- Hardcoded marketing metrics are prohibited across the codebase.
- All printed metrics are derived from executions against the 105-case mutation corpus in `testdata/regressions/` and committed to `benchmark.json`.
