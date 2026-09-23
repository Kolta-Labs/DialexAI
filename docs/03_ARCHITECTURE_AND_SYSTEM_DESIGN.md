# System Topology & System Design — Dialex AI

Dialex AI is built upon **Clean Architecture**, **unidirectional Model-View-Intent (MVI)** presentation patterns, and a robust decoupled multiplatform topology pairing a high-throughput **Go Orchestration Engine** with a **Kotlin Multiplatform (KMP) & Compose Multiplatform** client layer powered by the **Kolt Ecosystem (`KoltLibs`)**.

---

## 1. System Topology Overview

```mermaid
flowchart TD
    subgraph UI ["Presentation Layer (Compose Multiplatform)"]
        Sidebar["WorkspaceSidebar"]
        Setup["SetupScreen / SetupViewModel"]
        Chat["ChatScreen / ChatViewModel"]
        Settings["SettingsScreen / SettingsViewModel"]
        MobileHub["Dialex AI Mobile (Stadium Arena / Voice / QR)"]
    end

    subgraph KoltLibs ["Kolt Framework (io.github.koltsystems.koltx)"]
        ComposeKmp["compose-kmp (CcPalette, LocalCcColors, ThemeMode)"]
        Utils["utils (AsyncState Monad, Coroutine Scope Utils)"]
        LogUtils["logutils (Release-Gated Structured Logging)"]
    end

    subgraph Domain ["Domain Layer (Pure Platform-Agnostic Kotlin)"]
        ProjectsRepo["ProjectRepository (Interface)"]
        DiscussionRepo["DiscussionRepository (Interface)"]
        PersonaRepo["PersonaRepository (Interface)"]
        SettingsRepo["SettingsRepository (Interface)"]
        UseCases["Deliberation, Persona & Pairing UseCases"]
    end

    subgraph Data ["Data Layer (KMP)"]
        EngineDS["EngineDataSource (DomainException Mapper)"]
        KtorClient["EngineClient (Ktor HTTP + SSE Flow)"]
        LocalStore["Encrypted Vault & Direct API Runners"]
        AppLogStore["AppLogStore (Diagnostics Ring Buffer)"]
    end

    subgraph Backend ["Go Orchestration Engine (:8080 / Embedded)"]
        HttpServer["Engine HTTP REST & SSE Server"]
        Orchestrator["Turn State Machine & Turn Scheduler"]
        ConsensusEngine["Consensus Evaluator & Deliverable Synthesizer"]
        TsnetMesh["Embedded Tailscale Mesh (tsnet)"]
        CliRunner["Local CLI Runner (claude, codex, agy, ollama)"]
        ApiRunner["Direct Cloud API Runner (Anthropic, OpenAI, Gemini, etc.)"]
        VaultStore["Argon2id + AES-256 Encrypted JSON Vault"]
    end

    UI --> KoltLibs
    UI --> Domain
    Domain --> Data
    Data --> KoltLibs
    EngineDS --> KtorClient
    KtorClient --> HttpServer
    MobileHub -.->|1-Tap QR / Tailscale MagicDNS| TsnetMesh
    TsnetMesh --> HttpServer
    HttpServer --> Orchestrator
    Orchestrator --> ConsensusEngine
    Orchestrator --> CliRunner
    Orchestrator --> ApiRunner
    Orchestrator --> VaultStore
```

---

## 2. Layering & Architectural Invariants

### A. Presentation Layer (MVI in Compose Multiplatform)
- **State**: Single immutable data class (`ChatState`) per screen utilizing `kotlinx.collections.immutable` (`ImmutableList`, `ImmutableMap`) to avoid unnecessary Compose recompositions across streaming feeds.
- **Intent**: Sealed interface (`ChatIntent`) encapsulating all user interactions and gestures.
- **Effect**: One-shot side-effects (navigation routes, file exports, snackbars) delivered via `Channel` and collected solely by Route composables.
- **Stateless Root Composable**: Receives only `state`, `onIntent: (Intent) -> Unit`, and navigation callbacks — zero ViewModel references for 100% `@Preview` testability.

