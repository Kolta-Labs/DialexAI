# Feature 03: Explicit Contradiction & Paraconsistent Tension Pair Detection Engine

> **Component Scope**:
> - **Go Engine**: `engine/pkg/consensus/detector.go`, `engine/pkg/model/tension.go`, `engine/pkg/orchestrator/orchestrator.go`, round analysis pass.
> - **KMP Shared Layer**: `shared/.../domain/model/TensionModels.kt`, `Discussion.kt` state persistence, SSE `tension_update` ingestion.
> - **UI / Presentation**: `TensionMatrixDrawer.kt`, `ChatContract.kt`, `ChatViewModel.kt`, `ChatScreen.kt` top-bar tension badge.
> - **Tests**: `engine/pkg/consensus/detector_test.go`, `engine/pkg/orchestrator/orchestrator_test.go`, `shared/.../presentation/chat/TensionDetectionTest.kt`.

---

## 1. Overview & Theoretical Motivation

Standard multi-agent frameworks often collapse into two dialectical pathologies:
1. **Premature Sycophancy / Groupthink**: Agents quickly converge on superficial agreement without interrogating foundational trade-offs.
2. **Explosion into Incoherence (Principle of Explosion / *Ex Falso Quodlibet*)**: In classical logic, a contradiction ($A \land \neg A$) invalidates the entire deductive system ($A \land \neg A \vdash B$), causing downstream agents to hallucinate or talk past each other.

**Feature 03** implements **Paraconsistent Dialectic Tension Pair Detection** based on paraconsistent logic systems ($C_n$ formalisms):
- **Contradictions as High-Value Assets**: Rejects the Principle of Explosion. Opposing agent assertions are treated as valuable, localized dialectic axes rather than fatal logical flaws.
- **Explicit Thesis-Antithesis Pairing**: Automatically isolates cross-agent clashes (e.g. strict consistency vs low latency; immutable event streams vs GDPR deletion SLAs).
- **Dialectic Lifecycle Tracking**: Monitors whether a contradiction is `OPEN`, actively `EXPLORED`, synthesized into an integrative solution (`RESOLVED`), or formally acknowledged as a deliberate compromise (`ACCEPTED_TRADE_OFF`).
- **Interactive Tension Matrix Drawer**: Real-time slide-out UI in Compose Multiplatform allowing users to inspect clashing quotes, compare model stances, track severity scores ($0.0$ to $1.0$), and monitor dialectical resolution.

---

## 2. Architecture & Lifecycle Flow

```mermaid
flowchart TD
    subgraph Multi-Agent Round Execution
        ORCH[orchestrator.go<br/>DebateOrchestrator] -->|Round N Turns Complete| TD[consensus/detector.go<br/>TensionDetector]
        TD -->|LLM Paraconsistent Prompt / Fallback| TP[AnalyzeRound]
    end

    subgraph Tension Resolution & State
        TP -->|New Contradictions & Updates| DISC[Discussion.tensions<br/>[]TensionPair]
        ORCH -->|SSE Event: tension_update| KMP[EngineClient SSE Receiver]
    end

    subgraph KMP UI Layer
        KMP -->|ChatIntent.TensionUpdateReceived| CVM[ChatViewModel]
        CVM -->|Update State| CS[ChatScreen.kt<br/>Tension Header Badge]
        CS -->|Click Badge| TMD[TensionMatrixDrawer.kt<br/>Slide-Out Inspector]
    end
```

---

## 3. Go Engine Implementation

### 3.1 Data Structures (`engine/pkg/model/tension.go`)
- **`TensionStatus`**: Lifecycle states:
  - `TensionStatusOpen`: Newly detected contradiction awaiting exploration.
  - `TensionStatusExplored`: Addressed by agents but unresolved.
  - `TensionStatusResolved`: Reconciled via higher-order architectural synthesis.
  - `TensionStatusAcceptedTradeOff`: Deliberate acceptance of one constraint over another with explicit rationale.
- **`AgentAssertion`**:
  - `SeatID`, `Provider`, `AuthorDisplayName`: Attribution of the speaking agent.
  - `Statement`: Concise distilled assertion.
  - `Quote`: Verbatim citation from the round's transcript.
  - `Round`: Round index where the assertion was formulated.
