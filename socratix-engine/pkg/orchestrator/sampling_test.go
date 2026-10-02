package orchestrator

import (
	"math"
	"testing"

	"socratix/pkg/model"
)

func near(a, b float64) bool { return math.Abs(a-b) < 0.001 }

func TestScheduledTemperatureCoolsAcrossRounds(t *testing.T) {
	c := model.DefaultSamplingConfig()
	c.Schedule = model.TemperatureLinearCooling
	for round, want := range map[int]float64{1: 0.85, 3: 0.60, 5: 0.35} {
		if got, _, _ := ScheduledSampling(c, round, 5, false); !near(got, want) {
			t.Errorf("round %d: got %v want %v", round, got, want)
		}
	}
}

func TestConclusionAlwaysLowTemperature(t *testing.T) {
	c := model.DefaultSamplingConfig()
	c.Temperature, c.Schedule = 0.95, model.TemperatureStatic
	temp, f, p := ScheduledSampling(c, 5, 5, true)
	if !near(temp, 0.15) || !near(f, 0.10) || p != 0 {
		t.Errorf("got %v %v %v", temp, f, p)
	}
}

func TestThreeStageAndAdaptive(t *testing.T) {
	c := model.DefaultSamplingConfig() // THREE_STAGE
	for round, want := range map[int]float64{1: 0.85, 2: 0.60, 4: 0.60, 5: 0.35} {
		if got, _, _ := ScheduledSampling(c, round, 5, false); !near(got, want) {
			t.Errorf("3stage round %d: got %v", round, got)
		}
	}
	if got, _, _ := ScheduledSampling(c, 1, 0, false); !near(got, 0.85) {
		t.Errorf("maxRounds 0 => 10: %v", got)
	}
	c.Schedule = model.TemperatureAdaptiveConsensusCooled
	if got, _, _ := ScheduledSampling(c, 3, 5, false); !near(got, 0.85-0.25*0.5) {
		t.Errorf("adaptive: %v", got)
	}
}

func TestProviderClamping(t *testing.T) {
	c := model.DefaultSamplingConfig()
	c.Schedule, c.Temperature = model.TemperatureStatic, 1.6
	a := NormalizeSampling(c, model.ProviderAnthropic, 1, 5, false, 0, 0)
	if a.Temperature != 1.0 || a.FrequencyPenalty != nil {
		t.Errorf("anthropic: %+v", a)
	}
	o := NormalizeSampling(c, model.ProviderOpenAI, 1, 5, false, 0, 0)
	if !near(o.Temperature, 1.6) || o.FrequencyPenalty == nil || o.PresencePenalty == nil {
		t.Errorf("openai: %+v", o)
	}
	g := NormalizeSampling(c, model.ProviderGemini, 1, 5, false, 0, 0)
	if g.FrequencyPenalty != nil {
		t.Errorf("gemini: %+v", g)
	}
	// retry bump pushes Anthropic over the lexical-variation threshold and raises temp
	r := NormalizeSampling(model.DefaultSamplingConfig(), model.ProviderAnthropic, 2, 5, false, 0.25, 0.60)
	if r.StyleDirective == "" || !near(r.Temperature, 0.85) {
		t.Errorf("retry: %+v", r)
	}
}

func TestAgentSamplingPrecedence(t *testing.T) {
	base := model.DefaultSamplingConfig()
	ov := model.SamplingConfig{Temperature: 0.1}
	if AgentSampling(model.Agent{SamplingOverride: &ov}, base).Temperature != 0.1 {
		t.Error("override")
	}
	tmp, tp := 0.4, 0.9
	got := AgentSampling(model.Agent{Temperature: &tmp, TopP: &tp}, base)
	if got.StartTemperature != 0.4 || got.FloorTemperature != 0.4 || *got.TopP != 0.9 {
		t.Errorf("%+v", got)
	}
	if AgentSampling(model.Agent{}, base) != base {
		t.Error("passthrough")
	}
}

func TestSamplingMaxRounds(t *testing.T) {
	if SamplingMaxRounds(model.DebateConfig{RoundMode: model.RoundModeFixed, MaxRounds: 4}) != 4 ||
		SamplingMaxRounds(model.DebateConfig{RoundMode: model.RoundModeUnlimited, MaxRounds: 4}) != 10 {
		t.Error("max rounds")
	}
}
