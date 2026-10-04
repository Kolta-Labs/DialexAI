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

## 4. Benchmark & Metric Verifiability & Unmeasured Boundaries

- Hardcoded marketing metrics are prohibited across the codebase.
- Metrics in `benchmark.json` and scorecards are derived from verifiable harness runs against `testdata/regressions/` and live containers when available.
- **Unmeasured Metrics in Corpus Mode**:
  - `p50_latency_seconds` / `p95_latency_seconds` / `wall_clock_ci_seconds`: Marked `"not measured"` in corpus mode. True CI wall-clock requires containerized multi-app runs (`Medusa`, `TodoMVC`) with cold Chrome CDP instances.
  - `local_model_token_count_avg` / `api_model_token_count_avg`: Marked `"not measured"` unless real model calls are executed via Ollama or authorized API keys.
- **Harness Prerequisite Fail-Closed Gate**: If any required infrastructure tool (`docker`, `chrome`, `postgres`, `vault`, `k6`) is absent, the harness exits non-zero with `PREREQUISITE_MISSING: <tool>` rather than silently substituting emulated measurements.
