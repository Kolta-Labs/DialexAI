# Feature 09: Bayesian Credence Tracking & Quantitative Epistemic Uncertainty Network (v1.9) — Architectural Specification

## Executive Summary

Dialex AI has systematically conquered qualitative dialectic rigor through Hegelian multi-agent deliberation, 8-layer cognitive persona DNAs, Paraconsistent tension tracking, dynamic in-debate RAG, and single-model null-hypothesis benchmarking. However, in enterprise architecture, strategic finance, and cybersecurity, rhetoric alone is insufficient: **executives and engineering leaders require quantifiable confidence, explicit probabilistic trade-offs, and empirical uncertainty metrics.**

Frontier Large Language Models (LLMs) are notorious for two opposing failure modes during deliberation:
1. **Spurious Overconfidence**: Articulating flawed technical arguments with absolute linguistic conviction.
2. **False Equivalence & Sycophancy**: Equating deeply unviable edge-case strategies with battle-tested standards in an effort to maintain conversational politeness.

**Feature 09 (v1.9: Bayesian Credence Tracking & Quantitative Epistemic Uncertainty Network)** transforms Dialex AI from a purely qualitative argumentation system into a **Mathematically Grounded Epistemic Decision Engine**. By extracting competing candidate hypotheses, tracking round-by-round prior and posterior probability distributions ($P(H | E)$), computing Shannon Epistemic Entropy ($\mathcal{S}$), and calculating evidentiary Likelihood Ratios ($\Lambda$), Dialex AI provides an empirical, visual, and audit-ready map of how evidence moves minds.

---

## 1. Mathematical Formulation & Epistemic Theory

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        BAYESIAN CREDENCE DYNAMICS IN DIALEX AI                         │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                        │
│   Prior Beliefs              Round N Evidentiary Shock             Posterior Beliefs   │
│   P(H_k | E_{1:r-1})   ───►  Dynamic Citations & Contradictions  ───► P(H_k | E_{1:r}) │
│                                         │                                              │
│                                         ▼                                              │
│                           Likelihood Ratio  Λ(E_r)                                     │
│                           Shannon Entropy   S^{(r)}                                    │
│                           Brier Calibration BS                                         │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.1 Hypothesis Space Formulation
At dilemma intake (or during initial framing in Round 1), the engine extracts a finite set of $K$ mutually exclusive and exhaustive architectural/strategic hypotheses:
$$\mathcal{H} = \{ H_1, H_2, \dots, H_K \}, \quad \text{where } K \in [2, 5]$$

*Example*:
* $H_1$: *Distributed Event-Driven Architecture (Apache Kafka + Debezium CDC)*
* $H_2$: *Synchronous High-Throughput Service Mesh (gRPC + Envoy + ScyllaDB)*
* $H_3$: *Modular Monolith with Read Replicas (PostgreSQL + PgBouncer)*

### 1.2 Prior Probability Distribution ($r = 0$)
The initial prior distribution is established via uniform uninformative prior or Dirichlet-weighted project context:
$$P_0(H_k) = \frac{1}{K}, \quad \forall k \in \{1, \dots, K\}$$

### 1.3 Round-by-Round Bayesian Posterior Update ($r \ge 1$)
At each round $r$, persona agents assert empirical arguments, benchmark data, and architectural objections ($E_r$).
Each participating persona model $i \in \{1, \dots, N\}$ outputs its subjective credence vector:
$$\mathbf{p}_i^{(r)} = \left[ P_i(H_1 | E_{1:r}), P_i(H_2 | E_{1:r}), \dots, P_i(H_K | E_{1:r}) \right], \quad \sum_{k=1}^K P_i(H_k | E_{1:r}) = 1.0$$
alongside an **Epistemic Certainty Score** $c_i^{(r)} \in [0.0, 1.0]$ representing meta-cognitive calibration (confidence in its own assessment).

The aggregated council consensus probability for hypothesis $H_k$ is calculated as a weighted linear pool:
$$\overline{P}^{(r)}(H_k) = \sum_{i=1}^N \omega_i \cdot P_i(H_k | E_{1:r})$$
where $\omega_i$ is the persona domain authority weight derived from Layer 1 (Core Identity) and Layer 2 (Epistemic Bias) of its 8-Layer DNA profile:
$$\omega_i = \frac{\alpha_i \cdot c_i^{(r)}}{\sum_{j=1}^N \alpha_j \cdot c_j^{(r)}}$$

### 1.4 Shannon Epistemic Entropy (Council Uncertainty Metric)
The uncertainty state of the council at round $r$ is formally quantified through Shannon Entropy:
$$\mathcal{S}^{(r)} = -\sum_{k=1}^K \overline{P}^{(r)}(H_k) \cdot \log_2 \overline{P}^{(r)}(H_k) \quad \text{[bits]}$$

