package benchmark

import (
	"errors"
	"fmt"

	"socratix/pkg/model"
)

// ErrJudgeNotIndependent means the judge comes from the same model family as an arm it scores.
// LLM judges favour their own family's output, so such a run's scores are not trustworthy.
var ErrJudgeNotIndependent = errors.New("judge is not independent of the arms")

// sameFamily treats the provider as the model family. Ollama and Custom host many unrelated
// models, so for those the model name must match too.
func sameFamily(a, b model.Agent) bool {
	if a.Provider != b.Provider {
		return false
	}
	if a.Provider == model.ProviderOllama || a.Provider == model.ProviderCustom {
		return a.Model == b.Model
	}
	return true
}

// JudgeConflict returns the first arm agent that shares a family with the judge, or nil.
func JudgeConflict(judge model.Agent, arms ...model.Agent) *model.Agent {
	for i := range arms {
		if sameFamily(judge, arms[i]) {
			return &arms[i]
		}
	}
	return nil
}

func judgeConflictError(judge model.Agent, arm *model.Agent) error {
	return fmt.Errorf("%w: judge %s and arm %s are both %s; pick a judge from another provider or allow overlap explicitly",
		ErrJudgeNotIndependent, judge.Label(), arm.Label(), judge.Provider)
}

// judgeCandidates are tried in order; model names follow the rest of the repo's defaults and
// will go stale, so callers can always pass their own judge.
var judgeCandidates = []model.Agent{
	{DisplayName: "Judge (Grok)", Provider: model.ProviderGrok, Model: "grok-3"},
	{DisplayName: "Judge (Mistral)", Provider: model.ProviderMistral, Model: "mistral-large-latest"},
	{DisplayName: "Judge (Gemini)", Provider: model.ProviderGemini, Model: "gemini-2.5-pro"},
	{DisplayName: "Judge (OpenAI)", Provider: model.ProviderOpenAI, Model: "gpt-4o"},
	{DisplayName: "Judge (Anthropic)", Provider: model.ProviderAnthropic, Model: "claude-3-7-sonnet"},
	{DisplayName: "Judge (DeepSeek)", Provider: model.ProviderDeepSeek, Model: "deepseek-reasoner"},
}

// PickIndependentJudge returns a judge whose family appears in none of the arms, preferring one
// the user has a key for (hasKey may be nil). With no keyed candidate it still returns the first
// independent one so the run fails with a clear missing-key error instead of a biased score.
// ok is false only when every candidate family is already an arm.
func PickIndependentJudge(arms []model.Agent, hasKey func(model.Provider) bool) (model.Agent, bool) {
	var fallback *model.Agent
	for i := range judgeCandidates {
		j := judgeCandidates[i]
		j.RunMode = model.RunModeAPI
		j.Role = "Chief Systems Architect & Evaluator"
		if JudgeConflict(j, arms...) != nil {
			continue
		}
		if hasKey != nil && hasKey(j.Provider) {
			return j, true
		}
		if fallback == nil {
			fallback = &j
		}
	}
	if fallback != nil {
		return *fallback, true
	}
	return model.Agent{}, false
}
