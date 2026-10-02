package credence

import (
	"math"
	"sort"
	"time"

	"socratix/pkg/model"
)

// CalculateShannonEntropy calculates Shannon entropy in bits for a discrete probability distribution.
// Entropy S = - \sum p * log2(p). Maximum entropy for K hypotheses is log2(K).
func CalculateShannonEntropy(probabilities map[string]float64) float64 {
	if len(probabilities) == 0 {
		return 0.0
	}
	entropy := 0.0
	for _, p := range probabilities {
		if p > 1e-9 {
			entropy -= p * math.Log2(p)
		}
	}
	if entropy < 0.0 {
		return 0.0
	}
	return entropy
}

// CalculateLikelihoodRatio computes the likelihood ratio Lambda between hypothesis A and hypothesis B:
// Lambda = (postA * priorB) / (postB * priorA)
func CalculateLikelihoodRatio(priorA, priorB, postA, postB float64) float64 {
	if priorA <= 1e-9 || priorB <= 1e-9 || postB <= 1e-9 {
		return 1.0
	}
	ratio := (postA * priorB) / (postB * priorA)
	if math.IsNaN(ratio) || math.IsInf(ratio, 0) {
		return 1.0
	}
	return ratio
}

// NormalizeProbabilities ensures a probability map sums to exactly 1.0.
func NormalizeProbabilities(raw map[string]float64) map[string]float64 {
	if len(raw) == 0 {
		return raw
	}
	total := 0.0
	for _, v := range raw {
		if v > 0 {
			total += v
		}
	}
	normalized := make(map[string]float64, len(raw))
	if total <= 1e-9 {
		uniform := 1.0 / float64(len(raw))
		for k := range raw {
			normalized[k] = uniform
		}
		return normalized
	}
	for k, v := range raw {
		if v <= 0 {
			normalized[k] = 0.0
		} else {
			normalized[k] = v / total
		}
	}
	return normalized
}

// AggregateCouncilCredence combines individual persona credence assignments into a council consensus probability
// using a linear pool weighted by each persona's domain authority weight (from 8-layer DNA) and certainty score.
func AggregateCouncilCredence(personaCredences []model.PersonaCredence, authorityWeights map[string]float64, hypotheses []model.Hypothesis) map[string]float64 {
	if len(hypotheses) == 0 {
		return nil
	}

	result := make(map[string]float64, len(hypotheses))
	for _, h := range hypotheses {
		result[h.ID] = 0.0
	}

	if len(personaCredences) == 0 {
		uniform := 1.0 / float64(len(hypotheses))
		for _, h := range hypotheses {
			result[h.ID] = uniform
		}
		return result
	}

	totalWeight := 0.0
	for _, pc := range personaCredences {
		auth := 1.0
		if w, ok := authorityWeights[pc.PersonaID]; ok && w > 0 {
			auth = w
		}
		certainty := pc.CertaintyScore
		if certainty <= 0.0 {
			certainty = 0.5
		}
		effectiveWeight := auth * certainty
		totalWeight += effectiveWeight

		for hID, prob := range pc.HypothesisCredence {
			result[hID] += prob * effectiveWeight
		}
	}

	if totalWeight > 1e-9 {
		for hID := range result {
			result[hID] /= totalWeight
		}
	}

	return NormalizeProbabilities(result)
}

// DetectTippingPoints compares two consecutive round snapshots to identify high-leverage belief shifts
// and correlates them with retrieved evidence from that round.
func DetectTippingPoints(prevSnapshot, currSnapshot model.RoundCredenceSnapshot, evidenceList []model.RoundEvidence) []model.TippingPoint {
	var tippingPoints []model.TippingPoint
	if len(prevSnapshot.AggregatedCredence) == 0 || len(currSnapshot.AggregatedCredence) == 0 {
		return tippingPoints
	}

	// Find the top evidence snippet for current round
	evidenceSnippet := "Dialectic argument and trade-off comparison"
	for _, re := range evidenceList {
		if re.Round == currSnapshot.RoundIndex && len(re.Items) > 0 {
			evidenceSnippet = re.Items[0].Snippet
			if len(evidenceSnippet) > 120 {
				evidenceSnippet = evidenceSnippet[:117] + "..."
			}
			break
		}
	}

	for hID, currP := range currSnapshot.AggregatedCredence {
		prevP, ok := prevSnapshot.AggregatedCredence[hID]
		if !ok {
			continue
		}
		delta := currP - prevP
		// Shift threshold: >= 15% net move
		if math.Abs(delta) >= 0.15 {
			var otherID string
			for other := range currSnapshot.AggregatedCredence {
				if other != hID {
					otherID = other
					break
				}
			}
			lambda := 1.0
			if otherID != "" {
				lambda = CalculateLikelihoodRatio(prevP, prevSnapshot.AggregatedCredence[otherID], currP, currSnapshot.AggregatedCredence[otherID])
			}
			tippingPoints = append(tippingPoints, model.TippingPoint{
				RoundIndex:         currSnapshot.RoundIndex,
				EvidenceSnippet:    evidenceSnippet,
				LikelihoodRatio:    math.Round(lambda*100) / 100,
				AffectedHypothesis: hID,
				ShiftDelta:         math.Round(delta*1000) / 1000,
			})
		}
	}

	return tippingPoints
}

