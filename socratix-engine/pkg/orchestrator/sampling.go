package orchestrator

import (
	"socratix/pkg/model"
)

// Port of the Kotlin SamplingNormalizer (git d0af8e3, orchestrator/SamplingNormalizer.kt).

// NormalizedSampling is provider-clamped sampling. nil pointers = do not send that parameter.
type NormalizedSampling struct {
	Temperature      float64
	TopP             *float64
	FrequencyPenalty *float64
	PresencePenalty  *float64
	// StyleDirective is non-empty only for Anthropic (no penalty params): extra prompt text
	// that stands in for the frequency penalty.
	StyleDirective string
}

// UnlimitedRoundsHorizon is the maxRounds the Kotlin orchestrator passes when RoundMode is
// not FIXED.
const UnlimitedRoundsHorizon = 10

// SamplingMaxRounds is what to pass as maxRounds: cfg.MaxRounds when FIXED, else 10.
func SamplingMaxRounds(cfg model.DebateConfig) int {
	if cfg.RoundMode == model.RoundModeFixed {
		return cfg.MaxRounds
	}
	return UnlimitedRoundsHorizon
}

// AgentSampling resolves one agent's sampling config: SamplingOverride wins; else a per-agent
// Temperature/TopP pins the temperature (start and floor too, so cooling schedules go flat)
// on top of base.
func AgentSampling(agent model.Agent, base model.SamplingConfig) model.SamplingConfig {
	if agent.SamplingOverride != nil {
		return *agent.SamplingOverride
	}
	if agent.Temperature == nil && agent.TopP == nil {
		return base
	}
	if agent.Temperature != nil {
		base.Temperature = *agent.Temperature
		base.StartTemperature = *agent.Temperature
		base.FloorTemperature = *agent.Temperature
	}
	if agent.TopP != nil {
		base.TopP = agent.TopP
	}
	return base
}

func clamp(v, lo, hi float64) float64 {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func ptr(v float64) *float64 { return &v }

// ScheduledSampling returns the scheduled temperature, frequency and presence penalty for a
// round. maxRounds <= 0 is treated as 10. Conclusions get conclusionTemperature, (0.10, 0.0).
func ScheduledSampling(c model.SamplingConfig, round, maxRounds int, isConclusion bool) (temp, freq, pres float64) {
	if isConclusion {
		return c.ConclusionTemperature, 0.10, 0.0
	}
	total := maxRounds
	if total <= 0 {
		total = UnlimitedRoundsHorizon
	}
	fraction := 0.0
	if total > 1 {
		fraction = clamp((float64(round)-1.0)/(float64(total)-1.0), 0, 1)
	}
	switch c.Schedule {
	case model.TemperatureStatic:
		return c.Temperature, c.FrequencyPenalty, c.PresencePenalty
	case model.TemperatureLinearCooling:
		temp = c.StartTemperature - fraction*(c.StartTemperature-c.FloorTemperature)
		freq = c.FrequencyPenalty + fraction*0.20
		if freq > 1.0 {
			freq = 1.0
		}
		pres = c.PresencePenalty * (1.0 - fraction)
		if pres < 0 {
			pres = 0
		}
		return
	case model.TemperatureAdaptiveConsensusCooled:
		temp = clamp(c.StartTemperature-fraction*fraction*(c.StartTemperature-c.FloorTemperature), c.FloorTemperature, c.StartTemperature)
		return temp, c.FrequencyPenalty, c.PresencePenalty
	default: // THREE_STAGE_DELIBERATION (the Kotlin default)
		switch {
		case round <= 1:
			return c.StartTemperature, 0.10, 0.30
		case round >= total:
			return c.FloorTemperature, 0.20, 0.0
		default:
			return 0.60, 0.35, 0.15
		}
	}
}

const lexicalVariationDirective = "[STYLE DIRECTIVE: LEXICAL VARIATION]\n" +
	"Avoid repeating key phrases, structural templates, or rhetorical devices from earlier turns. " +
	"Introduce distinct framing, fresh vocabulary, and novel arguments."

// NormalizeSampling schedules then clamps per provider. temperatureBump / frequencyPenaltyBump
// are the anti-loop retry bumps (Kotlin: += retryTemperatureBump and += 0.60 per retry).
func NormalizeSampling(c model.SamplingConfig, provider model.Provider, round, maxRounds int, isConclusion bool, temperatureBump, frequencyPenaltyBump float64) NormalizedSampling {
	sched, baseFreq, basePres := ScheduledSampling(c, round, maxRounds, isConclusion)
	rawTemp := sched + temperatureBump
	rawFreq := baseFreq + frequencyPenaltyBump
	rawPres := basePres

	var topP *float64
	if c.TopP != nil {
		topP = ptr(clamp(*c.TopP, 0, 1))
	}
	switch provider {
	case model.ProviderAnthropic:
		n := NormalizedSampling{Temperature: clamp(rawTemp, 0, 1), TopP: topP}
		if rawFreq > 0.20 {
			n.StyleDirective = lexicalVariationDirective
		}
		return n
	case model.ProviderOpenAI, model.ProviderGrok, model.ProviderDeepSeek, model.ProviderMistral, model.ProviderOllama:
		return NormalizedSampling{
			Temperature:      clamp(rawTemp, 0, 2),
			TopP:             topP,
			FrequencyPenalty: ptr(clamp(rawFreq, -2, 2)),
			PresencePenalty:  ptr(clamp(rawPres, -2, 2)),
		}
	case model.ProviderGemini:
		return NormalizedSampling{Temperature: clamp(rawTemp, 0, 2), TopP: topP}
	default: // CUSTOM and anything unknown: temperature only, no topP
		return NormalizedSampling{Temperature: clamp(rawTemp, 0, 2)}
	}
}
