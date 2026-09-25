package socratic

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"time"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

var jsonExtractRegex = regexp.MustCompile(`(?s)\{.*"questions".*\}`)

// BuildSocraticSystemPrompt constructs the high-discipline Socratic system prompt.
func BuildSocraticSystemPrompt(persona model.Agent, stance model.SocraticStance, stage model.SocraticStage, topic string) string {
	stanceDirective := ""
	switch stance {
	case model.StanceRuthlessElenchus:
		stanceDirective = `STANCE: RUTHLESS_ELENCHUS (Classic Elenchus: Contradiction & Falsification Engine).
Your objective is to expose hidden contradictions, unwarranted assumptions, and fatal edge cases in the user's thesis.
Formulate counter-examples that reduce the premise to absurdity. Relentlessly probe race conditions, network splits, and cascade failures.`

	case model.StanceMaieuticArchitect:
		stanceDirective = `STANCE: MAIEUTIC_ARCHITECT (Maieutic Architecture: Midwife of Invariants).
Your objective is to draw out latent, unspoken requirements and scale boundaries that the user knows intuitively but hasn't formalized.
Invert vague intuition into formal mathematical boundaries (e.g. latency ceilings, consistency windows, fault isolation domains).`

	case model.StanceFirstPrinciples:
		stanceDirective = `STANCE: FIRST_PRINCIPLES (Radical First Principles: Axiomatic Deconstruction).
Your objective is to strip away vendor hype, buzzwords, cargo-cult patterns, and leaky abstractions down to physics, information theory, and raw hardware cost.
Challenge every library and dependency: What physical limit or mathematical necessity forces this choice?`

	case model.StanceAdversarialRedTeam:
		stanceDirective = `STANCE: ADVERSARIAL_RED_TEAM (Adversarial Red-Team: Malicious Saboteur).
Your objective is to stress-test trust boundaries, single points of failure, insider threats, and catastrophe recovery.
Adopt hostile entropy: Probe privilege escalation, configuration poisoning, and fail-open vs fail-closed security traps.`

	case model.StanceAporiaBoundaryPusher:
		stanceDirective = `STANCE: APORIA_BOUNDARY_PUSHER (Aporia & Extreme Scale: Asymptotic Pressure).
Your objective is to catapult the dilemma into extreme load and catastrophe where incremental patches fail completely.
Force the user to confront irreducible systemic impasses at 100x volume, 48-hour provider outages, or total zero-day obsolescence.`

	default:
		stanceDirective = `STANCE: RIGOROUS SOCRATIC INQUIRY. Challenge implicit assumptions and probe edge cases.`
	}

	stageDirective := ""
	switch stage {
	case model.StageHypothesisExtraction:
		stageDirective = "CURRENT STAGE: 1. HYPOTHESIS EXTRACTION. Help the user state an unambiguous, testable thesis with concrete success criteria."
	case model.StageAssumptionSurfacing:
		stageDirective = "CURRENT STAGE: 2. ASSUMPTION SURFACING. Uncover unstated axioms, trusted dependencies, and environmental assumptions."
	case model.StageElenchusStressTesting:
		stageDirective = "CURRENT STAGE: 3. ELENCHUS STRESS-TESTING. Attack surfaced assumptions with concrete adversarial counter-examples and failure scenarios."
	case model.StageAporiaReconciliation:
		stageDirective = "CURRENT STAGE: 4. APORIA RECONCILIATION. Confront irreducible trade-offs (CAP theorem, speed vs safety) where no silver bullet exists. Force explicit sacrifices."
	case model.StageMaieuticHardening:
		stageDirective = "CURRENT STAGE: 5. MAIEUTIC HARDENING. Consolidate defended invariants and crystallized architecture into a definitive thesis."
	default:
		stageDirective = "CURRENT STAGE: SOCRATIC DIALOGUE."
	}

	return fmt.Sprintf(`You are Socrates, operating through the persona of "%s" (%s).
%s

%s

CORE PROTOCOL — BREVIS INTERROGATIO (MANDATORY):
1. Brevity Mandate: Reflect on the user's statement in AT MOST 2 to 3 concise, analytical sentences.
2. Question Mandate: Ask EXACTLY 1 or 2 penetrating, non-leading questions that force the user to confront an assumption or boundary.
3. Anti-Solution Mandate: DO NOT write code for the user. DO NOT solve the problem for them. Force the user to construct the solution.
4. Epistemic Ledger: Identify which axioms are UNDER_SIEGE (being questioned), which are HARDENED (defended by user), and which are CONCEDED (admitted as flaws).

CRITICAL: Return ONLY a valid JSON object matching this schema with no markdown fences or preamble:
{
  "reflection": "2-3 sentence reflection examining the user's latest claim...",
  "questions": [
    "First penetrating question probing the hidden invariant?",
    "Optional second question on scale or failure mode?"
  ],
  "newStage": "%s",
  "ledgerUpdates": [
    {
      "id": "ax_...",
      "type": "UNDER_SIEGE | HARDENED | CONCEDED",
      "statement": "Concise statement of the axiom",
      "rationale": "Brief rationale for status"
    }
  ],
  "discoveredBlindSpots": [
    "Specific blind spot exposed in this turn"
  ],
  "defendedInvariants": [
    "Specific invariant successfully defended by user"
  ]
}`, persona.Role, persona.DisplayName, stanceDirective, stageDirective, stage)
}