* **Maximum Uncertainty ($\mathcal{S} \approx \log_2 K$)**: The council is in total deadlock or unyielding disagreement; hypotheses have near-equal probability.
* **Dialectic Evolution ($\Delta \mathcal{S} < 0$)**: New evidence successfully reduces ambiguity and drives consensus.
* **Epistemic Convergence ($\mathcal{S} < 0.25\text{ bits}$)**: Mathematical consensus achieved; one dominant hypothesis has captured $>92\%$ weighted council credence.

### 1.5 Evidentiary Impact Factor & Likelihood Ratio ($\Lambda$)
To detect the exact "smoking gun" argument or piece of retrieved graph evidence that changed the council's trajectory, the engine computes the Likelihood Ratio $\Lambda$ between the top two contenders ($H_a$ vs $H_b$):
$$\Lambda(E_r) = \frac{P(E_r \mid H_a)}{P(E_r \mid H_b)} = \frac{\overline{P}^{(r)}(H_a) \cdot \overline{P}^{(r-1)}(H_b)}{\overline{P}^{(r)}(H_b) \cdot \overline{P}^{(r-1)}(H_a)}$$
Any evidence with $\Lambda(E_r) > 3.0$ or $\Lambda(E_r) < 0.33$ is marked with an **Epistemic Tipping Point** badge in the deliberation transcript.

---

## 2. System Architecture & Component Design

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                   DIALEX ENGINE (Go)                                   │
│  engine/pkg/credence/                                                                  │
│    ├── model.go        (Hypothesis, CredenceSnapshot, CredenceLedger, TippingPoint)    │
│    ├── extractor.go    (Zero-shot candidate hypothesis extraction from dilemma)        │
│    ├── tracker.go      (Round-by-round Bayesian update, entropy & Lambda calculations)  │
│    ├── evaluator.go    (Structured persona evaluation prompt & JSON extractor)         │
│    └── tracker_test.go (Unit & math invariant verification tests)                      │
│  engine/pkg/api/                                                                       │
│    ├── credence_handlers.go      (REST API: GET /debates/{id}/credence, recalculate)   │
│    └── credence_handlers_test.go (HTTP endpoint integration tests)                     │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                                   KMP SHARED LAYER                                     │
│  shared/.../domain/model/CredenceModels.kt     (@Serializable immutable models)        │
│  shared/.../domain/repository/CredenceRepository.kt                                    │
│  shared/.../data/repository/CredenceRepositoryImpl.kt                                  │
│  shared/.../domain/usecase/GetCredenceLedgerUseCase.kt                                 │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                               PRESENTATION & VISUALIZATION                             │
│  shared/.../presentation/chat/CredenceRibbonCanvas.kt (Desktop & Mobile Area Flow)     │
│  shared/.../presentation/chat/CredenceDrawer.kt       (Touch Bottom Sheet & Modal)     │
│  shared/.../presentation/workspace/WorkspaceHeader.kt (Live Entropy & Credence Pill)   │
│  shared/.../export/ExecutiveMemoExporter.kt           (Bayesian Credence Matrix table) │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Go Engine Specification (`engine/pkg/credence/`)

### 3.1 Data Structures (`engine/pkg/credence/model.go`)

```go
package credence

import "time"

// Hypothesis represents a discrete competing strategy or thesis under deliberation.
type Hypothesis struct {
	ID          string  `json:"id"`
	Index       int     `json:"index"`       // 1, 2, 3...
	Label       string  `json:"label"`       // Short title (e.g. "Kafka + CDC")
	Description string  `json:"description"` // Full thesis description
	ColorHex    string  `json:"colorHex"`    // Deterministic palette color
}

// PersonaCredence captures an individual model's subjective probability assignment.
type PersonaCredence struct {
	PersonaID          string             `json:"personaId"`
	PersonaName        string             `json:"personaName"`
	HypothesisCredence map[string]float64 `json:"hypothesisCredence"` // HypothesisID -> [0.0, 1.0]
	CertaintyScore     float64            `json:"certaintyScore"`     // Meta-confidence [0.0, 1.0]
	CoreRationale      string             `json:"coreRationale"`      // 1-2 sentence epistemic defense
}

// TippingPoint marks a high-leverage piece of evidence that caused a major belief shift.
type TippingPoint struct {
	RoundIndex      int     `json:"roundIndex"`
	EvidenceSnippet string  `json:"evidenceSnippet"`
	LikelihoodRatio float64 `json:"likelihoodRatio"`
	AffectedHypothesis string `json:"affectedHypothesis"`
	ShiftDelta      float64 `json:"shiftDelta"` // e.g. +0.34
}

// RoundCredenceSnapshot stores the aggregated epistemic state after a completed round.
type RoundCredenceSnapshot struct {
	RoundIndex        int                        `json:"roundIndex"`
	Timestamp         time.Time                  `json:"timestamp"`
	PersonaCredences  []PersonaCredence          `json:"personaCredences"`
	AggregatedCredence map[string]float64        `json:"aggregatedCredence"` // HypothesisID -> [0.0, 1.0]
	Entropy           float64                    `json:"entropy"`            // Shannon entropy in bits
	DominantHypothesis string                    `json:"dominantHypothesis"`
	TippingPoints     []TippingPoint             `json:"tippingPoints,omitempty"`
}

// CredenceLedger maintains the full historical trajectory of belief evolution.
type CredenceLedger struct {
	DiscussionID string                  `json:"discussionId"`
	Topic        string                  `json:"topic"`
	Hypotheses   []Hypothesis            `json:"hypotheses"`
	Snapshots    []RoundCredenceSnapshot `json:"snapshots"`
	FinalEntropy float64                 `json:"finalEntropy"`
	Status       string                  `json:"status"` // "CONVERGED" | "STALEMATE" | "IN_PROGRESS"
}
```

