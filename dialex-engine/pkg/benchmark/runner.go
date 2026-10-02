package benchmark

import (
	"context"
	"fmt"
	"math"
	"strings"
	"sync"
	"time"

	"dialex/pkg/model"
	"dialex/pkg/orchestrator"
	"dialex/pkg/runner"
)

// Runner orchestrates the execution of Arm A (Solo) and Arm B (Council).
type Runner struct {
	runnerFor func(agent model.Agent) runner.AgentRunner
	// AllowJudgeOverlap permits a judge from the same family as an arm (e.g. you only have one
	// API key). The run is then marked JudgeOverlap so its scores are not read as unbiased.
	AllowJudgeOverlap bool
	// CouncilIndependence limits what council seats see of each other (blind first round,
	// anonymized peers). nil = open, the original behaviour.
	CouncilIndependence *model.IndependenceConfig
}

// IndependenceLabel names an independence setting for run records.
func IndependenceLabel(c *model.IndependenceConfig) string {
	switch {
	case c == nil, !c.BlindFirstRound && !c.AnonymizeTranscript:
		return "open"
	case c.BlindFirstRound && c.AnonymizeTranscript:
		return "blind+anon"
	case c.BlindFirstRound:
		return "blind"
	}
	return "anon"
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
	return r.ExecuteRunWithBaseline(ctx, bCase, BaselineSolo, soloAgent, councilAgents, judgeAgent, rounds, onProgress)
}

