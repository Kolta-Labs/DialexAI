# Feature 02: Pre-Debate Multi-Perspective Problem Decomposition & Chat Resilience

> **Component Scope**:
> - **Go Engine**: `engine/pkg/decomposition/`, `engine/pkg/api/decomposition_handlers.go`, REST `/api/v1/discussions/decompose`, fallback heuristics.
> - **KMP Shared Layer**: `shared/.../domain/model/DecompositionModels.kt`, `DecompositionRepository.kt`, `DecomposeProblemUseCase.kt`, `DecompositionRepositoryImpl.kt`, `EngineDataSource.kt`, `EngineClient.kt`.
> - **UI / Presentation**: `DecompositionModal.kt`, `SetupContract.kt`, `SetupViewModel.kt`, `SetupScreen.kt`, and `ChatScreen.kt` / `ChatViewModel.kt` resilience polishing.
> - **Tests**: `engine/pkg/decomposition/engine_test.go`, `engine/pkg/api/decomposition_handlers_test.go`, `shared/.../presentation/setup/ProblemDecompositionTest.kt`, `shared/.../presentation/chat/ChatChronologyTest.kt`.

---

## 1. Overview & Motivation

When initiating multi-agent dialectical deliberation on complex, multifaceted engineering or product dilemmas (e.g. *"Migrating monolith to event-driven microservices"* or *"Choosing Rust vs. Go for high-throughput edge proxies"*), discussions often suffer from:
1. **Unfocused Rambling**: Agents talking past each other without addressing root architectural tradeoffs.
2. **Cognitive Blind Spots**: Over-indexing on either purely low-level technical guarantees (invariants, latency) or high-level organizational speed (time-to-market, dev ergonomics) at the exclusion of the other.
3. **Vague Agendas**: Lack of explicit thesis-antithesis boundaries prior to round one.

**Feature 02** introduces **Pre-Debate Multi-Perspective Problem Decomposition**:
- Automatically deconstructs any topic into **Perspective A (Technical & Structural Architecture)** and **Perspective B (Product & Strategic Velocity)** before the debate starts.
- Identifies orthogonal sub-axes with explicit **Thesis**, **Antithesis**, **Probing Key Dilemma Questions**, and **Criticality Weights** ($0.1$ to $1.0$).
- Provides an interactive Compose Multiplatform modal (`DecompositionModal.kt`) enabling users to audit, toggle, and one-click inject selected axes into the discussion's shared agenda context.
- Hardens **Chat Resilience & Chronology**: Ensures clean snapshotting of completed rounds, prevents state collision on follow-up user intervention, and guarantees non-blocking stream synchronization.

---

## 2. Architecture & Data Flow

```mermaid
flowchart TD
    subgraph Setup Screen UI
        SS[SetupScreen.kt] -->|Decompose Topic Click| SVM[SetupViewModel]
        SVM -->|RequestProblemDecomposition| DUC[DecomposeProblemUseCase]
        DM[DecompositionModal.kt] -->|Toggle Axis / Apply Agenda| SVM
    end

    subgraph KMP Clean Architecture Layer
        DUC --> DR[DecompositionRepositoryImpl]
        DR --> EDS[EngineDataSource]
        EDS --> EC[EngineClient - Ktor HTTP]
    end

    subgraph Go Engine (localhost:7432)
        EC -->|POST /api/v1/discussions/decompose| DH[decomposition_handlers.go]
        DH --> DE[decomposition/engine.go<br/>DecomposeProblem]
        DE -->|Prompt Execution| AR[runner.AgentRunner<br/>Claude / OpenAI / Ollama]
        DE -.->|Offline / Parsing Fallback| HD[GenerateHeuristicDecomposition]
    end

    subgraph Debate Seeding
        SVM -->|ApplyDecompositionToAgenda| DISC[Discussion.config.commonContext<br/>### 🎯 Structured Debate Agenda]
    end
```

---

## 3. Go Engine Implementation

### 3.1 Domain Models (`engine/pkg/decomposition/decomposition.go`)
- **`ProblemAxis`**: Represents one orthogonal dimension of dialectical contention.
  - `ID`: Unique slug identifier (e.g. `tech_correctness_latency`).
  - `Title`: Concise title (e.g. `State Consistency & SLA Boundaries`).
  - `Thesis`: Affirmative argument/requirement.
  - `Antithesis`: Countervailing technical/operational constraint.
  - `KeyQuestions`: Array of probing questions that agents must resolve.
  - `Weight`: Relative criticality float in `[0.1, 1.0]`.
  - `Selected`: Initial inclusion boolean (defaults to `true`).
- **`Perspective`**: Cognitive lens containing grouped axes.
  - `PerspectiveA`: Technical / Structural Architecture (Invariants, Latency/Throughput SLAs, State Consistency, Fault Isolation).
  - `PerspectiveB`: Product / Strategic / Operational Velocity (Developer Ergonomics, Time-to-Market, Migration Blast Radius, TCO).
- **`DecompositionResult`**: Root payload containing the topic, `PerspectiveA`, and `PerspectiveB`.

