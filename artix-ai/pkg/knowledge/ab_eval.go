package knowledge

import (
	"fmt"
)

// ABEvalResult holds the outcome of an A/B evaluation measuring rounds-to-converge with vs without a knowledge item or rule.
type ABEvalResult struct {
	TargetID       string `json:"targetId"`
	RoundsBaseline int    `json:"roundsBaseline"`
	RoundsWithKI   int    `json:"roundsWithKi"`
	Regression     bool   `json:"regression"`
	Summary        string `json:"summary"`
}

// RunABEval compares rounds-to-converge with and without a knowledge item or rule.
// If the knowledge item causes a regression (roundsWithKI > roundsBaseline), it is automatically demoted.
func RunABEval(target any, roundsBaseline, roundsWithKI int) (*ABEvalResult, error) {
	if roundsBaseline <= 0 || roundsWithKI <= 0 {
		return nil, fmt.Errorf("rounds must be greater than zero")
	}

	regression := roundsWithKI > roundsBaseline
	var targetID string
	var summary string

	if regression {
		summary = fmt.Sprintf("A/B evaluation regression: %d rounds with KI vs %d baseline rounds", roundsWithKI, roundsBaseline)
	} else {
		summary = fmt.Sprintf("A/B evaluation pass: %d rounds with KI vs %d baseline rounds", roundsWithKI, roundsBaseline)
	}

	switch item := target.(type) {
	case *KnowledgeItem:
		if item == nil {
			return nil, fmt.Errorf("target knowledge item is nil")
		}
		targetID = item.ID
		if regression {
			item.AutoDemote(summary)
		}
	case *SynthesizedRule:
		if item == nil {
			return nil, fmt.Errorf("target synthesized rule is nil")
		}
		targetID = item.RuleID
		if regression {
			item.AutoDemote(summary)
		}
	default:
		return nil, fmt.Errorf("unsupported target type for A/B eval: %T", target)
	}

	return &ABEvalResult{
		TargetID:       targetID,
		RoundsBaseline: roundsBaseline,
		RoundsWithKI:   roundsWithKI,
		Regression:     regression,
		Summary:        summary,
	}, nil
}
