package benchmark

import (
	"dialex/pkg/model"
)

// MetricDimension identifies one of the four orthogonal evaluation dimensions.
type MetricDimension string

const (
	MetricFactuality    MetricDimension = "FACTUALITY"
	MetricBlindSpots    MetricDimension = "BLIND_SPOTS"
	MetricTradeOffs     MetricDimension = "TRADE_OFFS"
	MetricActionability MetricDimension = "ACTIONABILITY"
)

// ArmType identifies which evaluation arm produced a deliverable.
type ArmType string

const (
	ArmSoloBaseline ArmType = "SOLO_BASELINE"
	ArmCouncil      ArmType = "COUNCIL"
)

// BenchmarkCase defines a standardized architectural dilemma test case.
type BenchmarkCase struct {
	ID                          string   `json:"id"`
	Title                       string   `json:"title"`
	Domain                      string   `json:"domain"`
	Dilemma                     string   `json:"dilemma"`
	Constraints                 []string `json:"constraints"`
	GroundTruthTraps            []string `json:"groundTruthTraps"`
	RequiredTradeOffAxes        []string `json:"requiredTradeOffAxes"`
	MandatoryBoundaryConditions []string `json:"mandatoryBoundaryConditions"`
	IsBundled                   bool     `json:"isBundled"`
}

// ArmResult holds execution metrics and synthesized output from one arm.
type ArmResult struct {
	ArmType          ArmType `json:"armType"`
	ModelOrCouncil   string  `json:"modelOrCouncil"`
	Deliverable      string  `json:"deliverable"`
	TokensUsed       int     `json:"tokensUsed"`
	DurationMs       int64   `json:"durationMs"`
	EstimatedCostUSD float64 `json:"estimatedCostUSD"`
}

// MetricScore is a scored metric dimension with critique rationale.
type MetricScore struct {
	Dimension MetricDimension `json:"dimension"`
	Score     float64         `json:"score"` // 0.0 - 10.0
	Critique  string          `json:"critique"`
}

// JudgeEvaluation holds the result of one blinded judge evaluation pass.
type JudgeEvaluation struct {
	PassNumber       int           `json:"passNumber"` // 1 or 2 (order swap)
	Order            string        `json:"order"`      // "COUNCIL_FIRST" or "SOLO_FIRST"
	SoloScores       []MetricScore `json:"soloScores"`
	CouncilScores    []MetricScore `json:"councilScores"`
	OverallVerdict   string        `json:"overallVerdict"`
	DetailedCritique string        `json:"detailedCritique"`
}

// BenchmarkRun represents a completed dual-arm benchmark execution.
type BenchmarkRun struct {
	ID                string            `json:"id"`
	CaseID            string            `json:"caseId"`
	CaseTitle         string            `json:"caseTitle"`
	Timestamp         int64             `json:"timestamp"`
	SoloResult        ArmResult         `json:"soloResult"`
	CouncilResult     ArmResult         `json:"councilResult"`
	JudgeModel        string            `json:"judgeModel"`
	Evaluations       []JudgeEvaluation `json:"evaluations"`
	SoloTotalScore    float64           `json:"soloTotalScore"`
	CouncilTotalScore float64           `json:"councilTotalScore"`
	DeltaQ            float64           `json:"deltaQ"` // CouncilTotalScore - SoloTotalScore
	Winner            string            `json:"winner"` // "COUNCIL", "SOLO", "TIE"
}

// BenchmarkSummary holds aggregate metrics and scientific statistical hypothesis tests.
type BenchmarkSummary struct {
	TotalRuns          int     `json:"totalRuns"`
	CouncilWins        int     `json:"councilWins"`
	SoloWins           int     `json:"soloWins"`
	Ties               int     `json:"ties"`
	CouncilWinRate     float64 `json:"councilWinRate"`
	MeanDeltaQ         float64 `json:"meanDeltaQ"`
	PValue             float64 `json:"pValue"`
	IsStatSignificant  bool    `json:"isStatSignificant"` // p < 0.05
	AvgFactualityDelta float64 `json:"avgFactualityDelta"`
	AvgBlindSpotDelta  float64 `json:"avgBlindSpotDelta"`
	AvgTradeOffDelta   float64 `json:"avgTradeOffDelta"`
	AvgActionDelta     float64 `json:"avgActionDelta"`
}

// RunBenchmarkRequest is the input payload to trigger a benchmark evaluation.
type RunBenchmarkRequest struct {
	CaseID        string        `json:"caseId"`
	SoloAgent     *model.Agent  `json:"soloAgent,omitempty"`
	CouncilAgents []model.Agent `json:"councilAgents,omitempty"`
	JudgeAgent    *model.Agent  `json:"judgeAgent,omitempty"`
	Rounds        int           `json:"rounds,omitempty"`
}
