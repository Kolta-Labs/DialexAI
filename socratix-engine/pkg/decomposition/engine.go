package decomposition

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

// Whole-word match: bare substrings like "go" hit "algorithm", "good", "google", "rust" hits "trust".
var languageTopicRegex = regexp.MustCompile(`\b(rust|go|golang|rewrite)\b`)

var jsonExtractRegex = regexp.MustCompile(`(?s)\{.*"perspectiveA".*"perspectiveB".*\}`)

// DecomposeProblem coordinates divergent LLM generation or deterministic fallback.
func DecomposeProblem(
	ctx context.Context,
	r runner.AgentRunner,
	agent model.Agent,
	topic string,
	userContext string,
	modelOverride string,
) (*DecompositionResult, error) {
	topic = strings.TrimSpace(topic)
	if topic == "" {
		return nil, fmt.Errorf("topic cannot be empty")
	}

	// If no runner or agent is provided, return rich deterministic heuristic fallback immediately
	if r == nil || agent.Provider == "" {
		res := GenerateHeuristicDecomposition(topic, userContext)
		return &res, nil
	}

	systemPrompt := `You are an expert Principal Systems Architect and Dialectic Framing Engine.
Your task is to decompose a complex dilemma or topic into two competing, orthogonal perspectives before a multi-agent debate starts.

Perspective A: Technical / Structural Lens (Invariants, Latency/Throughput SLAs, State Consistency, Fault Isolation, Reliability).
Perspective B: Product / Strategic / Operational Lens (Developer Ergonomics, Time-to-Market, Migration Blast Radius, Total Cost of Ownership, Organizational Velocity).

For EACH perspective, produce exactly 3 to 4 distinct orthogonal sub-axes.
Each sub-axis must specify:
1. "id": unique lowercase string (e.g. "tech_consistency_latency")
2. "title": concise descriptive title
3. "thesis": clear affirmative argument or requirement (e.g. "Strict linearizability is mandatory for financial invariants")
4. "antithesis": opposing technical or operational constraint (e.g. "Multi-region WAN latency penalty (>250ms) breaks checkout SLA")
5. "keyQuestions": 2-3 probing dilemma questions that must be answered
6. "weight": float between 0.1 and 1.0 indicating relative criticality

Return ONLY a valid JSON object with the following schema, with no conversational preamble:
{
  "topic": "...",
  "perspectiveA": {
    "id": "technical_structural",
    "name": "Technical & Structural Architecture",
    "lensDescription": "Evaluates formal correctness, state invariants, fault isolation, and low-level performance guarantees.",
    "axes": [
      {
        "id": "tech_axis_1",
        "title": "...",
        "thesis": "...",
        "antithesis": "...",
        "keyQuestions": ["..."],
        "weight": 0.9,
        "selected": true
      }
    ]
  },
  "perspectiveB": {
    "id": "product_operational",
    "name": "Product & Strategic Velocity",
    "lensDescription": "Evaluates delivery timelines, developer cognitive load, blast radius, and total lifecycle costs.",
    "axes": [
      {
        "id": "strat_axis_1",
        "title": "...",
        "thesis": "...",
        "antithesis": "...",
        "keyQuestions": ["..."],
        "weight": 0.85,
        "selected": true
      }
    ]
  }
}`

	userPrompt := fmt.Sprintf("Topic Dilemma: %s\n\nBackground Context: %s\n\nDecompose this topic now into valid JSON.", topic, userContext)

	reply, err := r.Respond(
		ctx,
		agent,
		topic,
		userPrompt,
		systemPrompt,
		nil,
		modelOverride,
	)
	if err != nil {
		// Logically fall back to heuristic decomposition on runner failure
		res := GenerateHeuristicDecomposition(topic, userContext)
		return &res, nil
	}

	parsed, parseErr := parseDecompositionJSON(reply.Content, topic)
	if parseErr != nil {
		res := GenerateHeuristicDecomposition(topic, userContext)
		return &res, nil
	}

	return parsed, nil
}

func parseDecompositionJSON(content, fallbackTopic string) (*DecompositionResult, error) {
	match := jsonExtractRegex.FindString(content)
	jsonStr := content
	if match != "" {
		jsonStr = match
	} else {
		// Strip markdown code fences if present
		jsonStr = strings.TrimPrefix(strings.TrimSpace(jsonStr), "```json")
		jsonStr = strings.TrimPrefix(jsonStr, "```")
		jsonStr = strings.TrimSuffix(jsonStr, "```")
		jsonStr = strings.TrimSpace(jsonStr)
	}

	var result DecompositionResult
	if err := json.Unmarshal([]byte(jsonStr), &result); err != nil {
		return nil, fmt.Errorf("failed to unmarshal decomposition json: %w", err)
	}

	if result.Topic == "" {
		result.Topic = fallbackTopic
	}
	if len(result.PerspectiveA.Axes) == 0 || len(result.PerspectiveB.Axes) == 0 {
		return nil, fmt.Errorf("decomposition missing axes in one or both perspectives")
	}

	// Ensure selected defaults to true
	for i := range result.PerspectiveA.Axes {
		result.PerspectiveA.Axes[i].Selected = true
		if result.PerspectiveA.Axes[i].Weight <= 0 {
			result.PerspectiveA.Axes[i].Weight = 0.8
		}
	}
	for i := range result.PerspectiveB.Axes {
		result.PerspectiveB.Axes[i].Selected = true
		if result.PerspectiveB.Axes[i].Weight <= 0 {
			result.PerspectiveB.Axes[i].Weight = 0.8
		}
	}

	return &result, nil
}