### 3.2 Decomposition Engine & Dual Execution Pipeline (`engine/pkg/decomposition/engine.go`)
1. **LLM Orchestration**:
   - Executes a structured dialectical system prompt instructing the model to act as a Principal Systems Architect.
   - Demands strict JSON schema output containing orthogonal axes with thesis/antithesis pairs and questions.
   - Robust JSON extraction via regex (`(?s)\{.*"perspectiveA".*"perspectiveB".*\}`) and markdown fence stripping.
2. **Deterministic Heuristic Fallback Engine**:
   - If the LLM provider is offline, unconfigured, or returns an unparseable response, `GenerateHeuristicDecomposition()` immediately executes without blocking the user.
   - Features domain-specific pattern recognition for topics relating to databases/SQL/NoSQL (injecting ACID vs Read/Write Throughput, Data Migration cutover) and systems languages/rewrites (injecting Memory Safety vs Ecosystem Ramp-Up Curve).

### 3.3 HTTP API (`engine/pkg/api/decomposition_handlers.go`)
- **Endpoint**: `POST /api/v1/discussions/decompose`
- **Request**:
  ```json
  {
    "topic": "Migrating payment processing from PostgreSQL to distributed Cassandra",
    "context": "Needs 99.999% availability with financial audit invariants",
    "provider": "anthropic",
    "model": "claude-3-7-sonnet"
  }
  ```
- Automatically resolves configured API keys or CLI runners from `AppState` when provider/model are omitted.

---

## 4. KMP Shared & MVI Presentation

### 4.1 Clean Architecture Hierarchy
1. **Domain Layer**:
   - `com.dialex.domain.model.ProblemDecomposition`, `DecompositionPerspective`, `ProblemAxis`.
   - `com.dialex.domain.repository.DecompositionRepository`: Interface with `decomposeProblem(topic, context, model, provider): ProblemDecomposition`.
   - `com.dialex.domain.usecase.DecomposeProblemUseCase`: Enforces non-empty topic validation.
2. **Data Layer**:
   - `EngineDataSource.decomposeProblem()`: Dispatches POST request via `EngineClient` and converts HTTP/Ktor network exceptions to `DomainException.EngineUnavailable` or `DomainException.ValidationFailed`.
   - `DecompositionRepositoryImpl`: Bridges DataSource to Domain.
3. **Presentation Layer (`com.dialex.presentation.setup`)**:
   - `SetupContract.kt`:
     - Intention: `RequestProblemDecomposition`, `ToggleDecompositionAxis`, `SelectAllPerspectiveA`, `SelectAllPerspectiveB`, `ApplyDecompositionToAgenda`, `DismissDecompositionSheet`.
     - State: `activeDecomposition`, `isDecomposing`, `showDecompositionSheet`, `selectedAxisIds`.
     - Effect: `DecompositionApplied`, `ShowSnackbar`.
   - `DecompositionModal.kt`:
     - Modal bottom sheet / dialog displaying perspective tabs (Technical vs Product).
     - Visual badge indicators for axis weights (`High Priority`, `Medium`, `Standard`).
     - Expandable dilemma card showing *Thesis*, *Antithesis*, and bulleted *Core Questions*.
     - One-click bulk selection toggles per perspective and "Apply to Debate Agenda" action.

### 4.2 Agenda Seeding Logic (`SetupViewModel.kt`)
When axes are applied, `SetupViewModel` formats them into an explicit markdown section:
```markdown
### 🎯 Structured Debate Agenda: Orthogonal Problem Axes
The council must explicitly address and resolve the following orthogonal tensions:
1. **State Consistency & SLA Boundaries** (Weight: 0.9)
   - *Thesis*: Enforce strict transactional invariants and zero data corruption boundaries.
   - *Antithesis*: Decouple synchronous dependencies to preserve ultra-low latency (<20ms P99).
   - *Core Questions*: Can we accept eventual consistency?; What is the blast radius?
```
This is idempotently injected into `discussion.config.commonContext` so all participating AI agents receive the structured tensions in their prompt context from Turn 1.

---

## 5. Verification & Test Coverage

- **Go Engine Test Suite**:
  - `engine/pkg/decomposition/engine_test.go`:
    - `TestGenerateHeuristicDecomposition_GeneratesExpectedAxes`: Validates presence of minimum 3 axes per perspective and default weights.
    - `TestGenerateHeuristicDecomposition_TopicSpecialization`: Validates specialized keyword tuning for databases and language rewrites.
    - `TestParseDecompositionJSON_ValidPayload`: Tests JSON parsing, fence cleaning, and fallback defaulting.
  - `engine/pkg/api/decomposition_handlers_test.go`:
    - Validates POST endpoint with both valid payloads and empty topics (HTTP 400).
- **KMP Shared & UI Tests**:
  - `ProblemDecompositionTest.kt`:
    - Tests `DecomposeProblemUseCase` execution and error propagation.
    - Tests `SetupViewModel` intent handling: axis selection toggle, select all A/B, and agenda formatting into `commonContext`.
  - `ChatChronologyTest.kt`:
    - Tests multi-round snapshotting and state continuation when debates receive follow-up user comments.