// ExecuteRunWithBaseline is ExecuteRun with a choice of baseline arm. BaselineSelfConsistency
// spends the same number of model calls as the council, so a council win cannot be put down
// to extra compute alone.
func (r *Runner) ExecuteRunWithBaseline(
	ctx context.Context,
	bCase BenchmarkCase,
	baseline string,
	soloAgent model.Agent,
	councilAgents []model.Agent,
	judgeAgent model.Agent,
	rounds int,
	onProgress func(phase string, progress float64),
) (*BenchmarkRun, error) {
	if rounds <= 0 {
		rounds = 2
	}
	if baseline == "" {
		baseline = BaselineSolo
	}
	if baseline != BaselineSolo && baseline != BaselineSelfConsistency {
		return nil, fmt.Errorf("unknown baseline %q", baseline)
	}
	conflict := JudgeConflict(judgeAgent, append([]model.Agent{soloAgent}, councilAgents...)...)
	if conflict != nil && !r.AllowJudgeOverlap {
		return nil, judgeConflictError(judgeAgent, conflict)
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
		if baseline == BaselineSelfConsistency {
			soloRes, soloErr = r.runSelfConsistencyArm(ctx, bCase, soloAgent, rounds*len(councilAgents))
		} else {
			soloRes, soloErr = r.runSoloArm(ctx, bCase, soloAgent)
		}
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

	fallbacks := 0
	for _, e := range evaluations {
		if e.Fallback {
			fallbacks++
		}
	}
	tokenRatio := 0.0
	if soloRes.TokensUsed > 0 {
		tokenRatio = math.Round(float64(councilRes.TokensUsed)/float64(soloRes.TokensUsed)*100) / 100
	}

	return &BenchmarkRun{
		Baseline:            baseline,
		Independence:        IndependenceLabel(r.CouncilIndependence),
		TokenRatio:          tokenRatio,
		JudgeFallbackPasses: fallbacks,
		JudgeOverlap:        conflict != nil,
		ID:                  runID,
		CaseID:              bCase.ID,
		CaseTitle:           bCase.Title,
		Timestamp:           time.Now().UnixMilli(),
		SoloResult:          soloRes,
		CouncilResult:       councilRes,
		JudgeModel:          judgeAgent.Model,
		Evaluations:         evaluations,
		SoloTotalScore:      soloScore,
		CouncilTotalScore:   councilScore,
		DeltaQ:              deltaQ,
		Winner:              winner,
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

	// seats need distinct IDs so several personas on one provider stay distinct
	agents = append([]model.Agent(nil), agents...)
	for i := range agents {
		if agents[i].ID == "" {
			agents[i].ID = fmt.Sprintf("bench_seat_%d", i)
		}
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

			reply, err := rnr.Respond(ctx, agent, bCase.Title, topicContext, rolePrompt,
				orchestrator.ApplyIndependence(transcript, agent, round, r.CouncilIndependence, agents), "")
			if err != nil {
				return ArmResult{}, err
			}

			if reply.TokensIn != nil && reply.TokensOut != nil {
				totalTokens += *reply.TokensIn + *reply.TokensOut
			} else {
				totalTokens += len(reply.Content) / 4
			}

			transcript = append(transcript, model.DebateMessage{
				SeatID:            agent.ID,
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
		ArmType:               ArmCouncil,
		ModelOrCouncil:        fmt.Sprintf("Council of %d (%s)", len(agents), strings.Join(councilNames, ", ")),
		Deliverable:           reply.Content,
		TokensUsed:            totalTokens,
		DurationMs:            time.Since(start).Milliseconds(),
		EstimatedCostUSD:      cost,
		Convergence:           RoundConvergence(transcript, rounds),
		FirstRoundConvergence: RoundConvergence(transcript, 1),
	}, nil
}

// runSelfConsistencyArm draws `draws` independent solo drafts, then one aggregation call by the
// same model that merges them. Model calls = draws+1, matching a council of `draws` turns plus
// a synthesis turn.
//
// ponytail: drafts run sequentially; parallelise if latency of the baseline matters.
func (r *Runner) runSelfConsistencyArm(ctx context.Context, bCase BenchmarkCase, agent model.Agent, draws int) (ArmResult, error) {
	start := time.Now()
	rnr := r.runnerFor(agent)
	if rnr == nil {
		return ArmResult{}, fmt.Errorf("no runner available for baseline agent %s", agent.Label())
	}
	if draws < 1 {
		draws = 1
	}

	prompt := fmt.Sprintf(`You are a world-class Principal Systems Architect. Produce a definitive Architecture Decision Record (ADR) for:
TITLE: %s
DOMAIN: %s
DILEMMA: %s
CONSTRAINTS:
- %s`, bCase.Title, bCase.Domain, bCase.Dilemma, strings.Join(bCase.Constraints, "\n- "))

	tokens := 0
	count := func(in, out string, reply runner.AgentReply) {
		if reply.TokensIn != nil && reply.TokensOut != nil {
			tokens += *reply.TokensIn + *reply.TokensOut
		} else {
			tokens += len(in)/4 + len(out)/4
		}
	}

	var drafts []string
	for i := 0; i < draws; i++ {
		reply, err := rnr.Respond(ctx, agent, bCase.Title, prompt, "", nil, "")
		if err != nil {
			return ArmResult{}, err
		}
		count(prompt, reply.Content, reply)
		drafts = append(drafts, reply.Content)
	}

	var agg strings.Builder
	agg.WriteString("Below are independent drafts of the same ADR. Produce one final ADR: keep what the drafts agree on, resolve disagreements explicitly, and drop errors.\n\n")
	for i, d := range drafts {
		fmt.Fprintf(&agg, "--- DRAFT %d ---\n%s\n\n", i+1, d)
	}
	reply, err := rnr.Respond(ctx, agent, bCase.Title, agg.String(), "Aggregation Mode", nil, "")
	if err != nil {
		return ArmResult{}, err
	}
	count(agg.String(), reply.Content, reply)

	return ArmResult{
		ArmType:          ArmSelfConsistency,
		ModelOrCouncil:   fmt.Sprintf("%s (%s) self-consistency x%d", agent.Label(), agent.Model, draws),
		Deliverable:      reply.Content,
		TokensUsed:       tokens,
		DurationMs:       time.Since(start).Milliseconds(),
		EstimatedCostUSD: float64(tokens) * 0.000015,
	}, nil
}