### 3.2 Hypothesis Extractor (`engine/pkg/credence/extractor.go`)
Before Round 1 begins, the engine evaluates the debate topic and attached project files to synthesize 2 to 4 discrete hypotheses using an empirical extraction prompt:

```go
const HYPOTHESIS_EXTRACTION_PROMPT = `
Analyze the following architectural dilemma and decompose it into 2 to 4 mutually exclusive, exhaustive strategic hypotheses (competing solutions).

Topic: %s
Context: %s

Respond STRICTLY in JSON format:
{
  "hypotheses": [
    {
      "label": "Short label (3-5 words)",
      "description": "Specific, unambiguous architectural strategy with key trade-offs."
    }
  ]
}
`
```

### 3.3 Bayesian Tracker & Entropy Math (`engine/pkg/credence/tracker.go`)
```go
func CalculateShannonEntropy(probabilities map[string]float64) float64 {
	entropy := 0.0
	for _, p := range probabilities {
		if p > 1e-6 {
			entropy -= p * math.Log2(p)
		}
	}
	return entropy
}

func CalculateLikelihoodRatio(priorA, priorB, postA, postB float64) float64 {
	if priorA <= 0 || priorB <= 0 || postB <= 0 {
		return 1.0
	}
	return (postA * priorB) / (postB * priorA)
}
```

---

## 4. KMP Shared Domain & Data Specification

### 4.1 Data Models (`shared/src/commonMain/kotlin/com/dialex/domain/model/CredenceModels.kt`)

```kotlin
package com.dialex.domain.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.serialization.Serializable

@Serializable
data class CredenceHypothesis(
    val id: String,
    val index: Int,
    val label: String,
    val description: String,
    val colorHex: String
)

@Serializable
data class PersonaCredencePoint(
    val personaId: String,
    val personaName: String,
    val hypothesisCredence: ImmutableMap<String, Double>,
    val certaintyScore: Double,
    val coreRationale: String
)

@Serializable
data class EpistemicTippingPoint(
    val roundIndex: Int,
    val evidenceSnippet: String,
    val likelihoodRatio: Double,
    val affectedHypothesis: String,
    val shiftDelta: Double
)

@Serializable
data class RoundCredenceSnapshot(
    val roundIndex: Int,
    val personaCredences: ImmutableList<PersonaCredencePoint>,
    val aggregatedCredence: ImmutableMap<String, Double>,
    val entropy: Double,
    val dominantHypothesis: String?,
    val tippingPoints: ImmutableList<EpistemicTippingPoint>
)

@Serializable
data class CredenceLedger(
    val discussionId: String,
    val topic: String,
    val hypotheses: ImmutableList<CredenceHypothesis>,
    val snapshots: ImmutableList<RoundCredenceSnapshot>,
    val finalEntropy: Double,
    val status: String
)
```

### 4.2 Repository Interface (`shared/src/commonMain/kotlin/com/dialex/domain/repository/CredenceRepository.kt`)

```kotlin
package com.dialex.domain.repository

import com.dialex.domain.model.CredenceLedger

interface CredenceRepository {
    suspend fun getCredenceLedger(discussionId: String): CredenceLedger
    suspend fun recalculateCredence(discussionId: String): CredenceLedger
}
```

---

## 5. UI & Presentation Specification (Compose Multiplatform)

