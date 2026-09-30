package benchmark

import (
	"context"
	"fmt"
	"math"
	"strings"
	"sync"
	"time"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

// Runner orchestrates the execution of Arm A (Solo) and Arm B (Council).
type Runner struct {
	runnerFor func(agent model.Agent) runner.AgentRunner
}

// NewRunner creates a new benchmark runner with the runner resolution factory.
func NewRunner(runnerFor func(agent model.Agent) runner.AgentRunner) *Runner {
	return &Runner{runnerFor: runnerFor}
}

// ExecuteRun executes a complete dual-arm benchmark comparison for a given test case.
func (r *Runner) ExecuteRun(
	ctx context.Context,
	bCase BenchmarkCase,
	soloAgent model.Agent,
	councilAgents []model.Agent,
	judgeAgent model.Agent,
	rounds int,
	onProgress func(phase string, progress float64),
) (*BenchmarkRun, error) {
	if rounds <= 0 {
		rounds = 2
	}

	runID := fmt.Sprintf("run_%s_%d", bCase.ID, time.Now().UnixNano())

	if onProgress != nil {
		onProgress("Running Dual-Arm Execution", 0.1)
	}

	var soloRes ArmResult
	var councilRes ArmResult
	var soloErr, councilErr error

	var wg sync.WaitGroup
	wg.Add(2)

	// Run Arm A (Solo Baseline)
	go func() {
		defer wg.Done()
		soloRes, soloErr = r.runSoloArm(ctx, bCase, soloAgent)
	}()

	// Run Arm B (Council Deliberation)
	go func() {
		defer wg.Done()
		councilRes, councilErr = r.runCouncilArm(ctx, bCase, councilAgents, rounds)
	}()

	wg.Wait()

	if soloErr != nil {
		return nil, fmt.Errorf("solo arm failed: %w", soloErr)
	}
	if councilErr != nil {
		return nil, fmt.Errorf("council arm failed: %w", councilErr)
	}

	if onProgress != nil {
		onProgress("Double-Blind Judging", 0.7)
	}

	// Double-blind judging
	judgeRunner := r.runnerFor(judgeAgent)
	evaluations, soloScore, councilScore, err := EvaluateDoubleBlind(
		ctx,
		judgeRunner,
		judgeAgent,
		bCase,
		soloRes.Deliverable,
		councilRes.Deliverable,
	)
	if err != nil {
		return nil, fmt.Errorf("double-blind evaluation failed: %w", err)
	}

	deltaQ := math.Round((councilScore-soloScore)*100) / 100
	winner := "TIE"
	if deltaQ >= 0.50 {
		winner = "COUNCIL"
	} else if deltaQ <= -0.50 {
		winner = "SOLO"
	}

	if onProgress != nil {
		onProgress("Benchmark Completed", 1.0)
	}

	return &BenchmarkRun{
		ID:                runID,
		CaseID:            bCase.ID,
		CaseTitle:         bCase.Title,
		Timestamp:         time.Now().UnixMilli(),
		SoloResult:        soloRes,
		CouncilResult:     councilRes,
		JudgeModel:        judgeAgent.Model,
		Evaluations:       evaluations,
		SoloTotalScore:    soloScore,
		CouncilTotalScore: councilScore,
		DeltaQ:            deltaQ,
		Winner:            winner,
	}, nil
}

func (r *Runner) runSoloArm(ctx context.Context, bCase BenchmarkCase, agent model.Agent) (ArmResult, error) {
	start := time.Now()
	rnr := r.runnerFor(agent)
	if rnr == nil {
		return ArmResult{}, fmt.Errorf("no runner available for solo agent %s", agent.Label())
	}

	prompt := fmt.Sprintf(`You are a world-class Principal Systems Architect evaluating an architectural dilemma.
Analyze the following challenge thoroughly, consider all edge cases, failure cascades, and operational trade-offs, and produce a definitive, production-grade Architecture Decision Record (ADR):

TITLE: %s
DOMAIN: %s
DILEMMA: %s
CONSTRAINTS:
- %s

Please provide your complete, detailed architectural proposal with concrete component specifications, failure recovery steps, and trade-off matrices.`,
		bCase.Title, bCase.Domain, bCase.Dilemma, strings.Join(bCase.Constraints, "\n- "))

	reply, err := rnr.Respond(ctx, agent, bCase.Title, prompt, "", nil, "")
	if err != nil {
		return ArmResult{}, err
	}

	tokens := 0
	if reply.TokensIn != nil && reply.TokensOut != nil {
		tokens = *reply.TokensIn + *reply.TokensOut
	} else {
		// Heuristic approximation: 1 token ~ 4 chars
		tokens = len(prompt)/4 + len(reply.Content)/4
	}

	cost := float64(tokens) * 0.000015 // estimated blended rate

	return ArmResult{
		ArmType:          ArmSoloBaseline,
		ModelOrCouncil:   fmt.Sprintf("%s (%s)", agent.Label(), agent.Model),
		Deliverable:      reply.Content,
		TokensUsed:       tokens,
		DurationMs:       time.Since(start).Milliseconds(),
		EstimatedCostUSD: cost,
	}, nil
}

func (r *Runner) runCouncilArm(ctx context.Context, bCase BenchmarkCase, agents []model.Agent, rounds int) (ArmResult, error) {
	start := time.Now()
	if len(agents) == 0 {
		return ArmResult{}, fmt.Errorf("council must contain at least 1 agent")
	}

	var transcript []model.DebateMessage
	totalTokens := 0

	// Run sequential debate rounds among council members
	for round := 1; round <= rounds; round++ {
		for _, agent := range agents {
			rnr := r.runnerFor(agent)
			if rnr == nil {
				continue
			}

			topicContext := fmt.Sprintf("Architectural Dilemma: %s\nConstraints: %s", bCase.Dilemma, strings.Join(bCase.Constraints, "; "))
			rolePrompt := fmt.Sprintf("Role: %s. Rigorously critique prior claims, expose hidden assumptions, and focus on failure modes.", agent.Role)

			reply, err := rnr.Respond(ctx, agent, bCase.Title, topicContext, rolePrompt, transcript, "")
			if err != nil {
				return ArmResult{}, err
			}

			if reply.TokensIn != nil && reply.TokensOut != nil {
				totalTokens += *reply.TokensIn + *reply.TokensOut
			} else {
				totalTokens += len(reply.Content) / 4
			}

			transcript = append(transcript, model.DebateMessage{
				AuthorDisplayName: agent.Label(),
				Provider:          agent.Provider,
				AgentID:           agent.Provider,
				Content:           reply.Content,
				Round:             round,
				TimestampMs:       time.Now().UnixMilli(),
			})
		}
	}

	// Moderator synthesis turn
	moderator := agents[0]
	modRunner := r.runnerFor(moderator)
	synthesisPrompt := fmt.Sprintf(`As the Lead Moderator, synthesize the council's multi-round deliberation on:
TITLE: %s
DILEMMA: %s

Produce a definitive, production-grade Architecture Decision Record (ADR) resolving all surfaced tensions, documenting accepted trade-offs, and detailing concrete architecture specifications.`, bCase.Title, bCase.Dilemma)

	reply, err := modRunner.Respond(ctx, moderator, bCase.Title, synthesisPrompt, "Synthesis Mode", transcript, "")
	if err != nil {
		return ArmResult{}, err
	}

	if reply.TokensIn != nil && reply.TokensOut != nil {
		totalTokens += *reply.TokensIn + *reply.TokensOut
	} else {
		totalTokens += len(reply.Content) / 4
	}

	cost := float64(totalTokens) * 0.000015

	councilNames := make([]string, len(agents))
	for i, a := range agents {
		councilNames[i] = a.Label()
	}

	return ArmResult{
		ArmType:          ArmCouncil,
		ModelOrCouncil:   fmt.Sprintf("Council of %d (%s)", len(agents), strings.Join(councilNames, ", ")),
		Deliverable:      reply.Content,
		TokensUsed:       totalTokens,
		DurationMs:       time.Since(start).Milliseconds(),
		EstimatedCostUSD: cost,
	}, nil
}
