package model

// PrimaryReasoningMode defines the foundational logic paradigm of the persona.
type PrimaryReasoningMode string

const (
	ReasoningFirstPrinciples      PrimaryReasoningMode = "FIRST_PRINCIPLES"
	ReasoningEmpiricalStatistical PrimaryReasoningMode = "EMPIRICAL_STATISTICAL"
	ReasoningHistoricalAnalogy    PrimaryReasoningMode = "HISTORICAL_ANALOGY"
	ReasoningPragmaticEngineering PrimaryReasoningMode = "PRAGMATIC_ENGINEERING"
	ReasoningFormalLogical        PrimaryReasoningMode = "FORMAL_LOGICAL"
)

// CombatStance defines the adversarial reaction style when challenged.
type CombatStance string

const (
	StanceUnyieldingDogmatic      CombatStance = "UNYIELDING_DOGMATIC"
	StanceCounterAttacking        CombatStance = "COUNTER_ATTACKING"
	StanceSocraticInverter        CombatStance = "SOCRATIC_INVERTER"
	StanceAnalyticalDeconstructor CombatStance = "ANALYTICAL_DECONSTRUCTOR"
	StancePragmaticAccommodator   CombatStance = "PRAGMATIC_ACCOMMODATOR"
)

// SynthesisStyle defines consensus behavior.
type SynthesisStyle string

const (
	SynthesisSeekSynthesis         SynthesisStyle = "SEEK_SYNTHESIS"
	SynthesisHoldMinorityReport    SynthesisStyle = "HOLD_MINORITY_REPORT"
	SynthesisConditionalCompromise SynthesisStyle = "CONDITIONAL_COMPROMISE"
)

// HeuristicRule defines an executable mental model.
type HeuristicRule struct {
	ID                   string `json:"id" yaml:"id"`
	Name                 string `json:"name" yaml:"name"`
	FormulaOrMaxime      string `json:"formulaOrMaxime" yaml:"formulaOrMaxime"`
	TriggerCondition     string `json:"triggerCondition" yaml:"triggerCondition"`
	ApplicationDirective string `json:"applicationDirective" yaml:"applicationDirective"`
}

// TabooSpace defines negative constraints.
type TabooSpace struct {
	ForbiddenArguments   []string `json:"forbiddenArguments" yaml:"forbiddenArguments"`
	RejectedFallacies    []string `json:"rejectedFallacies" yaml:"rejectedFallacies"`
	IntolerableBuzzwords []string `json:"intolerableBuzzwords" yaml:"intolerableBuzzwords"`
	PenaltyAction        string   `json:"penaltyAction" yaml:"penaltyAction"`
}

// CoreIdentity defines Layer 1.
type CoreIdentity struct {
	Title           string   `json:"title" yaml:"title"`
	Background      string   `json:"background" yaml:"background"`
	DomainAuthority string   `json:"domainAuthority" yaml:"domainAuthority"`
	Credentials     []string `json:"credentials,omitempty" yaml:"credentials,omitempty"`
}

// EpistemicBias defines Layer 2.
type EpistemicBias struct {
	PrimaryMode         PrimaryReasoningMode `json:"primaryMode" yaml:"primaryMode"`
	TheoryVsPractice    float64              `json:"theoryVsPractice" yaml:"theoryVsPractice"`       // 0.0 = Theory, 1.0 = Practice
	NoveltyVsProvenance float64              `json:"noveltyVsProvenance" yaml:"noveltyVsProvenance"` // 0.0 = Novelty, 1.0 = Provenance
	SafetyVsVelocity    float64              `json:"safetyVsVelocity" yaml:"safetyVsVelocity"`       // 0.0 = Safety, 1.0 = Velocity
	RigorThreshold      float64              `json:"rigorThreshold" yaml:"rigorThreshold"`           // 0.0 - 1.0
}

// CommunicationVector defines Layer 3.
type CommunicationVector struct {
	Tone                  string   `json:"tone" yaml:"tone"`
	FormalityLevel        int      `json:"formalityLevel" yaml:"formalityLevel"` // 1 - 5
	TargetSentenceCeiling int      `json:"targetSentenceCeiling" yaml:"targetSentenceCeiling"`
	RhetoricalDevices     []string `json:"rhetoricalDevices" yaml:"rhetoricalDevices"`
	SyntaxPattern         string   `json:"syntaxPattern,omitempty" yaml:"syntaxPattern,omitempty"`
}

// DomainOntology defines Layer 6.
type DomainOntology struct {
	MandatoryStandards     []string `json:"mandatoryStandards" yaml:"mandatoryStandards"`
	AuthoritativeRFCs      []string `json:"authoritativeRFCs" yaml:"authoritativeRFCs"`
	SpecializedLexicon     []string `json:"specializedLexicon" yaml:"specializedLexicon"`
	EnforceFormalCitations bool     `json:"enforceFormalCitations" yaml:"enforceFormalCitations"`
}

// AdversarialPosture defines Layer 7.
type AdversarialPosture struct {
	Stance              CombatStance `json:"stance" yaml:"stance"`
	TenacityScore       float64      `json:"tenacityScore" yaml:"tenacityScore"` // 0.0 - 1.0
	CounterAttackMethod string       `json:"counterAttackMethod" yaml:"counterAttackMethod"`
	ConcedeCondition    string       `json:"concedeCondition" yaml:"concedeCondition"`
}

// SynthesisPreference defines Layer 8.
type SynthesisPreference struct {
	Style                  SynthesisStyle `json:"style" yaml:"style"`
	AllowMinorityReport    bool           `json:"allowMinorityReport" yaml:"allowMinorityReport"`
	MinorityReportCriteria string         `json:"minorityReportCriteria" yaml:"minorityReportCriteria"`
	CompromiseCondition    string         `json:"compromiseCondition" yaml:"compromiseCondition"`
}

// PersonaDNA represents the complete 8-Layer Cognitive DNA Schema.
type PersonaDNA struct {
	SchemaVersion       string              `json:"schemaVersion" yaml:"schemaVersion"` // "dialex.dna/v1.0" or "mmos/v1.0"
	ID                  string              `json:"id" yaml:"id"`
	Name                string              `json:"name" yaml:"name"`
	Role                string              `json:"role" yaml:"role"`
	Category            string              `json:"category" yaml:"category"`
	Icon                string              `json:"icon,omitempty" yaml:"icon,omitempty"`
	CoreIdentity        CoreIdentity        `json:"coreIdentity" yaml:"coreIdentity"`
	EpistemicBias       EpistemicBias       `json:"epistemicBias" yaml:"epistemicBias"`
	CommunicationVector CommunicationVector `json:"communicationVector" yaml:"communicationVector"`
	HeuristicLibrary    []HeuristicRule     `json:"heuristicLibrary" yaml:"heuristicLibrary"`
	TabooSpace          TabooSpace          `json:"tabooSpace" yaml:"tabooSpace"`
	DomainOntology      DomainOntology      `json:"domainOntology" yaml:"domainOntology"`
	AdversarialPosture  AdversarialPosture  `json:"adversarialPosture" yaml:"adversarialPosture"`
	SynthesisPreference SynthesisPreference `json:"synthesisPreference" yaml:"synthesisPreference"`
	RawCustomPrompt     string              `json:"rawCustomPrompt,omitempty" yaml:"rawCustomPrompt,omitempty"`
}