### 5.1 Credence Ribbon Canvas (`CredenceRibbonCanvas.kt`)
The Credence Ribbon is a responsive, smooth area-flow visualization (similar to a stacked Sankey/Alluvial chart) that plots round numbers along the horizontal X-axis ($0, 1, 2, \dots, N$) and cumulative consensus probabilities ($0.0 \dots 1.0$) along the Y-axis.

1. **Visual Encoding**:
   - Each hypothesis occupies a fluid vertical ribbon whose height is directly proportional to its consensus probability $\overline{P}^{(r)}(H_k)$.
   - Fluid cubic Bézier curves smoothly transition belief shifts between rounds.
   - Distinctive colors:
     - $H_1$: Electric Emerald (`#10B981`)
     - $H_2$: Royal Indigo (`#6366F1`)
     - $H_3$: Crimson Coral (`#F43F5E`)
     - $H_4$: Amber Gold (`#F59E0B`)
2. **Interactive Scrubbing**:
   - Scrubbing or tapping any round column updates a bottom inspector showing individual persona votes:
     - Model avatars with their respective $P_i(H_k)$ assignments and rationales.
     - Likelihood ratio callout for any tipping point introduced in that round.
3. **Viewport Responsiveness**:
   - **Desktop**: Full-width interactive canvas embedded above the chat transcript or accessible via slide-out drawer (`Cmd+Shift+B`).
   - **Mobile (`maxWidth < 640.dp`)**: Automatically renders in a touch-friendly bottom sheet with touch scrubbing and compact vertical hypothesis cards.

### 5.2 Live Epistemic Entropy Pill (`WorkspaceHeader.kt`)
In both desktop and mobile header rails, a reactive pill communicates council certainty in real time:
- **`[ 🟢 88% H1 (S: 0.18 bits) ]`** &rarr; Consensus Converged
- **`[ 🟡 54% H2 vs 46% H1 (S: 0.98 bits) ]`** &rarr; Active Dialectic Contention
- **`[ 🔴 Deadlock (S: 1.58 bits) ]`** &rarr; Complete Stalemate / Irreconcilable Trade-off

Tapping this pill toggles the **CredenceDrawer**.

### 5.3 ADR & Executive Memo Inclusion (`ExecutiveMemoExporter.kt`)
Generated Architecture Decision Records (ADRs) and Executive Memorandums automatically embed a formal **Bayesian Epistemic Decision Matrix**:

| Hypothesis | Prior $P_0$ | Posterior $P_N$ | Net Belief Shift ($\Delta P$) | Decisive Evidence Citation |
|---|---|---|---|---|
| **$H_1$: Kafka + CDC** | $33.3\%$ | **$88.4\%$** | $+55.1\%$ | *Empirical Disk I/O Benchmark (Round 3)* |
| **$H_2$: gRPC + ScyllaDB** | $33.3\%$ | **$8.2\%$** | $-25.1\%$ | *Operational Complexity Analysis (Round 2)* |
| **$H_3$: Postgres Replicas** | $33.3\%$ | **$3.4\%$** | $-29.9\%$ | *Write Saturation Falsification (Round 4)* |

**Final Epistemic Entropy**: $0.21\text{ bits}$ (*Status: Mathematically Conclusive Consensus*).

---

## 6. Verification & Testing Plan

1. **Mathematical Invariant Tests (`engine/pkg/credence/tracker_test.go`)**:
   - $\sum_{k=1}^K P(H_k) = 1.0 \pm 10^{-6}$ at every round snapshot.
   - Shannon entropy $\mathcal{S}$ is non-negative and bounded: $0.0 \le \mathcal{S} \le \log_2 K$.
   - Likelihood ratio $\Lambda > 1.0$ if and only if $P(H_a | E) > P_0(H_a)$.
2. **API Endpoint Integration Tests (`engine/pkg/api/credence_handlers_test.go`)**:
   - `GET /debates/{id}/credence` returns 200 OK with valid JSON ledger.
   - Handles deliberations in progress, completed, and empty states.
3. **KMP Test Suite (`shared/.../CredenceRepositoryTest.kt`)**:
   - Serialization and deserialization roundtrip tests for `CredenceLedger`.
   - MVI contract verification for `CredenceRibbonCanvas`.
   - Desktop and Mobile responsive layout verification.

---

## 7. Implementation Roadmap & Milestones

* **Phase 1: Go Engine Core Math & API** (`pkg/credence`, `pkg/api`): Models, hypothesis extractor, Bayesian tracker, and REST endpoints.
* **Phase 2: KMP Shared Layer**: Data transfer objects, repository, and use cases.
* **Phase 3: Presentation UI & Credence Ribbon Canvas**: Desktop and mobile interactive canvas with Bézier flow ribbons and entropy gauge.
* **Phase 4: Deliverable Synthesis & Verification**: Integration with ADR/Memo exporter and comprehensive test suite execution.
