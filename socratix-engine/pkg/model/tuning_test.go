package model

import (
	"encoding/json"
	"testing"
)

func TestKotlinStyleJSONDecodesWithDefaults(t *testing.T) {
	var a AntiLoopConfig
	if err := json.Unmarshal([]byte(`{"maxRetries":3}`), &a); err != nil {
		t.Fatal(err)
	}
	want := DefaultAntiLoopConfig()
	want.MaxRetries = 3
	if a != want {
		t.Errorf("antiloop: %+v", a)
	}
	var full AntiLoopConfig
	json.Unmarshal([]byte(`{"enabled":false,"maxSimilarityThreshold":0.5,"maxContiguousDuplicateChars":90,"maxRetries":0,"retryTemperatureBump":0.1,"fallbackAction":"HALT_OR_ADVANCE"}`), &full)
	if full.Enabled || full.FallbackAction != LoopActionHaltOrAdvance || full.MaxRetries != 0 {
		t.Errorf("antiloop full: %+v", full)
	}

	var s SamplingConfig
	json.Unmarshal([]byte(`{"schedule":"LINEAR_COOLING","topP":0.9}`), &s)
	if s.Schedule != TemperatureLinearCooling || s.TopP == nil || *s.TopP != 0.9 || s.Temperature != 0.7 || s.ConclusionTemperature != 0.15 {
		t.Errorf("sampling: %+v", s)
	}

	var m ModeratorConfig
	json.Unmarshal([]byte(`{"enabled":true,"persona":"SOCRATIC_PROBE","moderatorProvider":null,"moderatorSeatId":null,"enforceCivility":false}`), &m)
	if !m.Enabled || m.Persona != PersonaSocraticProbe || m.Strictness != 3 || !m.DetectTopicDrift || m.EnforceCivility ||
		m.Style != ModerationDynamicActiveSteerage || m.MaxInterventions != 5 || m.DriftThreshold != 0.45 {
		t.Errorf("moderator: %+v", m)
	}
}

func TestDebateConfigNewFieldsRoundTrip(t *testing.T) {
	in := `{"topic":"t","claude":{"provider":"ANTHROPIC","model":"m","runMode":"API","systemPrompt":"","context":"","displayName":"","role":"","personaId":"","ponytail":false,"frequencyPenalty":0.3,"presencePenalty":0,"samplingOverride":{"temperature":0.2}},` +
		`"antiLoop":{"enabled":false},"sampling":{"schedule":"STATIC"},"moderation":{"detectTopicDrift":false,"strictness":5}}`
	var c DebateConfig
	if err := json.Unmarshal([]byte(in), &c); err != nil {
		t.Fatal(err)
	}
	if c.Primary.FrequencyPenalty == nil || *c.Primary.FrequencyPenalty != 0.3 || c.Primary.PresencePenalty == nil || c.Primary.SamplingOverride.Temperature != 0.2 {
		t.Fatalf("agent: %+v", c.Primary)
	}
	b, _ := json.Marshal(c)
	var c2 DebateConfig
	if err := json.Unmarshal(b, &c2); err != nil {
		t.Fatal(err)
	}
	if c2.AntiLoop.Enabled || c2.Sampling.Schedule != TemperatureStatic || c2.Moderation.DetectTopicDrift || c2.Moderation.Strictness != 5 {
		t.Errorf("round trip lost values: %s", b)
	}
}

func TestEffectiveDefaultsWhenNil(t *testing.T) {
	var c DebateConfig
	if c.EffectiveAntiLoop() != DefaultAntiLoopConfig() || c.EffectiveSampling() != DefaultSamplingConfig() {
		t.Error("nil configs should yield defaults")
	}
	if c.EffectiveModeration().Strictness != 3 {
		t.Error("moderation default")
	}
	var legacy DebateConfig
	json.Unmarshal([]byte(`{"topic":"x","claude":{"provider":"ANTHROPIC"}}`), &legacy)
	if legacy.AntiLoop != nil || legacy.Sampling != nil {
		t.Error("absent fields must stay nil")
	}
}
