# Dialex Suite

**By Kolta Labs.** Tools that make AI disagree with itself before you trust it.

Dialex Suite is three products on one deliberation engine:

| Product | Folder | What it is | Stack |
|---|---|---|---|
| **Dialex AI** | [`dialex-ai/`](./dialex-ai) | Desktop and Android app where several AI models debate a decision and a moderator synthesizes the result. | Kotlin Multiplatform, Compose |
| **Kritix AI** | [`kritix-ai/`](./kritix-ai) | Adversarial review and spec-to-patch workflow for AI-written code (CLI and desktop cockpit). | Go, Compose Desktop |
| **Dialex Engine** | [`dialex-engine/`](./dialex-engine) | The shared deliberation daemon: consensus, orchestration, mesh networking, model runners. | Go 1.27 |

Start with Dialex AI for decisions, or Kritix AI for code review. The engine is a dependency of both.

---

## Architecture & Directory Layout

```text
DialexAI/
├── dialex-engine/         # Core Go Engine & Orchestration Daemon
│   ├── cmd/               # Daemon binaries (dialex, dialexd)
│   ├── pkg/               # Engine packages (consensus, orchestrator, socratic, etc.)
│   └── go.mod             # Go module: dialex
│
├── dialex-ai/             # Kotlin Multiplatform Client Application
│   ├── shared/            # Common business logic, MVI architecture & UI components
│   ├── desktopApp/        # Compose Multiplatform Desktop client (macOS/Win/Linux)
│   ├── androidApp/        # Compose Android mobile client
│   └── build.gradle.kts   # Independent Gradle build & wrapper
│
├── kritix-ai/             # Kritix Autonomous Engineering Platform
│   ├── cli/               # kritix CLI frontend (spec, code, review, steering)
│   ├── cmd/               # kritixd remote server daemon & webhooks
│   ├── pkg/               # Core packages (coder, reviewer, spec, forge, steering)
│   ├── app/               # Standalone Compose Desktop Cockpit application
│   └── build.gradle.kts   # Independent Gradle build & wrapper
│
├── go.work                # Go Workspace uniting dialex-engine and kritix-ai
└── .github/workflows/     # Unified CI/CD pipelines
```

---

## Quick Start

### 1. Dialex Engine (Go Daemon)
```bash
cd dialex-engine
go test ./pkg/...
go run ./cmd/dialex
```

### 2. Dialex AI (Compose Desktop & Android)
```bash
cd dialex-ai
# Run Desktop Application
./gradlew :desktopApp:run

# Compile Android Client
./gradlew :androidApp:assembleDebug
```

### 3. Kritix AI (CLI & Desktop Cockpit)
```bash
cd kritix-ai
# Build CLI binary
go build -o bin/kritix ./cli/main.go
./bin/kritix help

# Run Kritix Cockpit Desktop App
./gradlew :app:run
```

---

## Status & Limitations

Run the tests yourself:

```bash
(cd dialex-engine && go test ./pkg/...)
(cd kritix-ai     && go test ./pkg/...)
(cd dialex-ai     && ./gradlew :shared:allTests)   # includes real-engine integration tests
```

**Feature maturity**

| Tier | Features |
|---|---|
| **Core** | Council deliberation, moderator synthesis, live steering, deliverables (ADR, decision matrix, memo) |
| **Experimental** | Knowledge graph, problem decomposition, contradiction detection, dynamic retrieval, Socratic mode, benchmarking harness, persona DNA, mobile parity, Bayesian credence |
| **Planned** | Autonomous artifact sandbox |

**Known limitations**

| Area | Status |
|---|---|
| Debate vs single model | A benchmark harness and a 10-case set (`Dialex-Bench-10`) exist in `dialex-engine/pkg/benchmark`. **No results are published yet.** |
| Kritix `plan` and review | `plan` is template-based and the reviewer is rule-based; neither calls a model. See [kritix-ai/README.md](kritix-ai/README.md). `kritix code` writes patches with a model only when you pass `--provider` and `--model`. |
| Kritix execution isolation | Process isolation only, **not** a security sandbox. Do not run it on untrusted repositories. |
| API key storage | AES-256-GCM with a key file on disk; no OS keychain yet. |
| Third-party CLI runners | Driving vendor CLIs (`claude`, `codex`, ...) depends on each vendor's terms and may break. Direct API keys or Ollama are the stable path. |

## Contributing

We want real contributors on scoped pieces of work (docs, a runner, a platform port, benchmark cases). See [CONTRIBUTING.md](CONTRIBUTING.md). Contributors are credited for what they build.

---

## Development & Engineering Standards

All modules adhere to the [Kolta KMP & Go Engineering Standards](https://github.com/Kolta-Labs/Kolt). Personas, agents, and tooling are governed by rules symlinked in `.standards/`.
