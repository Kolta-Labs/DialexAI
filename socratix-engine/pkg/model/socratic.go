package model

// DiscussionMode defines the operating paradigm of a deliberation.
type DiscussionMode string

const (
	DiscussionModeCouncil           DiscussionMode = "COUNCIL"
	DiscussionModeSocraticInterview DiscussionMode = "SOCRATIC_INTERVIEW"
)

// SocraticStance defines the philosophical and architectural interrogation lens.
type SocraticStance string

const (
	StanceRuthlessElenchus     SocraticStance = "RUTHLESS_ELENCHUS"      // Contradiction hunting & falsification
	StanceMaieuticArchitect    SocraticStance = "MAIEUTIC_ARCHITECT"     // Midwife of latent requirements & invariants
	StanceFirstPrinciples      SocraticStance = "FIRST_PRINCIPLES"       // Axiomatic deconstruction down to physics/math
	StanceAdversarialRedTeam   SocraticStance = "ADVERSARIAL_RED_TEAM"   // Zero-trust malicious saboteur
	StanceAporiaBoundaryPusher SocraticStance = "APORIA_BOUNDARY_PUSHER" // Catapulting to asymptotic limits & 100x load
)

// AllSocraticStances provides the ordered list of all supported stances.
var AllSocraticStances = []SocraticStance{
	StanceRuthlessElenchus,
	StanceMaieuticArchitect,
	StanceFirstPrinciples,
	StanceAdversarialRedTeam,
	StanceAporiaBoundaryPusher,
}

func (s SocraticStance) DisplayName() string {
	switch s {
	case StanceRuthlessElenchus:
		return "Classic Elenchus"
	case StanceMaieuticArchitect:
		return "Maieutic Architecture"
	case StanceFirstPrinciples:
		return "Radical First Principles"
	case StanceAdversarialRedTeam:
		return "Adversarial Red-Team"
	case StanceAporiaBoundaryPusher:
		return "Aporia & Extreme Scale"
	default:
		return string(s)
	}
}

// SocraticStage represents the dynamic progression of the interrogation.
type SocraticStage string

const (
	StageHypothesisExtraction  SocraticStage = "HYPOTHESIS_EXTRACTION"
	StageAssumptionSurfacing   SocraticStage = "ASSUMPTION_SURFACING"
	StageElenchusStressTesting SocraticStage = "ELENCHUS_STRESS_TESTING"
	StageAporiaReconciliation  SocraticStage = "APORIA_RECONCILIATION"
	StageMaieuticHardening     SocraticStage = "MAIEUTIC_HARDENING"
)

// LedgerItemType classifies an assumption's status in the Epistemic Ledger.
type LedgerItemType string

const (
	LedgerItemHardened   LedgerItemType = "HARDENED"    // Validated invariant
	LedgerItemConceded   LedgerItemType = "CONCEDED"    // Discarded axiom / conceded flaw
	LedgerItemUnderSiege LedgerItemType = "UNDER_SIEGE" // Currently interrogated premise
)

// SocraticLedgerItem records a tracked cognitive asset in the Live Epistemic Ledger.
type SocraticLedgerItem struct {
	ID        string         `json:"id"`
	Type      LedgerItemType `json:"type"`
	Statement string         `json:"statement"`
	Turn      int            `json:"turn"`
	Rationale string         `json:"rationale,omitempty"`
}

// SocraticConfig encapsulates metadata and active settings for a Socratic session.
type SocraticConfig struct {
	InterviewerPersonaID string         `json:"interviewerPersonaId"`
	InterviewerName      string         `json:"interviewerName"`
	Stance               SocraticStance `json:"stance"`
	Stage                SocraticStage  `json:"stage"`
	ParentDiscussionID   string         `json:"parentDiscussionId,omitempty"`
	ParentMessageID      string         `json:"parentMessageId,omitempty"`
}

// SocraticDigest represents the crystallized architectural artifact produced at the end of an interview.
type SocraticDigest struct {
	InitialHypothesis  string   `json:"initialHypothesis"`
	DefendedInvariants []string `json:"defendedInvariants"`
	ExposedBlindSpots  []string `json:"exposedBlindSpots"`
	HardenedThesis     string   `json:"hardenedThesis"`
	ResidualTensions   []string `json:"residualTensions"`
	GeneratedAtMs      int64    `json:"generatedAtMs"`
}