// GenerateHeuristicDecomposition provides deterministic, high-signal decomposition when LLMs are offline.
func GenerateHeuristicDecomposition(topic, userContext string) DecompositionResult {
	lower := strings.ToLower(topic + " " + userContext)

	var pA Perspective
	var pB Perspective

	pA = Perspective{
		ID:              "technical_structural",
		Name:            "Technical & Structural Architecture",
		LensDescription: "Evaluates formal correctness, state invariants, fault isolation, and low-level performance guarantees.",
		Axes: []ProblemAxis{
			{
				ID:         "axis_correctness_latency",
				Title:      "State Consistency & SLA Boundaries",
				Thesis:     "Enforce strict transactional invariants and zero data corruption boundaries.",
				Antithesis: "Decouple synchronous dependencies to preserve ultra-low latency (<20ms P99) and partition tolerance.",
				KeyQuestions: []string{
					"Can we accept eventual consistency, or do financial/audit invariants mandate synchronous locking?",
					"What is the quantitative blast radius if state divergence occurs across nodes?",
				},
				Weight:   0.9,
				Selected: true,
			},
			{
				ID:         "axis_fault_isolation",
				Title:      "Blast Radius & Fault Isolation",
				Thesis:     "Isolate failure domains using cellular boundaries and circuit breakers.",
				Antithesis: "Avoid distributed coordination overhead and multi-hop network hops.",
				KeyQuestions: []string{
					"How does the system behave under partial network degradation or downstream cascade failures?",
					"Is disaster recovery deterministic and testable via chaos engineering?",
				},
				Weight:   0.85,
				Selected: true,
			},
			{
				ID:         "axis_throughput_concurrency",
				Title:      "Concurrency & Scalability Ceiling",
				Thesis:     "Scale compute horizontally with stateless processing and lock-free data structures.",
				Antithesis: "Centralize ordering and serialization to avoid split-brain and reconciliation complexity.",
				KeyQuestions: []string{
					"What is the theoretical hardware saturation point under 10x traffic spikes?",
					"Does this architecture introduce memory thrashing or GC pause liabilities?",
				},
				Weight:   0.8,
				Selected: true,
			},
		},
	}

	pB = Perspective{
		ID:              "product_operational",
		Name:            "Product & Strategic Velocity",
		LensDescription: "Evaluates delivery timelines, developer cognitive load, blast radius, and total lifecycle costs.",
		Axes: []ProblemAxis{
			{
				ID:         "axis_dev_ergonomics",
				Title:      "Cognitive Load & Hiring Velocity",
				Thesis:     "Adopt familiar, battle-tested paradigms that allow junior/mid engineers to ship safely.",
				Antithesis: "Invest in high-leverage esoteric technologies that offer a 10x operational advantage once mastered.",
				KeyQuestions: []string{
					"How many weeks does onboarding take before a new hire can deploy production code safely?",
					"Does the candidate talent pool support our hiring requirements over the next 24 months?",
				},
				Weight:   0.85,
				Selected: true,
			},
			{
				ID:         "axis_tco_migration",
				Title:      "Migration Blast Radius & TCO",
				Thesis:     "Minimize capital expenditures and migration risk via incremental strangler fig patterns.",
				Antithesis: "Perform a clean greenfield transition to avoid paying indefinite dual-stack maintenance taxes.",
				KeyQuestions: []string{
					"What is the runaway operational maintenance cost if the migration timeline doubles?",
					"Can the migration be aborted or rolled back at any milestone without data loss?",
				},
				Weight:   0.8,
				Selected: true,
			},
			{
				ID:         "axis_time_to_market",
				Title:      "Time-to-Market vs. Technical Debt",
				Thesis:     "Ship an MVP immediately to capture market window and validate user appetite.",
				Antithesis: "Build robust foundational abstractions to prevent architectural bankruptcy in 12 months.",
				KeyQuestions: []string{
					"What is the cost of delay per month of delayed launch?",
					"Are the deliberate trade-offs documented with explicit refactoring criteria?",
				},
				Weight:   0.75,
				Selected: true,
			},
		},
	}

	// Refine titles if database or language topic
	if strings.Contains(lower, "database") || strings.Contains(lower, "sql") || strings.Contains(lower, "postgres") || strings.Contains(lower, "mongo") {
		pA.Axes[0].Title = "ACID Guarantees vs. Read/Write Throughput"
		pB.Axes[1].Title = "Data Migration Complexity & Zero-Downtime Cutover"
	} else if languageTopicRegex.MatchString(lower) {
		pA.Axes[0].Title = "Memory Safety & Concurrency Primitives"
		pB.Axes[0].Title = "Ecosystem Maturity & Ramp-Up Curve"
	}

	return DecompositionResult{
		Topic:        topic,
		PerspectiveA: pA,
		PerspectiveB: pB,
	}
}
