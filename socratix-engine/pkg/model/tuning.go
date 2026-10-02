package model

import "encoding/json"

// Anti-loop, sampling and moderation settings. Field names and enum strings match the Kotlin
// client's kotlinx.serialization output exactly. Kotlin may omit defaulted fields, so each
// config seeds its Kotlin defaults before decoding (UnmarshalJSON) — an absent field means
// "default", never Go's zero value. Fields whose Kotlin default is non-zero therefore have no
// omitempty, or an explicit false/0 would be dropped on re-marshal and revert on the next load.

// LoopAction is the fallback when a retried turn still loops.
type LoopAction string

const (
	LoopActionRetryWithDirective  LoopAction = "RETRY_WITH_DIRECTIVE"
	LoopActionConvertToConcession LoopAction = "CONVERT_TO_CONCESSION"
	LoopActionModeratorIntervene  LoopAction = "MODERATOR_INTERVENE"
	LoopActionHaltOrAdvance       LoopAction = "HALT_OR_ADVANCE"
)

// AntiLoopConfig guards against repeated/cloned turns.
type AntiLoopConfig struct {
	Enabled bool `json:"enabled"`
	// Max token n-gram Jaccard similarity against a prior turn (0..1).
	MaxSimilarityThreshold float64 `json:"maxSimilarityThreshold"`
	// Max contiguous identical characters before flagging a verbatim copy.
	MaxContiguousDuplicateChars int `json:"maxContiguousDuplicateChars"`
	// Max retries per turn before FallbackAction.
	MaxRetries int `json:"maxRetries"`
	// Temperature boost added on each retry of a looped turn.
	RetryTemperatureBump float64    `json:"retryTemperatureBump"`
	FallbackAction       LoopAction `json:"fallbackAction"`
}

// DefaultAntiLoopConfig is the Kotlin default.
func DefaultAntiLoopConfig() AntiLoopConfig {
	return AntiLoopConfig{
		Enabled:                     true,
		MaxSimilarityThreshold:      0.65,
		MaxContiguousDuplicateChars: 180,
		MaxRetries:                  1,
		RetryTemperatureBump:        0.25,
		FallbackAction:              LoopActionConvertToConcession,
	}
}

func (c *AntiLoopConfig) UnmarshalJSON(b []byte) error {
	type plain AntiLoopConfig
	v := plain(DefaultAntiLoopConfig())
	if err := json.Unmarshal(b, &v); err != nil {
		return err
	}
	*c = AntiLoopConfig(v)
	return nil
}

// TemperatureSchedule is the strategy for temperature across rounds.
type TemperatureSchedule string

const (
	TemperatureStatic                  TemperatureSchedule = "STATIC"
	TemperatureLinearCooling           TemperatureSchedule = "LINEAR_COOLING"
	TemperatureAdaptiveConsensusCooled TemperatureSchedule = "ADAPTIVE_CONSENSUS_COOLED"
	TemperatureThreeStageDeliberation  TemperatureSchedule = "THREE_STAGE_DELIBERATION"
)

// SamplingConfig is the temperature/penalty schedule.
type SamplingConfig struct {
	Temperature      float64             `json:"temperature"`
	TopP             *float64            `json:"topP,omitempty"`
	FrequencyPenalty float64             `json:"frequencyPenalty"`
	PresencePenalty  float64             `json:"presencePenalty"`
	Schedule         TemperatureSchedule `json:"schedule"`
	StartTemperature float64             `json:"startTemperature"`
	FloorTemperature float64             `json:"floorTemperature"`
	// Ultra-low temperature for executive conclusions and syntheses.
	ConclusionTemperature float64 `json:"conclusionTemperature"`
}

// DefaultSamplingConfig is the Kotlin default.
func DefaultSamplingConfig() SamplingConfig {
	return SamplingConfig{
		Temperature:           0.7,
		FrequencyPenalty:      0.2,
		PresencePenalty:       0.15,
		Schedule:              TemperatureThreeStageDeliberation,
		StartTemperature:      0.85,
		FloorTemperature:      0.35,
		ConclusionTemperature: 0.15,
	}
}

func (c *SamplingConfig) UnmarshalJSON(b []byte) error {
	type plain SamplingConfig
	v := plain(DefaultSamplingConfig())
	if err := json.Unmarshal(b, &v); err != nil {
		return err
	}
	*c = SamplingConfig(v)
	return nil
}

// ModerationStyle is when the moderator steps in.
type ModerationStyle string

const (
	ModerationPassiveWrapupOnly     ModerationStyle = "PASSIVE_WRAPUP_ONLY"
	ModerationPeriodicCheckpoint    ModerationStyle = "PERIODIC_CHECKPOINT"
	ModerationDynamicActiveSteerage ModerationStyle = "DYNAMIC_ACTIVE_STEERAGE"
	ModerationStrictArbitration     ModerationStyle = "STRICT_ARBITRATION"
)

// ModeratorPersona is the moderator's voice.
type ModeratorPersona string

const (
	PersonaDeliberationChair   ModeratorPersona = "DELIBERATION_CHAIR"
	PersonaExecutiveArbiter    ModeratorPersona = "EXECUTIVE_ARBITER"
	PersonaSocraticProbe       ModeratorPersona = "SOCRATIC_PROBE"
	PersonaDevilsAdvocateChair ModeratorPersona = "DEVILS_ADVOCATE_CHAIR"
)

// InterventionStyle is how the moderator intervenes on a loop/deadlock.
type InterventionStyle string

const (
	InterventionRedirect  InterventionStyle = "REDIRECT"
	InterventionChallenge InterventionStyle = "CHALLENGE"
	InterventionForceVote InterventionStyle = "FORCE_VOTE"
)

// DefaultModeratorConfig is the Kotlin ModerationConfig default (Enabled=false).
func DefaultModeratorConfig() ModeratorConfig {
	return ModeratorConfig{
		Style:                     ModerationDynamicActiveSteerage,
		Persona:                   PersonaExecutiveArbiter,
		ModeratorModel:            "claude-haiku-4-5-20251001",
		CheckpointFrequencyRounds: 2,
		DriftThreshold:            0.45,
		EnforceSteerageDirectives: true,
		Strictness:                3,
		LoopDetectionThreshold:    3,
		InterventionStyle:         InterventionRedirect,
		MaxInterventions:          5,
		DetectRepetition:          true,
		DetectTopicDrift:          true,
		EnforceCivility:           true,
	}
}

func (c *ModeratorConfig) UnmarshalJSON(b []byte) error {
	type plain ModeratorConfig
	v := plain(DefaultModeratorConfig())
	if err := json.Unmarshal(b, &v); err != nil {
		return err
	}
	*c = ModeratorConfig(v)
	return nil
}

// EffectiveAntiLoop returns the configured anti-loop settings, or the Kotlin defaults if nil.
func (c DebateConfig) EffectiveAntiLoop() AntiLoopConfig {
	if c.AntiLoop == nil {
		return DefaultAntiLoopConfig()
	}
	return *c.AntiLoop
}

// EffectiveSampling returns the configured sampling settings, or the Kotlin defaults if nil.
func (c DebateConfig) EffectiveSampling() SamplingConfig {
	if c.Sampling == nil {
		return DefaultSamplingConfig()
	}
	return *c.Sampling
}

// EffectiveModeration returns the configured moderation settings, or the Kotlin defaults if nil.
func (c DebateConfig) EffectiveModeration() ModeratorConfig {
	if c.Moderation == nil {
		return DefaultModeratorConfig()
	}
	return *c.Moderation
}
