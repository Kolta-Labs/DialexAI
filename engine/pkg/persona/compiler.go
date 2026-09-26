package persona

import (
	"fmt"
	"strings"

	"dialex/pkg/model"
)

// CompilePrompt transforms an 8-Layer PersonaDNA struct into a high-density, prompt-injected instruction block.
func CompilePrompt(dna *model.PersonaDNA) string {
	if dna == nil {
		return ""
	}

	var sb strings.Builder
	sb.WriteString(fmt.Sprintf("### [COGNITIVE DNA MANDATE: %s]\n", strings.ToUpper(dna.Name)))

	// Layer 1: Core Identity
	if dna.CoreIdentity.Title != "" || dna.CoreIdentity.DomainAuthority != "" {
		sb.WriteString("• CORE IDENTITY & JURISDICTION:\n")
		if dna.CoreIdentity.Title != "" {
			sb.WriteString(fmt.Sprintf("  - Official Role: %s\n", dna.CoreIdentity.Title))
		}
		if dna.CoreIdentity.Background != "" {
			sb.WriteString(fmt.Sprintf("  - Pedigree: %s\n", dna.CoreIdentity.Background))
		}
		if dna.CoreIdentity.DomainAuthority != "" {
			sb.WriteString(fmt.Sprintf("  - Domain Authority: %s\n", dna.CoreIdentity.DomainAuthority))
		}
		if len(dna.CoreIdentity.Credentials) > 0 {
			sb.WriteString(fmt.Sprintf("  - Credentials: %s\n", strings.Join(dna.CoreIdentity.Credentials, ", ")))
		}
	}

	// Layer 2: Epistemic Bias
	sb.WriteString(fmt.Sprintf("• EPISTEMIC BIAS & REASONING MODE: %s\n", dna.EpistemicBias.PrimaryMode))
	sb.WriteString(fmt.Sprintf("  - Tensor Coordinates: Theory-to-Practice=%.2f, Novelty-to-Provenance=%.2f, Safety-to-Velocity=%.2f, RigorThreshold=%.2f\n",
		dna.EpistemicBias.TheoryVsPractice,
		dna.EpistemicBias.NoveltyVsProvenance,
		dna.EpistemicBias.SafetyVsVelocity,
		dna.EpistemicBias.RigorThreshold,
	))

	// Layer 3: Communication Vector
	if dna.CommunicationVector.Tone != "" || dna.CommunicationVector.TargetSentenceCeiling > 0 {
		sb.WriteString("• COMMUNICATION & RHETORICAL VECTOR:\n")
		sb.WriteString(fmt.Sprintf("  - Tone: %s (Formality: %d/5)\n", dna.CommunicationVector.Tone, dna.CommunicationVector.FormalityLevel))
		if dna.CommunicationVector.TargetSentenceCeiling > 0 {
			sb.WriteString(fmt.Sprintf("  - Brevity Ceiling: Strictly limit response to ~%d punchy, content-dense sentences.\n", dna.CommunicationVector.TargetSentenceCeiling))
		}
		if len(dna.CommunicationVector.RhetoricalDevices) > 0 {
			sb.WriteString(fmt.Sprintf("  - Rhetorical Mechanics: %s\n", strings.Join(dna.CommunicationVector.RhetoricalDevices, "; ")))
		}
	}

	// Layer 4: Heuristic Library
	if len(dna.HeuristicLibrary) > 0 {
		sb.WriteString("• ACTIVE HEURISTIC LIBRARY & MENTAL MODELS:\n")
		for _, h := range dna.HeuristicLibrary {
			sb.WriteString(fmt.Sprintf("  - %s: \"%s\"\n    * Directive: %s\n", h.Name, h.FormulaOrMaxime, h.ApplicationDirective))
		}
	}

	// Layer 5: Taboo Space
	if len(dna.TabooSpace.ForbiddenArguments) > 0 || len(dna.TabooSpace.IntolerableBuzzwords) > 0 || len(dna.TabooSpace.RejectedFallacies) > 0 {
		sb.WriteString("• TABOO SPACE (STRICT NEGATIVE CONSTRAINTS - NEVER CONCEDE THESE):\n")
		if len(dna.TabooSpace.ForbiddenArguments) > 0 {
			sb.WriteString("  - Forbidden Arguments to Call Out:\n")
			for _, arg := range dna.TabooSpace.ForbiddenArguments {
				sb.WriteString(fmt.Sprintf("    * \"%s\"\n", arg))
			}
		}
		if len(dna.TabooSpace.IntolerableBuzzwords) > 0 {
			sb.WriteString(fmt.Sprintf("  - Intolerable Jargon to Attack: %s\n", strings.Join(dna.TabooSpace.IntolerableBuzzwords, ", ")))
		}
		if len(dna.TabooSpace.RejectedFallacies) > 0 {
			sb.WriteString(fmt.Sprintf("  - Unacceptable Fallacies: %s\n", strings.Join(dna.TabooSpace.RejectedFallacies, ", ")))
		}
		if dna.TabooSpace.PenaltyAction != "" {
			sb.WriteString(fmt.Sprintf("  - Penalty Directive: %s\n", dna.TabooSpace.PenaltyAction))
		}
	}

	// Layer 6: Domain Ontology
	if len(dna.DomainOntology.MandatoryStandards) > 0 || len(dna.DomainOntology.AuthoritativeRFCs) > 0 || len(dna.DomainOntology.SpecializedLexicon) > 0 {
		sb.WriteString("• DOMAIN ONTOLOGY & FORMAL SPECIFICATIONS:\n")
		if len(dna.DomainOntology.MandatoryStandards) > 0 {
			sb.WriteString(fmt.Sprintf("  - Mandatory Standards: %s\n", strings.Join(dna.DomainOntology.MandatoryStandards, ", ")))
		}
		if len(dna.DomainOntology.AuthoritativeRFCs) > 0 {
			sb.WriteString(fmt.Sprintf("  - Authoritative RFC Citations: %s\n", strings.Join(dna.DomainOntology.AuthoritativeRFCs, ", ")))
		}
		if len(dna.DomainOntology.SpecializedLexicon) > 0 {
			sb.WriteString(fmt.Sprintf("  - Required Domain Lexicon: %s\n", strings.Join(dna.DomainOntology.SpecializedLexicon, ", ")))
		}
	}

	// Layer 7: Adversarial Posture
	sb.WriteString("• ADVERSARIAL COMBAT POSTURE:\n")
	sb.WriteString(fmt.Sprintf("  - Stance: %s (Tenacity Threshold: %.2f/1.00)\n", dna.AdversarialPosture.Stance, dna.AdversarialPosture.TenacityScore))
	if dna.AdversarialPosture.CounterAttackMethod != "" {
		sb.WriteString(fmt.Sprintf("  - Counter-Attack Method: %s\n", dna.AdversarialPosture.CounterAttackMethod))
	}
	if dna.AdversarialPosture.ConcedeCondition != "" {
		sb.WriteString(fmt.Sprintf("  - Concession Boundary: %s\n", dna.AdversarialPosture.ConcedeCondition))
	}

	// Layer 8: Synthesis Preference
	sb.WriteString("• SYNTHESIS & CONSENSUS BEHAVIOR:\n")
	sb.WriteString(fmt.Sprintf("  - Strategy: %s\n", dna.SynthesisPreference.Style))
	if dna.SynthesisPreference.AllowMinorityReport {
		sb.WriteString("  - Minority Report Mandate: Authorized and expected if core invariants are breached.\n")
		if dna.SynthesisPreference.MinorityReportCriteria != "" {
			sb.WriteString(fmt.Sprintf("  - Trigger Criteria: %s\n", dna.SynthesisPreference.MinorityReportCriteria))
		}
	}
	if dna.SynthesisPreference.CompromiseCondition != "" {
		sb.WriteString(fmt.Sprintf("  - Compromise Requirement: %s\n", dna.SynthesisPreference.CompromiseCondition))
	}

	if dna.RawCustomPrompt != "" {
		sb.WriteString("\n• SUPPLEMENTARY INSTRUCTIONS:\n")
		sb.WriteString(dna.RawCustomPrompt)
		sb.WriteString("\n")
	}

	return sb.String()
}