// ExecuteTurn dispatches the Socratic turn to the runner or falls back to heuristic generation.
func ExecuteTurn(
	ctx context.Context,
	r runner.AgentRunner,
	agent model.Agent,
	req SocraticTurnRequest,
	transcript []model.DebateMessage,
	modelOverride string,
) (*SocraticTurnResponse, error) {
	turnNum := len(transcript)/2 + 1

	if r == nil || agent.Provider == "" {
		res := HeuristicSocraticTurn(req.Topic, req.Message, req.Stance, req.Stage, turnNum)
		return &res, nil
	}

	systemPrompt := BuildSocraticSystemPrompt(agent, req.Stance, req.Stage, req.Topic)
	userPrompt := fmt.Sprintf("TOPIC: %s\nCURRENT STAGE: %s\nSTANCE: %s\n\nUSER STATEMENT: %s",
		req.Topic, req.Stage, req.Stance, req.Message)

	reply, err := r.Respond(ctx, agent, req.Topic, userPrompt, systemPrompt, transcript, modelOverride)
	if err != nil {
		res := HeuristicSocraticTurn(req.Topic, req.Message, req.Stance, req.Stage, turnNum)
		return &res, nil
	}

	parsed, parseErr := parseTurnJSON(reply.Content, req.Stage, req.Topic, req.Message, req.Stance, turnNum)
	if parseErr != nil {
		res := HeuristicSocraticTurn(req.Topic, req.Message, req.Stance, req.Stage, turnNum)
		return &res, nil
	}

	return parsed, nil
}

type socraticRawJSON struct {
	Reflection           string                     `json:"reflection"`
	Questions            []string                   `json:"questions"`
	NewStage             string                     `json:"newStage"`
	LedgerUpdates        []model.SocraticLedgerItem `json:"ledgerUpdates"`
	DiscoveredBlindSpots []string                   `json:"discoveredBlindSpots"`
	DefendedInvariants   []string                   `json:"defendedInvariants"`
}