### B. The Kolt Ecosystem Foundation (`KoltLibs`)
Dialex AI relies on `KoltLibs` via a Gradle Composite Build (`includeBuild("../KoltLibs")`):
- **`compose-kmp`**: Provides the Obsidian Nebula theme system (`CcPalette`, `LocalCcColors`), elevation tokens, button styles, and fluid typography.
- **`utils`**: Supplies `AsyncState<T>` (`Uninitialized`, `Loading`, `Success`, `Error`) reactive state modeling.
- **`logutils`**: Release-gated logging and telemetry ring buffers.

### C. Domain Layer (Pure Kotlin)
- Zero platform dependencies (`java.*`, `android.*`, `androidx.compose.*`).
- Encapsulates core business entities: `Project`, `Discussion`, `DiscussionMessage`, `DiscussionArtifact`, `PersonaConfig`, and `ExecutionPolicy`.
- Strict single-direction dependency rule: `UseCase` depends only on `Repository` interfaces; no peer `UseCase` cross-invocations.

### D. Data Layer (Transport & Mapping)
- `EngineDataSource`: Maps low-level transport errors (HTTP 4xx/5xx, connection timeouts, parsing bugs) into typed `DomainException` models (`NetworkUnavailable`, `AuthenticationRequired`, `ExecutionError`).
- `EngineClient`: Ktor-powered HTTP client streaming Server-Sent Events (SSE) flows directly into domain repositories.

### E. Go Orchestration Engine
- **Turn State Machine**: Thread-safe state engine coordinating model turns, loop detection, and prompt context compaction.
- **In-Stream Milestone Deliverables**: Synthesizes checkpoint ADRs and patches, timestamping each artifact and associating it with its generation round.
- **Live Interrupt Channel**: Mutex-locked FIFO queue ensuring human steering messages take immediate precedence before the next turn starts.
- **Embedded Tailscale (`tsnet`)**: Advertises the engine onto private Tailscale tailnets without requiring local root privileges or manual port forwarding.

---

## 3. Sequence: Deliberation Turn, Streaming & Consensus Deliverable

```mermaid
sequenceDiagram
    autonumber
    actor User as Human Moderator
    participant ChatScreen as ChatScreen (Compose UI)
    participant ChatVM as ChatViewModel (MVI)
    participant EngineDS as EngineDataSource
    participant GoEngine as Go Orchestration Engine
    participant ModelProvider as AI Model (Claude / OpenAI / Gemini / Ollama)

    User->>ChatScreen: Click "Start Deliberation" or Send Interrupt
    ChatScreen->>ChatVM: onIntent(ChatIntent.StartDiscussion)
    ChatVM->>EngineDS: startDiscussion(config)
    EngineDS->>GoEngine: POST /api/debates/{id}/start
    GoEngine->>ModelProvider: Stream Prompt (Persona + Context + Directives)
    ModelProvider-->>GoEngine: Stream Token Chunks
    GoEngine-->>EngineDS: SSE: agent_token_chunk (delta)
    EngineDS-->>ChatVM: Flow<DeliberationEvent>
    ChatVM-->>ChatScreen: Update State (messages, active speaker glow, elapsed timer)

    Note over GoEngine: Consensus Evaluator triggers (e.g. 94% agreement)
    GoEngine->>ModelProvider: Synthesize Consensus Outcome & Deliverables (ADR / Matrix)
    ModelProvider-->>GoEngine: Synthesized Markdown Deliverable
    GoEngine-->>EngineDS: SSE: deliverable_generated (timestampMs, format, content)
    EngineDS-->>ChatVM: Update Discussion Artifacts
    ChatVM-->>ChatScreen: Render Consensus Outcome Bubble & Actionable Deliverable Tabs
```

---

## 📄 License

Dialex AI is licensed under the [PolyForm Noncommercial License 1.0.0](file:///LICENSE).  
Copyright (c) 2026 Kolta Labs. Free for personal, academic, and noncommercial research. Commercial deployments require an enterprise license from Kolta Labs.
