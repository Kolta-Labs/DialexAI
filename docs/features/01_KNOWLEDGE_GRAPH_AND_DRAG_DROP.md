# Feature 01: Self-Organizing Deliberation Knowledge Graph & Fluid Discussion Drag-and-Drop

> **Component Scope**:
> - **Go Engine**: `engine/pkg/graph/`, `engine/pkg/api/graph_handlers.go`, pure-Go SQLite with FTS5, half-life decay, Hebbian reinforcement.
> - **KMP Shared Layer**: `shared/.../domain/model/GraphModels.kt`, `GraphRepository`, `GraphUseCases.kt`, `EngineDataSource.kt`, `EngineClient.kt`.
> - **UI / Presentation**: `GraphContract.kt`, `GraphViewModel.kt`, `GraphScreen.kt`, `GraphRoute.kt`, `WorkspaceSidebar.kt`.
> - **Tests**: `engine/pkg/graph/sqlite_test.go`, `shared/.../presentation/graph/GraphViewModelTest.kt`.

---

## 1. Overview & Motivation

As users conduct multiple multi-agent dialectical deliberations, critical decisions, technical trade-offs, and empirical assertions accumulate across disparate discussions. Without structured persistence and semantic linkage, these insights remain trapped in linear transcripts.

**Feature 01** solves this by establishing a **Self-Organizing Deliberation Knowledge Graph** coupled with a **Fluid Discussion Drag-and-Drop Workspace Organization System**:
1. **Dynamic Knowledge Graph**: Automatically indexes discussion concepts, claims, and dialectical tensions as nodes and edges in a high-performance SQLite graph engine with full-text search (FTS5).
2. **Hebbian Reinforcement & Half-Life Decay**: Edge connection strengths and node activation weights update dynamically as ideas are cited or left dormant.
3. **Interactive 2D Canvas Visualization**: A force-directed canvas rendered in Compose Multiplatform with pan, zoom, activation threshold filtering, and FTS5 concept query search.
4. **Fluid Drag-and-Drop Workspace Sidebar**: Reassign discussions across project workspaces with physics-based smooth spring animations and drop-target expansion.

---

## 2. Architecture & Data Flow

```mermaid
flowchart TD
    subgraph KMP Presentation & UI
        WS[WorkspaceSidebar.kt<br/>Drag-and-Drop Gesture Detector] -->|Reassign Project| DUC[UpdateDiscussionUseCase]
        GS[GraphScreen.kt<br/>Interactive 2D Visualizer] -->|MVI Intent| GVM[GraphViewModel]
        GVM -->|Query Nodes & Edges| GU[GetKnowledgeGraphUseCase]
    end

    subgraph KMP Clean Architecture Layer
        GU --> GR[GraphRepositoryImpl]
        DUC --> DR[DiscussionRepositoryImpl]
        GR --> EDS[EngineDataSource]
        EDS --> EC[EngineClient - Ktor HTTP]
    end

    subgraph Go Engine (localhost:7432)
        EC -->|GET /api/v1/graph| GH[graph_handlers.go]
        GH --> GSStore[graph/sqlite.go<br/>SQLite + FTS5 Graph Engine]
        GSStore -->|Decay & Reinforce| SQLDB[(dialex_graph.db)]
    end
```

---

## 3. Go Engine Implementation

### 3.1 Data Structures (`engine/pkg/graph/graph.go`)
- **`GraphNode`**: Represents a concept, assertion, or decision unit.
  - `ID`: Unique identifier (e.g. `node_sqlite_wal`).
  - `ProjectID`: Scopes the knowledge entity to the parent project workspace.
  - `DiscussionID`: Discussion where the node originated.
  - `Label`: Human-readable title or concept name.
  - `Type`: Node classification (`concept`, `claim`, `evidence`, `decision`, `metric`).
  - `Summary`: Distilled paragraph or quote.
  - `Activation`: Float64 activation level in `[0.0, 1.0]`.
  - `LastReinforced`: Unix timestamp (seconds).
- **`GraphEdge`**: Represents semantic or causal relationships between nodes.
  - `ID`: Edge identifier.
  - `SourceID`, `TargetID`: Connecting node IDs.
  - `Relation`: Relationship type (`contradicts`, `supports`, `refines`, `depends_on`, `replaces`).
  - `Weight`: Float64 connection weight in `[0.0, 1.0]`.
  - `CoOccurrence`: Number of shared round citations.
  - `LastReinforced`: Unix timestamp.

