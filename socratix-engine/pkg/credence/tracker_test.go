package credence

import (
	"math"
	"testing"
	"time"

	"dialex/pkg/model"
)

func TestCalculateShannonEntropy(t *testing.T) {
	// Degenerate distribution (absolute certainty): Entropy must be 0.0
	zeroEntropy := map[string]float64{
		"h1": 1.0,
		"h2": 0.0,
	}
	if got := CalculateShannonEntropy(zeroEntropy); math.Abs(got) > 1e-6 {
		t.Errorf("Expected entropy 0.0 for certain distribution, got %f", got)
	}

	// Uniform distribution on K=2: Entropy must be 1.0 bit
	uniform2 := map[string]float64{
		"h1": 0.5,
		"h2": 0.5,
	}
	if got := CalculateShannonEntropy(uniform2); math.Abs(got-1.0) > 1e-6 {
		t.Errorf("Expected entropy 1.0 for uniform K=2, got %f", got)
	}

	// Uniform distribution on K=4: Entropy must be 2.0 bits
	uniform4 := map[string]float64{
		"h1": 0.25,
		"h2": 0.25,
		"h3": 0.25,
		"h4": 0.25,
	}
	if got := CalculateShannonEntropy(uniform4); math.Abs(got-2.0) > 1e-6 {
		t.Errorf("Expected entropy 2.0 for uniform K=4, got %f", got)
	}
}

func TestNormalizeProbabilities(t *testing.T) {
	raw := map[string]float64{
		"h1": 3.0,
		"h2": 1.0,
	}
	norm := NormalizeProbabilities(raw)
	if math.Abs(norm["h1"]-0.75) > 1e-6 || math.Abs(norm["h2"]-0.25) > 1e-6 {
		t.Errorf("Normalization incorrect: got %v", norm)
	}

	sum := 0.0
	for _, p := range norm {
		sum += p
	}
	if math.Abs(sum-1.0) > 1e-6 {
		t.Errorf("Probabilities do not sum to 1.0: got %f", sum)
	}
}

func TestCalculateLikelihoodRatio(t *testing.T) {
	// Prior: 50% vs 50%. Posterior: 80% vs 20%.
	// Lambda = (0.80 * 0.50) / (0.20 * 0.50) = 4.0
	lambda := CalculateLikelihoodRatio(0.5, 0.5, 0.8, 0.2)
	if math.Abs(lambda-4.0) > 1e-6 {
		t.Errorf("Expected Likelihood Ratio 4.0, got %f", lambda)
	}
}

func TestAggregateCouncilCredence(t *testing.T) {
	hypotheses := []model.Hypothesis{
		{ID: "h1", Label: "Kafka"},
		{ID: "h2", Label: "Monolith"},
	}

	personaCredences := []model.PersonaCredence{
		{
			PersonaID: "agent_1",
			HypothesisCredence: map[string]float64{
				"h1": 0.9,
				"h2": 0.1,
			},
			CertaintyScore: 0.9,
		},
		{
			PersonaID: "agent_2",
			HypothesisCredence: map[string]float64{
				"h1": 0.3,
				"h2": 0.7,
			},
			CertaintyScore: 0.5,
		},
	}

	authority := map[string]float64{
		"agent_1": 1.0,
		"agent_2": 1.0,
	}

	agg := AggregateCouncilCredence(personaCredences, authority, hypotheses)
	if agg["h1"] <= agg["h2"] {
		t.Errorf("Expected h1 to dominate h2 with higher weight, got %v", agg)
	}

	sum := agg["h1"] + agg["h2"]
	if math.Abs(sum-1.0) > 1e-6 {
		t.Errorf("Aggregated credences do not sum to 1.0: got %f", sum)
	}
}

func TestDetectTippingPoints(t *testing.T) {
	prev := model.RoundCredenceSnapshot{
		RoundIndex: 1,
		AggregatedCredence: map[string]float64{
			"h1": 0.5,
			"h2": 0.5,
		},
	}
	curr := model.RoundCredenceSnapshot{
		RoundIndex: 2,
		AggregatedCredence: map[string]float64{
			"h1": 0.85,
			"h2": 0.15,
		},
	}
	evidence := []model.RoundEvidence{
		{
			Round: 2,
			Items: []model.EvidenceItem{
				{Snippet: "Disk I/O benchmark saturation under 500k writes"},
			},
		},
	}

	tipping := DetectTippingPoints(prev, curr, evidence)
	if len(tipping) == 0 {
		t.Fatalf("Expected tipping point detection on 35%% probability shift")
	}

	foundH1 := false
	for _, tp := range tipping {
		if tp.AffectedHypothesis == "h1" && tp.ShiftDelta > 0.3 {
			foundH1 = true
			break
		}
	}
	if !foundH1 {
		t.Errorf("Expected tipping point for h1 with shift delta > 0.3, got %v", tipping)
	}
}

func TestUpdateCredenceLedger(t *testing.T) {
	hypotheses := []model.Hypothesis{
		{ID: "h1", Label: "Kafka"},
		{ID: "h2", Label: "Postgres"},
	}

	ledger := &model.CredenceLedger{
		DiscussionID: "disc_123",
		Topic:        "Event Architecture",
		Hypotheses:   hypotheses,
		Snapshots:    []model.RoundCredenceSnapshot{CreateInitialSnapshot(hypotheses)},
		FinalEntropy: 1.0,
		Status:       "IN_PROGRESS",
	}

	snap1 := model.RoundCredenceSnapshot{
		RoundIndex: 1,
		Timestamp:  time.Now(),
		AggregatedCredence: map[string]float64{
			"h1": 0.90,
			"h2": 0.10,
		},
	}

	ledger = UpdateCredenceLedger(ledger, snap1, nil)
	if len(ledger.Snapshots) != 2 {
		t.Errorf("Expected 2 snapshots in ledger, got %d", len(ledger.Snapshots))
	}
	if ledger.Status != "CONVERGED" {
		t.Errorf("Expected status CONVERGED at 90%% probability, got %s", ledger.Status)
	}
	if ledger.FinalEntropy >= 1.0 {
		t.Errorf("Expected reduced entropy, got %f", ledger.FinalEntropy)
	}
}