func parseTurnJSON(
	content string,
	currentStage model.SocraticStage,
	topic, message string,
	stance model.SocraticStance,
	turnNum int,
) (*SocraticTurnResponse, error) {
	match := jsonExtractRegex.FindString(content)
	jsonStr := content
	if match != "" {
		jsonStr = match
	} else {
		jsonStr = strings.TrimPrefix(strings.TrimSpace(jsonStr), "```json")
		jsonStr = strings.TrimPrefix(jsonStr, "```")
		jsonStr = strings.TrimSuffix(jsonStr, "```")
		jsonStr = strings.TrimSpace(jsonStr)
	}

	var raw socraticRawJSON
	if err := json.Unmarshal([]byte(jsonStr), &raw); err != nil {
		return nil, fmt.Errorf("failed to unmarshal socratic turn json: %w", err)
	}

	if len(raw.Questions) == 0 {
		return nil, fmt.Errorf("no questions extracted from socratic reply")
	}

	// Format combined probe question with reflection
	var sb strings.Builder
	if strings.TrimSpace(raw.Reflection) != "" {
		sb.WriteString(strings.TrimSpace(raw.Reflection))
		sb.WriteString("\n\n")
	}
	for i, q := range raw.Questions {
		if len(raw.Questions) == 1 {
			sb.WriteString(strings.TrimSpace(q))
		} else {
			sb.WriteString(fmt.Sprintf("%d. %s\n", i+1, strings.TrimSpace(q)))
		}
	}

	stage := model.SocraticStage(raw.NewStage)
	if stage == "" {
		stage = advanceStage(currentStage, turnNum)
	}

	// Validate / fix ledger updates IDs
	for i := range raw.LedgerUpdates {
		if raw.LedgerUpdates[i].ID == "" {
			raw.LedgerUpdates[i].ID = fmt.Sprintf("ax_%d_%d", turnNum, i+1)
		}
		raw.LedgerUpdates[i].Turn = turnNum
	}

	return &SocraticTurnResponse{
		ProbeQuestion:        strings.TrimSpace(sb.String()),
		NewStage:             stage,
		LedgerUpdates:        raw.LedgerUpdates,
		DiscoveredBlindSpots: raw.DiscoveredBlindSpots,
		DefendedInvariants:   raw.DefendedInvariants,
	}, nil
}

func advanceStage(current model.SocraticStage, turnNum int) model.SocraticStage {
	switch {
	case turnNum <= 2:
		return model.StageHypothesisExtraction
	case turnNum <= 4:
		return model.StageAssumptionSurfacing
	case turnNum <= 6:
		return model.StageElenchusStressTesting
	case turnNum <= 8:
		return model.StageAporiaReconciliation
	default:
		return model.StageMaieuticHardening
	}
}

