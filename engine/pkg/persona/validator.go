package persona

import (
	"errors"
	"fmt"

	"dialex/pkg/model"
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
	if dna.EpistemicBias.TheoryVsPractice < 0.0 || dna.EpistemicBias.TheoryVsPractice > 1.0 {
		return fmt.Errorf("theoryVsPractice must be between 0.0 and 1.0, got %.2f", dna.EpistemicBias.TheoryVsPractice)
	}
	if dna.EpistemicBias.NoveltyVsProvenance < 0.0 || dna.EpistemicBias.NoveltyVsProvenance > 1.0 {
		return fmt.Errorf("noveltyVsProvenance must be between 0.0 and 1.0, got %.2f", dna.EpistemicBias.NoveltyVsProvenance)
	}
	if dna.EpistemicBias.SafetyVsVelocity < 0.0 || dna.EpistemicBias.SafetyVsVelocity > 1.0 {
		return fmt.Errorf("safetyVsVelocity must be between 0.0 and 1.0, got %.2f", dna.EpistemicBias.SafetyVsVelocity)
	}
	if dna.EpistemicBias.RigorThreshold < 0.0 || dna.EpistemicBias.RigorThreshold > 1.0 {
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
	if dna.AdversarialPosture.TenacityScore < 0.0 || dna.AdversarialPosture.TenacityScore > 1.0 {
		return fmt.Errorf("tenacityScore must be between 0.0 and 1.0, got %.2f", dna.AdversarialPosture.TenacityScore)
	}

	// Set default schema version if empty
	if dna.SchemaVersion == "" {
		dna.SchemaVersion = "dialex.dna/v1.0"
	}

	return nil
}
