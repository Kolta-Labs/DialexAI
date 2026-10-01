package benchmark

import (
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

var jsonBlockRegex = regexp.MustCompile(`(?s)\{.*"submission_a".*"submission_b".*\}`)

type rawSubmissionScore struct {
	Factuality    float64 `json:"factuality"`
	BlindSpots    float64 `json:"blind_spots"`
	TradeOffs     float64 `json:"trade_offs"`
	Actionability float64 `json:"actionability"`
	Critique      string  `json:"critique"`
}

type rawJudgeResponse struct {
	SubmissionA      rawSubmissionScore `json:"submission_a"`
	SubmissionB      rawSubmissionScore `json:"submission_b"`
	OverallVerdict   string             `json:"overall_verdict"`
	DetailedCritique string             `json:"detailed_critique"`
}

// EvaluateDoubleBlind conducts the position-balanced dual-pass blinded evaluation.
// Pass 1: X = Council, Y = Solo
// Pass 2: X = Solo, Y = Council
func EvaluateDoubleBlind(
	ctx context.Context,
	judgeRunner runner.AgentRunner,
	judgeAgent model.Agent,
	bCase BenchmarkCase,
	soloDeliverable string,
	councilDeliverable string,
) ([]JudgeEvaluation, float64, float64, error) {
	// Clean deliverables to remove identifying markers
	cleanSolo := sanitizeSubmission(soloDeliverable)
	cleanCouncil := sanitizeSubmission(councilDeliverable)

	// Pass 1: Submission A = Council, Submission B = Solo
	pass1, err := runSingleJudgePass(ctx, judgeRunner, judgeAgent, bCase, cleanCouncil, cleanSolo, 1, "COUNCIL_FIRST")
	if err != nil {
		// Heuristic fallback if LLM judge fails
		pass1 = heuristicJudgePass(bCase, cleanCouncil, cleanSolo, 1, "COUNCIL_FIRST")
	}

	// Pass 2: Submission A = Solo, Submission B = Council (Position Swapped)
	pass2, err := runSingleJudgePass(ctx, judgeRunner, judgeAgent, bCase, cleanSolo, cleanCouncil, 2, "SOLO_FIRST")
	if err != nil {
		pass2 = heuristicJudgePass(bCase, cleanSolo, cleanCouncil, 2, "SOLO_FIRST")
	}

	evaluations := []JudgeEvaluation{pass1, pass2}

	// Compute average scores across both passes
	cFact := (getDimensionScore(pass1.CouncilScores, MetricFactuality) + getDimensionScore(pass2.CouncilScores, MetricFactuality)) / 2.0
	cBlind := (getDimensionScore(pass1.CouncilScores, MetricBlindSpots) + getDimensionScore(pass2.CouncilScores, MetricBlindSpots)) / 2.0
	cTrade := (getDimensionScore(pass1.CouncilScores, MetricTradeOffs) + getDimensionScore(pass2.CouncilScores, MetricTradeOffs)) / 2.0
	cAction := (getDimensionScore(pass1.CouncilScores, MetricActionability) + getDimensionScore(pass2.CouncilScores, MetricActionability)) / 2.0

	sFact := (getDimensionScore(pass1.SoloScores, MetricFactuality) + getDimensionScore(pass2.SoloScores, MetricFactuality)) / 2.0
	sBlind := (getDimensionScore(pass1.SoloScores, MetricBlindSpots) + getDimensionScore(pass2.SoloScores, MetricBlindSpots)) / 2.0
	sTrade := (getDimensionScore(pass1.SoloScores, MetricTradeOffs) + getDimensionScore(pass2.SoloScores, MetricTradeOffs)) / 2.0
	sAction := (getDimensionScore(pass1.SoloScores, MetricActionability) + getDimensionScore(pass2.SoloScores, MetricActionability)) / 2.0

	councilTotal := CalculateScore(cFact, cBlind, cTrade, cAction)
	soloTotal := CalculateScore(sFact, sBlind, sTrade, sAction)

	return evaluations, soloTotal, councilTotal, nil
}

func runSingleJudgePass(
	ctx context.Context,
	judgeRunner runner.AgentRunner,
	judgeAgent model.Agent,
	bCase BenchmarkCase,
	subA string,
	subB string,
	passNumber int,
	order string,
) (JudgeEvaluation, error) {
	if judgeRunner == nil {
		return heuristicJudgePass(bCase, subA, subB, passNumber, order), nil
	}

	prompt := buildJudgePrompt(bCase, subA, subB)
	reply, err := judgeRunner.Respond(ctx, judgeAgent, "Double-Blind Architecture Evaluation", prompt, "", nil, "")
	if err != nil {
		return JudgeEvaluation{}, err
	}

	resp, err := parseJudgeJSON(reply.Content)
	if err != nil {
		return heuristicJudgePass(bCase, subA, subB, passNumber, order), nil
	}

	return formatJudgeEvaluation(resp, passNumber, order), nil
}

func buildJudgePrompt(bCase BenchmarkCase, subA, subB string) string {
	trapsStr := "- " + strings.Join(bCase.GroundTruthTraps, "\n- ")
	tradeOffsStr := "- " + strings.Join(bCase.RequiredTradeOffAxes, "\n- ")
	boundariesStr := "- " + strings.Join(bCase.MandatoryBoundaryConditions, "\n- ")

	return fmt.Sprintf(`You are an impartial, world-class Chief Systems Architect serving as a double-blind evaluator.
Evaluate the two anonymous architectural submissions below for the following challenge:

CHALLENGE: %s
DOMAIN: %s
DESCRIPTION: %s

KNOWN GROUND TRUTH FAILURE TRAPS (Check if submissions fall for these):
%s

REQUIRED TRADE-OFF AXES:
%s

MANDATORY BOUNDARY CONDITIONS:
%s

----------------------------------------
SUBMISSION A:
%s
----------------------------------------
SUBMISSION B:
%s
----------------------------------------

SCORING CRITERIA (0.0 to 10.0 scale for each):
1. FACTUALITY (Weight: 30%%): Groundedness, absence of hallucinations, catching the ground truth traps.
2. BLIND_SPOTS (Weight: 25%%): Identifying failure cascades, boundary conditions, and edge cases.
3. TRADE_OFFS (Weight: 25%%): Depth of operational maintenance, cost, tail latency trade-offs.
4. ACTIONABILITY (Weight: 20%%): Concrete, implementable technical decisions vs vague consultant hand-waving.

You MUST reply ONLY with valid JSON in this exact schema (no markdown preamble):
{
  "submission_a": {
    "factuality": 8.0,
    "blind_spots": 7.5,
    "trade_offs": 8.0,
    "actionability": 8.5,
    "critique": "Critique of Submission A..."
  },
  "submission_b": {
    "factuality": 9.0,
    "blind_spots": 8.5,
    "trade_offs": 9.0,
    "actionability": 9.0,
    "critique": "Critique of Submission B..."
  },
  "overall_verdict": "Summary of which submission was superior and why...",
  "detailed_critique": "In-depth comparison of technical depth..."
}`, bCase.Title, bCase.Domain, bCase.Dilemma, trapsStr, tradeOffsStr, boundariesStr, subA, subB)
}

func parseJudgeJSON(content string) (*rawJudgeResponse, error) {
	match := jsonBlockRegex.FindString(content)
	if match == "" {
		match = content
	}

	var resp rawJudgeResponse
	if err := json.Unmarshal([]byte(match), &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

func formatJudgeEvaluation(resp *rawJudgeResponse, passNumber int, order string) JudgeEvaluation {
	scoresA := []MetricScore{
		{Dimension: MetricFactuality, Score: clampScore(resp.SubmissionA.Factuality), Critique: resp.SubmissionA.Critique},
		{Dimension: MetricBlindSpots, Score: clampScore(resp.SubmissionA.BlindSpots), Critique: resp.SubmissionA.Critique},
		{Dimension: MetricTradeOffs, Score: clampScore(resp.SubmissionA.TradeOffs), Critique: resp.SubmissionA.Critique},
		{Dimension: MetricActionability, Score: clampScore(resp.SubmissionA.Actionability), Critique: resp.SubmissionA.Critique},
	}

	scoresB := []MetricScore{
		{Dimension: MetricFactuality, Score: clampScore(resp.SubmissionB.Factuality), Critique: resp.SubmissionB.Critique},
		{Dimension: MetricBlindSpots, Score: clampScore(resp.SubmissionB.BlindSpots), Critique: resp.SubmissionB.Critique},
		{Dimension: MetricTradeOffs, Score: clampScore(resp.SubmissionB.TradeOffs), Critique: resp.SubmissionB.Critique},
		{Dimension: MetricActionability, Score: clampScore(resp.SubmissionB.Actionability), Critique: resp.SubmissionB.Critique},
	}

	var councilScores, soloScores []MetricScore
	if order == "COUNCIL_FIRST" {
		councilScores = scoresA
		soloScores = scoresB
	} else {
		soloScores = scoresA
		councilScores = scoresB
	}

	return JudgeEvaluation{
		PassNumber:       passNumber,
		Order:            order,
		SoloScores:       soloScores,
		CouncilScores:    councilScores,
		OverallVerdict:   resp.OverallVerdict,
		DetailedCritique: resp.DetailedCritique,
	}
}

// heuristicJudgePass provides deterministic algorithmic scoring when no LLM judge is configured.
func heuristicJudgePass(bCase BenchmarkCase, subA, subB string, passNumber int, order string) JudgeEvaluation {
	scoreA := scoreHeuristic(subA, bCase)
	scoreB := scoreHeuristic(subB, bCase)

	var councilScores, soloScores []MetricScore
	if order == "COUNCIL_FIRST" {
		councilScores = scoreA
		soloScores = scoreB
	} else {
		soloScores = scoreA
		councilScores = scoreB
	}

	return JudgeEvaluation{
		PassNumber:       passNumber,
		Order:            order,
		SoloScores:       soloScores,
		CouncilScores:    councilScores,
		OverallVerdict:   "Automated rubric evaluation based on ground truth coverage and trade-off depth.",
		DetailedCritique: "Programmatic text analysis evaluating trap avoidance and boundary conditions.",
		Fallback:         true,
	}
}

func scoreHeuristic(text string, bCase BenchmarkCase) []MetricScore {
	lower := strings.ToLower(text)

	// Factuality: starts at 8.0, penalized if ground truth traps are triggered, boosted if refuted
	factScore := 7.5
	for _, trap := range bCase.GroundTruthTraps {
		trapKeywords := strings.Fields(strings.ToLower(trap))
		if len(trapKeywords) >= 2 {
			k1, k2 := trapKeywords[0], trapKeywords[1]
			if strings.Contains(lower, k1) && strings.Contains(lower, k2) {
				// Check if refuted
				if strings.Contains(lower, "not") || strings.Contains(lower, "cannot") || strings.Contains(lower, "avoid") || strings.Contains(lower, "refute") {
					factScore += 0.5
				} else {
					factScore -= 0.5
				}
			}
		}
	}

	// Blind spots: check boundary conditions
	blindScore := 6.5
	for _, bc := range bCase.MandatoryBoundaryConditions {
		for _, w := range strings.Fields(strings.ToLower(bc)) {
			if len(w) > 4 && strings.Contains(lower, w) {
				blindScore += 0.4
				break
			}
		}
	}

	// Trade-offs: check required trade-off axes
	tradeScore := 7.0
	for _, axis := range bCase.RequiredTradeOffAxes {
		for _, w := range strings.Fields(strings.ToLower(axis)) {
			if len(w) > 4 && strings.Contains(lower, w) {
				tradeScore += 0.4
				break
			}
		}
	}

	// Actionability: check for concrete code/config/tables
	actionScore := 7.0
	if strings.Contains(text, "```") || strings.Contains(text, "|") {
		actionScore += 1.5
	}
	if strings.Contains(lower, "step 1") || strings.Contains(lower, "phase 1") {
		actionScore += 0.8
	}

	return []MetricScore{
		{Dimension: MetricFactuality, Score: clampScore(factScore), Critique: "Automated factuality check"},
		{Dimension: MetricBlindSpots, Score: clampScore(blindScore), Critique: "Automated boundary check"},
		{Dimension: MetricTradeOffs, Score: clampScore(tradeScore), Critique: "Automated trade-off check"},
		{Dimension: MetricActionability, Score: clampScore(actionScore), Critique: "Automated actionability check"},
	}
}

func sanitizeSubmission(s string) string {
	// Strip agent names, seat identifiers, turn headers
	lines := strings.Split(s, "\n")
	var cleaned []string
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "Seat ") || strings.HasPrefix(trimmed, "Round ") || strings.HasPrefix(trimmed, "[Facilitator]") || strings.HasPrefix(trimmed, "[Devil's Advocate]") {
			continue
		}
		cleaned = append(cleaned, line)
	}
	return strings.Join(cleaned, "\n")
}

func getDimensionScore(scores []MetricScore, dim MetricDimension) float64 {
	for _, s := range scores {
		if s.Dimension == dim {
			return s.Score
		}
	}
	return 7.0
}

func clampScore(s float64) float64 {
	if s < 0.0 {
		return 0.0
	}
	if s > 10.0 {
		return 10.0
	}
	return s
}