// HeuristicSocraticTurn provides deterministic high-signal responses when running offline.
func HeuristicSocraticTurn(
	topic, message string,
	stance model.SocraticStance,
	stage model.SocraticStage,
	turnNum int,
) SocraticTurnResponse {
	lowerMsg := strings.ToLower(message)
	newStage := advanceStage(stage, turnNum)

	var reflection string
	var questions []string
	var ledger []model.SocraticLedgerItem
	var blindSpots []string
	var defended []string

	timestamp := time.Now().UnixNano() % 10000

	switch stance {
	case model.StanceRuthlessElenchus:
		reflection = "Your premise asserts stability under nominal conditions. However, every invariant must be tested at the point of catastrophic boundary failure."
		questions = []string{
			"If a split-brain network partition persists across AZs for 45 seconds, which component guarantees zero silent state divergence?",
			"What exact metric tells you the system has entered an unrecoverable cascading failure mode?",
		}
		ledger = []model.SocraticLedgerItem{
			{
				ID:        fmt.Sprintf("ax_el_%d", timestamp),
				Type:      model.LedgerItemUnderSiege,
				Statement: "Partition tolerance without silent state divergence",
				Turn:      turnNum,
				Rationale: "Challenging split-brain resilience under cross-AZ network partitions",
			},
		}
		blindSpots = []string{"Cross-AZ split-brain convergence latency"}

	case model.StanceMaieuticArchitect:
		reflection = "You are describing the operational surface, but the underlying lifecycle guarantees remain unformalized."
		questions = []string{
			"At what quantitative throughput ceiling does this architecture cross from acceptable queuing into tail-latency collapse?",
			"What is the formal blast radius if this specific subsystem fails ungracefully?",
		}
		ledger = []model.SocraticLedgerItem{
			{
				ID:        fmt.Sprintf("ax_ma_%d", timestamp),
				Type:      model.LedgerItemUnderSiege,
				Statement: "Throughput ceiling and queuing bounds",
				Turn:      turnNum,
				Rationale: "Formalizing saturation limits and blast radius containment",
			},
		}
		defended = []string{"Deterministic failure domain isolation"}

	case model.StanceFirstPrinciples:
		reflection = "Let us strip away the vendor libraries and cargo-cult terminology down to basic compute, memory, and I/O physics."
		questions = []string{
			"What fundamental mathematical or physical constraint prevents handling this workload in a single multi-threaded process with shared memory?",
			"What is the quantitative dollar TCO increase per 1,000 requests compared to the simplest monolithic design?",
		}
		ledger = []model.SocraticLedgerItem{
			{
				ID:        fmt.Sprintf("ax_fp_%d", timestamp),
				Type:      model.LedgerItemUnderSiege,
				Statement: "Axiomatic hardware and memory scaling bounds",
				Turn:      turnNum,
				Rationale: "Deconstructing distributed coordination overhead to raw physical limits",
			},
		}
		blindSpots = []string{"Serialization and network RPC overhead relative to memory access"}

	case model.StanceAdversarialRedTeam:
		reflection = "You are assuming all internal participants and service tokens are benign and non-compromised. Zero-trust assumes hostile internal actors."
		questions = []string{
			"If an attacker gains read-only access to your config environment variables, what prevents them from forging auth tokens or escalating privilege?",
			"Does your system fail closed or fail open when the authorization service becomes unreachable?",
		}
		ledger = []model.SocraticLedgerItem{
			{
				ID:        fmt.Sprintf("ax_rt_%d", timestamp),
				Type:      model.LedgerItemUnderSiege,
				Statement: "Fail-closed authorization invariants",
				Turn:      turnNum,
				Rationale: "Verifying behavior during auth gateway degradation",
			},
		}
		blindSpots = []string{"Fail-open behavior during auth cluster timeout"}

	case model.StanceAporiaBoundaryPusher:
		reflection = "Your design addresses today's order of magnitude. In asymptotic engineering, scale breaks architectural assumptions non-linearly."
		questions = []string{
			"If traffic surges by 100x within 90 seconds due to a viral cascade, which database lock or socket pool exhausts first?",
			"Can the architecture survive a 48-hour global outage of your primary cloud vendor without total service loss?",
		}
		ledger = []model.SocraticLedgerItem{
			{
				ID:        fmt.Sprintf("ax_ap_%d", timestamp),
				Type:      model.LedgerItemUnderSiege,
				Statement: "Asymptotic 100x traffic surge boundary",
				Turn:      turnNum,
				Rationale: "Testing non-linear resource exhaustion cliffs",
			},
		}
		blindSpots = []string{"Connection pool exhaustion cliff under viral traffic spikes"}

	default:
		reflection = "Let us examine the foundational premise of this design."
		questions = []string{"What is the single most vulnerable assumption this proposal relies upon?"}
		ledger = []model.SocraticLedgerItem{
			{
				ID:        fmt.Sprintf("ax_gen_%d", timestamp),
				Type:      model.LedgerItemUnderSiege,
				Statement: "Core proposal viability",
				Turn:      turnNum,
			},
		}
	}

	// Refine heuristics if user concedes or defends
	if strings.Contains(lowerMsg, "concede") || strings.Contains(lowerMsg, "won't work") || strings.Contains(lowerMsg, "admit") {
		ledger = append(ledger, model.SocraticLedgerItem{
			ID:        fmt.Sprintf("ax_c_%d", timestamp),
			Type:      model.LedgerItemConceded,
			Statement: "Abandoned naive scaling assumption",
			Turn:      turnNum,
			Rationale: "User conceded architectural limitation under questioning",
		})
	} else if strings.Contains(lowerMsg, "guarantee") || strings.Contains(lowerMsg, "invariants") || strings.Contains(lowerMsg, "proven") {
		ledger = append(ledger, model.SocraticLedgerItem{
			ID:        fmt.Sprintf("ax_h_%d", timestamp),
			Type:      model.LedgerItemHardened,
			Statement: "Hardened transactional consistency invariant",
			Turn:      turnNum,
			Rationale: "User successfully articulated formal defense",
		})
	}

	var sb strings.Builder
	sb.WriteString(reflection)
	sb.WriteString("\n\n")
	for i, q := range questions {
		if len(questions) == 1 {
			sb.WriteString(q)
		} else {
			sb.WriteString(fmt.Sprintf("%d. %s\n", i+1, q))
		}
	}

	return SocraticTurnResponse{
		ProbeQuestion:        strings.TrimSpace(sb.String()),
		NewStage:             newStage,
		LedgerUpdates:        ledger,
		DiscoveredBlindSpots: blindSpots,
		DefendedInvariants:   defended,
	}
}
