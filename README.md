# Dialex Monorepo

Welcome to the **Dialex & Kritix** ecosystem. This monorepo is structured into three dedicated sister folders:

| Folder | Name | Technology | Description |
|---|---|---|---|
| [`dialex-engine/`](./dialex-engine) | **Dialex Engine** | Go 1.27 | High-performance deliberation daemon, peer-to-peer mesh networking, consensus engine, and LLM runner. |
| [`dialex-ai/`](./dialex-ai) | **Dialex AI** | Kotlin Multiplatform & Compose | Sovereign multi-AI deliberation client application for Desktop (macOS, Windows, Linux) and Android. |
| [`kritix-ai/`](./kritix-ai) | **Kritix AI** | Go & Compose Desktop | Dialectic software engineering platform, autonomous coding cockpit, adversarial reviewer, and story spec generator. |

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

## Development & Engineering Standards

All modules adhere to the [Kolta KMP & Go Engineering Standards](https://github.com/Kolta-Labs/Kolt). Personas, agents, and tooling are governed by rules symlinked in `.standards/`.