- **`TensionPair`**:
  - `ID`: Unique hex identifier.
  - `Thesis`: Affirmative `AgentAssertion`.
  - `Antithesis`: Directly opposing `AgentAssertion` from a peer agent.
  - `UnderlyingConflict`: 3-6 word label of the conflict axis (e.g. *ACID Quorum vs. Write Latency SLA*).
  - `Synthesis`: Explanation of reconciliation (populated when `RESOLVED`).
  - `TradeOffRationale`: Explicit reasoning (populated when `ACCEPTED_TRADE_OFF`).
  - `Severity`: Float in `[0.0, 1.0]` representing whether the clash is minor nuance or architectural deadlock.
  - `DetectedInRound`, `ResolvedInRound`.

### 3.2 Detection Engine (`engine/pkg/consensus/detector.go`)
1. **End-of-Round Dialectical Pass**:
   - Executes after all participating agents in a round have spoken.
   - Filters out system messages and user comments to isolate peer-to-peer clashes.
   - Formulates a structured analysis prompt presenting both the existing open tensions and the new transcript turns.
2. **Analysis Prompt & Paraconsistent Guidelines**:
   - Instructs the model to isolate exact contradictory assertions between different agents.
   - Requires identification of any synthesis formulated in subsequent turns or agreement to an accepted trade-off.
3. **Deterministic Heuristic Fallback (`HeuristicAnalyzeRound`)**:
   - Operates when running offline or without an active compaction runner.
   - Scans for known dialectical polarity markers (*"however"*, *"disagree"*, *"trade-off"*, *"latency penalty"*, *"cannot compromise on"*).
   - Generates deterministic `TensionPair` objects with calculated severity metrics.

### 3.3 Orchestrator Integration (`engine/pkg/orchestrator/orchestrator.go`)
- In `RunDebate()`, after consensus evaluation for round $R$, `TensionDetector.AnalyzeRound` executes.
- Emits real-time SSE event:
  ```json
  event: tension_update
  data: [{"id":"t-9a1b","underlyingConflict":"Locking vs Eventual Consistency","status":"OPEN","severity":0.85,...}]
  ```
- Appends updated tension list to the persisted `Discussion` record.

---

## 4. KMP Shared & MVI Presentation

### 4.1 Clean Architecture & Domain State
1. **Domain Layer (`com.dialex.domain.model.TensionModels.kt`)**:
   - Pure Kotlin data classes: `TensionPair`, `AgentAssertion`, `TensionStatus`, `TensionFilter`.
   - Utility extensions: `List<TensionPair>.hasOpenTensions()`, `openCount()`, `resolvedCount()`.
2. **Presentation Layer (`com.dialex.presentation.chat`)**:
   - `ChatContract.kt`:
     - State fields: `tensions: ImmutableList<TensionPair>`, `tensionFilter: TensionFilter`, `showTensionDrawer: Boolean`.
     - Intents: `OpenTensionDrawer`, `CloseTensionDrawer`, `SelectTensionFilter(filter)`.
   - `ChatViewModel.kt`:
     - Updates `tensions` atomically as new SSE events arrive.
     - Maintains count of open vs resolved tensions.

### 4.2 Tension Matrix Drawer UI (`TensionMatrixDrawer.kt`)
- **Top Bar Badge**: Displays pulse dot and active count (e.g. `⚡ 2 Tensions`) in `ChatScreen.kt` header.
- **Filter Tabs**: Filter by `ALL`, `OPEN ONLY`, `RESOLVED`, or `TRADE-OFFS`.
- **Severity Pill**: Dynamic color-coded indicator ($<0.4$ Gray/Subtle, $0.4-0.7$ Amber/Moderate, $>0.7$ Red/Critical).
- **Dialectical Clash Card**:
  - Split thesis vs antithesis display with agent avatars, display names, and direct italicized quotes.
  - Synthesis / Trade-off callout box detailing how the council reconciled the clash or documented the intentional compromise.
  - Round badges indicating origin and resolution milestones.

---

## 5. Verification & Test Coverage

- **Go Engine Test Suite**:
  - `engine/pkg/consensus/detector_test.go`:
    - `TestTensionDetector_HeuristicAnalyzeRound`: Validates deterministic pair generation from polarized messages.
    - `TestTensionDetector_MergeTensionResults`: Validates status updates, synthesis attribution, and ID preservation.
    - `TestHasOpenTensions_And_OpenCount`: Verifies status counter predicates.
  - `engine/pkg/orchestrator/orchestrator_test.go`:
    - End-to-end integration test verifying that `TensionDetector` triggers on multi-turn rounds and populates `discussion.Tensions`.
- **KMP Shared & UI Tests**:
  - `TensionDetectionTest.kt`:
    - Tests `ChatViewModel` tension list updates and filter mutations.
    - Verifies open count computation and drawer visibility toggles.
