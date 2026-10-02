package persona

import (
	"errors"
	"fmt"

	"socratix/pkg/model"
)

// ValidateDNA verifies schema bounds, required identifiers, and checks for cognitive contradictions.
func ValidateDNA(dna *model.PersonaDNA) error {
	if dna == nil {
		return errors.New("persona DNA cannot be nil")
	}

	if dna.ID == "" {
		return errors.New("persona DNA requires a valid non-empty 'id'")
	}
	if dna.Name == "" {
		return errors.New("persona DNA requires a valid non-empty 'name'")
	}

	// Validate bounds on Epistemic Bias
	if !inUnit(dna.EpistemicBias.TheoryVsPractice) {
		return fmt.Errorf("theoryVsPractice must be between 0.0 and 1.0, got %.2f", dna.EpistemicBias.TheoryVsPractice)
	}
	if !inUnit(dna.EpistemicBias.NoveltyVsProvenance) {
		return fmt.Errorf("noveltyVsProvenance must be between 0.0 and 1.0, got %.2f", dna.EpistemicBias.NoveltyVsProvenance)
	}
	if !inUnit(dna.EpistemicBias.SafetyVsVelocity) {
		return fmt.Errorf("safetyVsVelocity must be between 0.0 and 1.0, got %.2f", dna.EpistemicBias.SafetyVsVelocity)
	}
	if !inUnit(dna.EpistemicBias.RigorThreshold) {
		return fmt.Errorf("rigorThreshold must be between 0.0 and 1.0, got %.2f", dna.EpistemicBias.RigorThreshold)
	}

	// Validate Formality level
	if dna.CommunicationVector.FormalityLevel < 1 || dna.CommunicationVector.FormalityLevel > 5 {
		if dna.CommunicationVector.FormalityLevel == 0 {
			dna.CommunicationVector.FormalityLevel = 3 // default fallback
		} else {
			return fmt.Errorf("formalityLevel must be between 1 and 5, got %d", dna.CommunicationVector.FormalityLevel)
		}
	}

	// Validate Tenacity Score
	if !inUnit(dna.AdversarialPosture.TenacityScore) {
		return fmt.Errorf("tenacityScore must be between 0.0 and 1.0, got %.2f", dna.AdversarialPosture.TenacityScore)
	}

	// Set default schema version if empty
	if dna.SchemaVersion == "" {
		dna.SchemaVersion = "dialex.dna/v1.0"
	}

	return nil
}

// inUnit reports whether v is within [0,1]. NaN is rejected (it fails every comparison).
func inUnit(v float64) bool { return v >= 0.0 && v <= 1.0 }
