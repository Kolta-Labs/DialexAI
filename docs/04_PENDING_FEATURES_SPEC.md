# Pending Features Specification — Dialex AI
> **Status**: Approved for Roadmap & Architecture Backlog  
> **Priority Classification**: **IMPORTANT / HIGH PRIORITY**  
> **Source Inspirations**: Dialex Epistemic Research, Multi-Mind Dialectic Synthesis (`murilloimparavel/dialex`), Buehler MIT Self-Organizing Graphs, Paraconsistent Logic.

This document serves as the formal specification for pending features slated for implementation into **Dialex AI**. Every feature in this document is marked as **IMPORTANT** to elevate Dialex AI from a turn-based deliberation chat workspace into a compounding, self-organizing cognitive engine.

---

## 📑 Specification Index

1. [Self-Organizing Knowledge Graph with Temporal Decay (COMPLETED)](#1-self-organizing-knowledge-graph-with-temporal-decay)
2. [Pre-Debate Multi-Perspective Problem Decomposition (COMPLETED)](#2-pre-debate-multi-perspective-problem-decomposition)
3. [Explicit Contradiction & Tension Pair Detection Engine](#3-explicit-contradiction--tension-pair-detection-engine)
4. [Round-Aware Dynamic Graph Retrieval (In-Debate RAG)](#4-round-aware-dynamic-graph-retrieval-in-debate-rag)
5. [Dedicated 1-on-1 Socratic Interview Mode](#5-dedicated-1-on-1-socratic-interview-mode)
6. [Null Hypothesis Benchmarking & Quantitative Evaluation Suite](#6-null-hypothesis-benchmarking--quantitative-evaluation-suite)
7. [8-Layer DNA Mental & Structured Persona Ingestion](#7-8-layer-dna-mental--structured-persona-ingestion)

---

## 1. Self-Organizing Knowledge Graph with Temporal Decay

> [!NOTE]
> **Status**: **COMPLETED** (v1.1)  
> **Component**: Go Engine (`pkg/graph`, `pkg/api`), KMP Shared (`domain`, `data`, `presentation/graph`), Embedded SQLite + FTS5, Pure-Go WAL.

### 1.1 Problem Statement
Currently, Dialex AI stores discussion records in flat atomic JSON files. Each deliberation starts in a fresh silo. Past insights, consensus decisions, trade-offs, and citations do not compound across discussions into institutional memory.

### 1.2 Proposed Architecture & Specifications
Implement an embedded, local-first **SQLite + FTS5** graph storage engine with temporal decay:
* **Node Schema**:
  * `id`: UUID
  * `type`: `CONCEPT | ARGUMENT | CONSENSUS | TENSION | DELIVERABLE | SOURCE`
  * `title`: String
  * `content`: Text (indexed in FTS5)
  * `weight`: Float (activation energy $W \in [0.0, 1.0]$)
  * `created_at`, `last_accessed_at`: Timestamp
  * `decay_half_life_days`: Float (default: 30 days)
* **Edge Schema**:
  * `source_id`, `target_id`: Node IDs
  * `relation`: `CONTRADICTS | SUPPORTS | BLENDS_INTO | DERIVED_FROM | PREREQUISITE_FOR`
  * `strength`: Float (reinforced upon co-reference)
* **Temporal Decay Mathematical Formulation**:
  $$W(t) = W_0 \cdot 2^{-\frac{\Delta t}{t_{\text{half}}}}$$
  Nodes not referenced in active deliberations naturally decay in activation weight. Nodes with $W < 0.1$ go dormant (excluded from automatic context injection unless explicitly queried via FTS5). When a dormant node is recalled and validated in a debate, its weight resets to $1.0$ and connected edges strengthen.

---

## 2. Pre-Debate Multi-Perspective Problem Decomposition

> [!NOTE]
> **Status**: **COMPLETED** (v1.2)  
> **Component**: Go Engine (`pkg/decomposition`, `pkg/api`), KMP Shared (`domain`, `data`, `presentation/setup`), Compose UI.

### 2.1 Problem Statement
Deliberations currently begin immediately with Round 1 framing by the Moderator. Complex, multifaceted dilemmas (e.g., *"Should we rewrite our core engine in Rust or Go?"*) often suffer from initial framing bias if the dilemma is not first split into its core orthogonal tensions.

### 2.2 Proposed Architecture & Specifications
Introduce a preliminary **Decomposition Phase** prior to Round 1:
1. **Divergent Splitting**: Before peer models debate, two distinct model personas (e.g., *Systems Architect* vs. *VP of Product / FinTech Strategist*) generate competing decompositions of the question into 3–4 sub-axes (e.g., Axis 1: Memory safety & concurrency vs Axis 2: Team hiring velocity & ecosystem maturity).
2. **Interactive Selection UI**: The human orchestrator is presented with the two proposed framing breakdown trees in the UI and can:
   - Accept Decomposition A or B.
   - Blend both into a hybrid debate agenda.
   - Kick off parallel sub-rounds across the decomposed branches.

---

## 3. Explicit Contradiction & Tension Pair Detection Engine

> [!NOTE]
> **Status**: **COMPLETED** (v1.3)  
> **Component**: Go Engine (`pkg/consensus`, `pkg/model`), KMP Presentation (`chat`, `deliverables`).

### 3.1 Problem Statement
In current deliberations, contradictions between opposing models are expressed as unstructured prose. Consensus evaluation searches for lexical agreement or numerical votes, which risks papering over critical technical tensions.

### 3.2 Proposed Architecture & Specifications
Grounded in **Paraconsistent Logic** (da Costa, 1974), where contradictions are treated as informative signals rather than system failures:
* **Tension Pair Data Model**:
  ```kotlin
  data class TensionPair(
      val id: String,
      val thesis: AgentAssertion,        // e.g., "Full ACID replication required"
      val antithesis: AgentAssertion,    // e.g., "Eventual consistency required for <10ms SLA"
      val underlyingConflict: String,    // "Consistency vs. Availability"
      val status: TensionStatus          // OPEN | EXPLORED | RESOLVED | ACCEPTED_TRADE_OFF
  )
  ```
* **Real-Time Extraction**: An async evaluation pass runs at the end of each round to extract new tension pairs.
* **Tension Matrix UI Card**: A live sidebar or HUD element displaying unresolved tensions.
* **Consensus Enforcement**: The Moderator cannot mark a discussion as `CONSENSUS_REACHED` unless every identified tension pair is either resolved via a synthesis compromise or formally documented as an accepted trade-off in the final deliverable.

---

## 4. Round-Aware Dynamic Graph Retrieval (In-Debate RAG)

> [!IMPORTANT]
> **Priority**: High  
> **Component**: Go Engine (`pkg/orchestrator`, `pkg/runner`), KMP Shared (`data`).

### 4.1 Problem Statement
Standard RAG retrieves context once at the start of a prompt session. In a 4-round multi-agent debate, the conversation shifts dynamically into unexpected technical subtopics, making initial retrieval stale.

### 4.2 Proposed Architecture & Specifications
Implement **Round-Aware Retrieval**:
1. At the conclusion of Round $N$, the orchestrator inspects the delta of assertions, claims, and technical terms introduced in Round $N$.
2. It generates focused retrieval queries against the local knowledge graph, indexed project files, and codebase embeddings.
3. Relevant evidence nodes (e.g., benchmark numbers, past ADRs, API specs) are dynamically injected into the system context for Round $N+1$ with attribution badges (`[Evidence Retrieved for Round 2: SQLite vs Postgres Benchmarks]`).

---

## 5. Dedicated 1-on-1 Socratic Interview Mode

> [!IMPORTANT]
> **Priority**: Medium / High Value  
> **Component**: Go Engine (`pkg/api`, `pkg/orchestrator`), KMP Presentation (`interview`).

### 5.1 Problem Statement
Convening a 4-to-6-model council is resource-intensive when a user merely wants to deep-dive an individual expert persona (e.g., interviewing *The Risk Analyst* on a specific zero-trust auth vulnerability).

### 5.2 Proposed Architecture & Specifications
* **Command & UI Entrypoint**: `*interview {persona_id} {topic}` or a dedicated **"Socratic Interview"** button in the Persona Studio.
* **Socratic Dialogue Protocol**: The selected persona adopts a structured Socratic extraction prompt—probing the user's constraints, challenging unstated assumptions, and producing a structured interview transcript.
* **Output Deliverable**: Generates a 1-tap **Socratic Interview Digest** that can be directly converted into a seed problem for a subsequent multi-agent council deliberation.

---

## 6. Null Hypothesis Benchmarking & Quantitative Evaluation Suite

> [!IMPORTANT]
> **Priority**: High  
> **Component**: Tooling (`tools/evaluator`, Go test suite), Web Admin Dashboard (`/admin/benchmarks`).

### 6.1 Problem Statement
To establish scientific legitimacy and enterprise ROI, Dialex AI must empirically prove that multi-agent deliberation yields superior, less hallucinated outcomes than a single well-prompted frontier model.

### 6.2 Proposed Architecture & Specifications
* **The Null Hypothesis**: *"A single frontier model (Claude 3.7 Sonnet or GPT-4o) with extended chain-of-thought produces equivalent or superior decision quality compared to a multi-agent dialectic council."*
* **Automated Evaluation Harness**:
  * Ingests standardized architectural dilemmas and edge-case engineering prompts.
  * Runs Run A (Single-Model Baseline with high reasoning effort).
  * Runs Run B (Dialex AI Multi-Agent Council with 3–4 competing models + Moderator).
  * Evaluates both outputs using a blinded LLM-as-a-Judge and programmatic metrics:
    1. **Hallucination Rate**: Count of ungrounded or fabricated claims.
    2. **Blind Spot Coverage**: Number of recognized edge cases and risk modes.
    3. **Trade-off Completeness**: Balance and depth of pro/con analysis.
    4. **Actionability Score**: Concrete clarity of the produced deliverable.

---

## 7. 8-Layer DNA Mental & Structured Persona Ingestion

> [!IMPORTANT]
> **Priority**: Medium / High Value  
> **Component**: KMP Shared (`model`, `domain`), Go Engine (`pkg/model`).

### 7.1 Problem Statement
Currently, personas are defined via markdown prompts and style toggles (Caveman/Ponytail). To support deeper psychological and domain fidelity, structured persona schemas are needed.

### 7.2 Proposed Architecture & Specifications
Adopt an **8-Layer Cognitive Schema** (compatible with MMOS exports):
1. **Core Identity**: Name, background, domain authority.
2. **Epistemic Bias**: Preferred reasoning frameworks (First Principles, Empirical/Statistical, Historical Analogy, Pragmatic/Engineering).
3. **Communication Vector**: Tone, formality, directness, brevity.
4. **Heuristic Library**: Specific rules-of-thumb and mental models invoked under stress.
5. **Taboo Space**: What arguments or fallacies this persona refuses to tolerate.
6. **Domain Ontology**: Specialized vocabulary, standards, and references.
7. **Adversarial Posture**: How the persona reacts when challenged (accommodating, unyielding, counter-attacking).
8. **Synthesis Preference**: Propensity toward compromise vs holding a hard minority report.
* Provide JSON/YAML import and export in the **Persona Studio**.