### 3.2 Pure-Go SQLite Graph Store (`engine/pkg/graph/sqlite.go`)
Uses `modernc.org/sqlite` (CGO-free pure Go) to guarantee deterministic cross-platform execution on macOS, Linux, and Windows:
- **Schema & Indexes**:
  - `nodes` table with unique constraint on `(project_id, label)`.
  - `edges` table with unique constraint on `(source_id, target_id, relation)`.
  - `nodes_fts` FTS5 virtual table for Porter-stemmed keyword search across labels and summaries.
- **Hebbian Reinforcement Formula**:
  When two entities co-occur in a consensus turn or debate claim:
  $$\Delta W = \eta \cdot (1.0 - W)$$
  where $\eta = 0.15$. High co-occurrence rapidly solidifies the connection towards 1.0.
- **IEEE 754 Half-Life Temporal Decay**:
  Nodes and edges decay exponentially if unreinforced:
  $$A(t) = A_0 \cdot 2^{-\frac{\Delta t}{T_{1/2}}}$$
  where $T_{1/2} = 14 \text{ days}$ ($1,209,600 \text{ seconds}$).
- **REST Endpoints**:
  - `GET /api/v1/graph?project_id=...&query=...&min_activation=...`: Fetches filtered graph nodes and edges.
  - `POST /api/v1/graph/nodes`: Ingests a new concept or assertion.
  - `POST /api/v1/graph/edges`: Ingests or strengthens an edge.
  - `POST /api/v1/graph/reinforce`: Manually reinforces an entity or pair.

---

## 4. KMP Shared & MVI Presentation

### 4.1 Clean Architecture Hierarchy
1. **Domain Layer**:
   - `com.dialex.domain.model.GraphNode`, `GraphEdge`, `KnowledgeGraph`.
   - `com.dialex.domain.repository.GraphRepository`: Abstract interface for graph operations.
   - `com.dialex.domain.usecase.GetKnowledgeGraphUseCase`, `ReinforceGraphNodeUseCase`.
2. **Data Layer**:
   - `com.dialex.data.datasource.EngineDataSource`: Wraps HTTP requests and maps HTTP 500/connection errors to `DomainException`.
   - `com.dialex.data.repository.GraphRepositoryImpl`: Implements `GraphRepository`.
3. **Presentation Layer (`com.dialex.presentation.graph`)**:
   - `GraphContract.kt`: Holds `GraphState` (nodes, edges, search query, selected node, activation filter), `GraphIntent` (SearchQueryChanged, MinActivationChanged, SelectNode, ResetFilter), and `GraphEffect` (ShowSnackbar, NavigateToDiscussion).
   - `GraphViewModel.kt`: MVI ViewModel reacting to filter adjustments and loading dynamic graph states.
   - `GraphScreen.kt`: Interactive Compose Multiplatform canvas implementing node positioning, bezier edge arcs, collision avoidance, and hover/click inspection sidebars.

### 4.2 Fluid Drag-and-Drop Workspace Organization (`WorkspaceSidebar.kt`)
- Discussions can be dragged from one project group and dropped onto another.
- Features real-time drop-target highlighting (`DropTargetOverlay`), target project auto-expansion after a 500ms hover delay, and non-blocking asynchronous migration via `UpdateDiscussionUseCase(discussion.copy(projectId = targetProjectId))`.
- Prevents redundant drop dispatches and re-renders tree hierarchy with smooth layout animations.

---

## 5. Verification & Test Coverage
- **Go Engine Suite (`engine/pkg/graph/sqlite_test.go`)**:
  - `TestSQLiteGraphStore_InsertAndQuery`: Validates node and edge persistence and querying.
  - `TestSQLiteGraphStore_FTS5Search`: Validates full-text search token matching.
  - `TestSQLiteGraphStore_TemporalDecay`: Verifies half-life mathematical decay over simulated time.
  - `TestSQLiteGraphStore_HebbianReinforcement`: Validates progressive edge weight convergence.
- **KMP Unit Suite (`GraphViewModelTest.kt`)**:
  - Verifies MVI state transitions, min-activation threshold filtering, node selection, and empty graph fallback.
