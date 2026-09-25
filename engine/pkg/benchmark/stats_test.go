package benchmark

import (
	"math"
	"testing"
)

func TestCalculateScore(t *testing.T) {
	// Factuality 30%, Blind Spots 25%, Trade-Offs 25%, Actionability 20%
	// 10*0.3 + 10*0.25 + 10*0.25 + 10*0.2 = 10.0
	score := CalculateScore(10, 10, 10, 10)
	if score != 10.0 {
		t.Fatalf("expected 10.0, got %f", score)
	}

	// 8*0.3 + 6*0.25 + 4*0.25 + 2*0.2 = 2.4 + 1.5 + 1.0 + 0.4 = 5.3
	score2 := CalculateScore(8, 6, 4, 2)
	if math.Abs(score2-5.3) > 1e-6 {
		t.Fatalf("expected 5.3, got %f", score2)
	}
}

func TestTTestPValue(t *testing.T) {
	// For large t-statistic (e.g. t = 4.0, df = 9), p-value should be very small (< 0.01)
	pVal := TwoTailedTTestPValue(4.0, 9.0)
	if pVal > 0.01 {
		t.Fatalf("expected p-value < 0.01 for t=4.0, df=9, got %f", pVal)
	}

	// For t = 0, p-value should be 1.0
	pValZero := TwoTailedTTestPValue(0.0, 9.0)
	if math.Abs(pValZero-1.0) > 1e-4 {
		t.Fatalf("expected p-value 1.0 for t=0, got %f", pValZero)
	}

	// For standard t ~ 2.262 at df = 9, two-tailed p ~ 0.05
	pValCritical := TwoTailedTTestPValue(2.262, 9.0)
	if math.Abs(pValCritical-0.05) > 0.005 {
		t.Fatalf("expected p-value ~ 0.05, got %f", pValCritical)
	}
}

func TestComputeSummary(t *testing.T) {
	runs := []BenchmarkRun{
		{
			ID:                "run-1",
			CaseID:            "DB01",
			Winner:            "COUNCIL",
			SoloTotalScore:    6.0,
			CouncilTotalScore: 8.5,
			Evaluations: []JudgeEvaluation{
				{
					PassNumber: 1,
					SoloScores: []MetricScore{
						{Dimension: MetricFactuality, Score: 6.0},
						{Dimension: MetricBlindSpots, Score: 6.0},
						{Dimension: MetricTradeOffs, Score: 6.0},
						{Dimension: MetricActionability, Score: 6.0},
					},
					CouncilScores: []MetricScore{
						{Dimension: MetricFactuality, Score: 8.5},
						{Dimension: MetricBlindSpots, Score: 8.5},
						{Dimension: MetricTradeOffs, Score: 8.5},
						{Dimension: MetricActionability, Score: 8.5},
					},
				},
			},
		},
		{
			ID:                "run-2",
			CaseID:            "DB02",
			Winner:            "COUNCIL",
			SoloTotalScore:    6.5,
			CouncilTotalScore: 9.0,
			Evaluations: []JudgeEvaluation{
				{
					PassNumber: 1,
					SoloScores: []MetricScore{
						{Dimension: MetricFactuality, Score: 6.5},
						{Dimension: MetricBlindSpots, Score: 6.5},
						{Dimension: MetricTradeOffs, Score: 6.5},
						{Dimension: MetricActionability, Score: 6.5},
					},
					CouncilScores: []MetricScore{
						{Dimension: MetricFactuality, Score: 9.0},
						{Dimension: MetricBlindSpots, Score: 9.0},
						{Dimension: MetricTradeOffs, Score: 9.0},
						{Dimension: MetricActionability, Score: 9.0},
					},
				},
			},
		},
		{
			ID:                "run-3",
			CaseID:            "DB03",
			Winner:            "COUNCIL",
			SoloTotalScore:    7.0,
			CouncilTotalScore: 9.2,
			Evaluations: []JudgeEvaluation{
				{
					PassNumber: 1,
					SoloScores: []MetricScore{
						{Dimension: MetricFactuality, Score: 7.0},
						{Dimension: MetricBlindSpots, Score: 7.0},
						{Dimension: MetricTradeOffs, Score: 7.0},
						{Dimension: MetricActionability, Score: 7.0},
					},
					CouncilScores: []MetricScore{
						{Dimension: MetricFactuality, Score: 9.2},
						{Dimension: MetricBlindSpots, Score: 9.2},
						{Dimension: MetricTradeOffs, Score: 9.2},
						{Dimension: MetricActionability, Score: 9.2},
					},
				},
			},
		},
	}

	summary := ComputeSummary(runs)
	if summary.TotalRuns != 3 {
		t.Fatalf("expected total runs 3, got %d", summary.TotalRuns)
	}
	if summary.CouncilWins != 3 {
		t.Fatalf("expected council wins 3, got %d", summary.CouncilWins)
	}
	if summary.CouncilWinRate != 1.0 {
		t.Fatalf("expected win rate 1.0, got %f", summary.CouncilWinRate)
	}
	if summary.MeanDeltaQ <= 2.0 {
		t.Fatalf("expected mean delta > 2.0, got %f", summary.MeanDeltaQ)
	}
	if !summary.IsStatSignificant {
		t.Fatalf("expected statistically significant, got false (p-value=%f)", summary.PValue)
	}
}
