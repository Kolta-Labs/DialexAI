# Project Scope & System Specification — Dialex AI

**Dialex AI** by **Kolta Labs** is a sovereign, cross-platform **Kotlin Multiplatform (KMP)** and **Go Orchestration Engine** multi-agent deliberation and synthetic advisory platform. It facilitates structured, multi-round dialectic debates among competing frontier AI models (**Anthropic Claude 3.7/3.5, OpenAI ChatGPT/Codex/o3, Google Gemini 2.5/2.0/Antigravity, xAI Grok 3, DeepSeek R1/V3, Mistral Large, and local Ollama/CLI agents**) on critical software architecture, systems engineering, cybersecurity, compliance, and strategic executive dilemmas.

---

## 1. Core Vision & Strategic Pillars

### 1. Multi-Model Adversarial Review & Consensus Synthesis
- Eliminates single-model hallucinations, prompt sycophancy, and cognitive bias by orchestrating round-robin peer debates, cross-examinations, and consensus voting across heterogeneous frontier models.
- Designates a Primary Moderator (Seat 1) who frames the dilemma, directs rounds, and synthesizes consensus alongside up to 5 competing peer models.

### 2. $0 Marginal Token Cost via Local Developer CLI Integration
- Connect directly to already-authenticated developer CLI subscriptions (`claude`, `codex`, `antigravity`/`agy`, `ollama`) with zero extra API keys or per-token credit card billing.
- Provides complete offline sovereign execution with local Ollama models (`llama3.3`, `deepseek-r1:32b`, `qwen2.5-coder`).

### 3. Chronological Milestone Deliverables & Generative Artifacts
- Synthesizes actionable, executive-ready outputs at configured round intervals and on consensus:
  - **ADRs (Architecture Decision Records - ADR-042)**: Context, decisions, trade-offs, and consequences.
  - **Weighted Decision Matrices**: Responsive multi-criteria comparison tables.
  - **Pro/Con Trade-Off Breakdowns**: Direct side-by-side risk/reward analysis.
  - **Executive Memorandums (HTML / Markdown)**: High-level strategic digests for C-suite stakeholders.
  - **Code Diffs & Scaffolding**: Ready-to-apply implementation patches.
- All deliverables remain permanently locked in their exact historical positions in the chat transcript.

### 4. Live Human Interjections & Instant Interrupts
- **Steering Queue**: Inject human constraints or answers while models are speaking without interrupting generation.
- **Instant Interrupt**: Immediately halt active model generation, safely finalize partial tokens, and force human guidance as the next turn with zero 409 conflicts.

### 5. 3-Tier Anti-Fluff & Topic Drift Guardrails
- **Human Dialogue Mode**: Enforces concise turns (2–4 sentences) and eliminates sycophantic pleasantries.
- **Topic Drift Guardrail**: Forbids peripheral semantic debates, forcing models to anchor every turn to the core dilemma.
- **3-Tier Cascade**: Master directives in Global Settings &rarr; Project Settings &rarr; Discussion Setup.

### 6. Cross-Platform Sovereign Topology (Desktop & Dialex AI Mobile)
- **Dialex AI Desktop (macOS, Linux, Windows)**: 1-Click native installer packages (`.dmg`, `.msi`, `.AppImage`, `.deb`) with auto-spawned local Go engine child process, fluid-responsive reading widths (680–880dp), and in-memory diagnostics (`AppLogStore`).
- **Dialex AI Mobile (Android)**: Thumb-optimized radial stadium arena with speaker glows, voice dilemma intake, multi-voice Text-to-Speech (TTS), and 1-tap QR pairing with Desktop or remote self-hosted servers.

### 7. Zero-Trust Security & Encrypted Credential Vault
- API keys encrypted at rest with AES-256-GCM using keys derived via Argon2id.
- Android keys anchored in Hardware Security Modules (TEE / StrongBox).
- Subprocess environment sanitization prevents credential leakage to local CLI subshells.
- Tailscale Mesh (`tsnet`) enables encrypted private WireGuard connectivity with zero open router ports.

---

## 2. Target Platforms & Topology Matrix

| Platform Target | Form Factor | Execution Engine | Key Capabilities |
|---|---|---|---|
| **macOS (Desktop)** | Native Desktop App (`.dmg` / `.app`) | Auto-Spawned Local Go Engine / Remote Server | 3-pane IDE workspace, fluid transcripts, sliding deliverable viewer, CLI runner |
| **Windows (Desktop)** | Native Desktop App (`.msi` / `.exe`) | Auto-Spawned Local Go Engine / Remote Server | Native installer, start menu integration, low-memory background engine |
| **Linux (Desktop)** | Native Desktop App (`.AppImage` / `.deb` / `.rpm`) | Auto-Spawned Local Go Engine / Remote Server | Portable double-click AppImage, native desktop integrations, CLI subprocesses |
| **Android (Mobile)** | Native Mobile App (`.apk` / Play Store) | Direct On-Device API / Tailscale MagicDNS / Remote LAN | Radial stadium arena, voice dilemma intake, QR scan pairing, native TTS audio |
| **Headless Server** | Docker Compose / systemd / launchd | Go Orchestration Engine (`:8080`) + Web Admin (`/admin`) | Multi-client SSE broadcast hub, atomic JSON storage, Tailscale `tsnet` mesh |

---

## 3. Target Beneficiaries & Value Matrix

| User Role | Core Workflow in Dialex AI | Primary Benefit & ROI |
|---|---|---|
| 👨‍💻 **Staff & Principal Architects** | Resolving high-consequence trade-offs (Monolith vs Microservices, Rust vs Go, RAG vs Long-Context). | Automated ADR generation; eliminates architectural blind spots before code is written. |
| 👔 **CTOs & Tech Executives** | Stress-testing multi-million-dollar technology investments and team roadmaps. | Executive Memorandums with weighted scoring matrices; avoids expensive rewrites. |
| 🛡️ **Cybersecurity & Red-Teams** | Adversarial threat modeling, zero-trust protocol audits, and zero-day exposure analysis. | Pits offensive hacker personas against defensive architects to uncover vulnerabilities. |
| 🔬 **AI / ML Researchers** | Cross-model consensus validation across competing AI model families. | Eliminates single-model confirmation bias and benchmark hallucinations. |
| ⚖️ **Legal & Compliance Officers** | Auditing multi-jurisdiction compliance regulations (GDPR, HIPAA, SOC2) sovereignly. | 100% on-premise execution with zero data leakage to public third-party SaaS clouds. |
| 🚀 **Startup Founders & PMs** | High-velocity synthetic advisory board for product-market fit and competitive moat analysis. | Instant executive-level advisory council without paying $1,000/hr consultant retainers. |

---

## 📄 License & Legal Notice

Dialex AI is licensed under the [PolyForm Noncommercial License 1.0.0](file:///LICENSE).  
Copyright (c) 2026 Kolta Labs. Free for personal, academic, and noncommercial research. Commercial deployments require an enterprise license from Kolta Labs.