// DetermineDominantHypothesis returns the ID of the hypothesis with highest consensus credence.
func DetermineDominantHypothesis(aggregated map[string]float64) string {
	var bestID string
	maxP := -1.0
	for hID, p := range aggregated {
		if p > maxP {
			maxP = p
			bestID = hID
		}
	}
	return bestID
}

// DetermineLedgerStatus checks whether the council has achieved mathematical convergence,
// reached a stalemate, or is still actively deliberating.
func DetermineLedgerStatus(entropy float64, dominantP float64, totalRounds int) string {
	if dominantP >= 0.85 || entropy <= 0.35 {
		return "CONVERGED"
	}
	if totalRounds >= 4 && entropy >= 1.0 {
		return "STALEMATE"
	}
	return "IN_PROGRESS"
}

// CreateInitialSnapshot builds Round 0 uniform prior snapshot.
func CreateInitialSnapshot(hypotheses []model.Hypothesis) model.RoundCredenceSnapshot {
	uniform := 1.0 / float64(len(hypotheses))
	credenceMap := make(map[string]float64, len(hypotheses))
	for _, h := range hypotheses {
		credenceMap[h.ID] = uniform
	}
	entropy := CalculateShannonEntropy(credenceMap)
	return model.RoundCredenceSnapshot{
		RoundIndex:         0,
		Timestamp:          time.Now(),
		PersonaCredences:   nil,
		AggregatedCredence: credenceMap,
		Entropy:            math.Round(entropy*1000) / 1000,
		DominantHypothesis: DetermineDominantHypothesis(credenceMap),
		TippingPoints:      nil,
	}
}

// UpdateCredenceLedger appends a new round snapshot, calculates entropy and tipping points, and updates ledger status.
func UpdateCredenceLedger(ledger *model.CredenceLedger, snapshot model.RoundCredenceSnapshot, evidence []model.RoundEvidence) *model.CredenceLedger {
	if ledger == nil {
		return nil
	}

	if len(ledger.Snapshots) > 0 {
		prev := ledger.Snapshots[len(ledger.Snapshots)-1]
		tippingPoints := DetectTippingPoints(prev, snapshot, evidence)
		snapshot.TippingPoints = tippingPoints
	}

	snapshot.Entropy = math.Round(CalculateShannonEntropy(snapshot.AggregatedCredence)*1000) / 1000
	snapshot.DominantHypothesis = DetermineDominantHypothesis(snapshot.AggregatedCredence)

	// Update or replace existing snapshot for this round
	existingIdx := -1
	for i, s := range ledger.Snapshots {
		if s.RoundIndex == snapshot.RoundIndex {
			existingIdx = i
			break
		}
	}

	if existingIdx >= 0 {
		ledger.Snapshots[existingIdx] = snapshot
	} else {
		ledger.Snapshots = append(ledger.Snapshots, snapshot)
	}

	// Sort snapshots by round index
	sort.Slice(ledger.Snapshots, func(i, j int) bool {
		return ledger.Snapshots[i].RoundIndex < ledger.Snapshots[j].RoundIndex
	})

	lastSnapshot := ledger.Snapshots[len(ledger.Snapshots)-1]
	ledger.FinalEntropy = lastSnapshot.Entropy

	dominantP := 0.0
	if domID := lastSnapshot.DominantHypothesis; domID != "" {
		dominantP = lastSnapshot.AggregatedCredence[domID]
	}
	ledger.Status = DetermineLedgerStatus(lastSnapshot.Entropy, dominantP, len(ledger.Snapshots)-1)

	return ledger
}
