package quickstart

import (
	"fmt"
	"strings"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

// KeyEnv names the environment variable that holds each provider's API key. Keys are read from
// the environment only: never stored, never logged. Ollama needs none.
var KeyEnv = map[model.Provider]string{
	model.ProviderAnthropic: "ANTHROPIC_API_KEY",
	model.ProviderOpenAI:    "OPENAI_API_KEY",
	model.ProviderGemini:    "GEMINI_API_KEY",
	model.ProviderGrok:      "XAI_API_KEY",
	model.ProviderDeepSeek:  "DEEPSEEK_API_KEY",
	model.ProviderMistral:   "MISTRAL_API_KEY",
	model.ProviderOllama:    "",
}

var detectOrder = []model.Provider{
	model.ProviderAnthropic, model.ProviderOpenAI, model.ProviderGemini,
	model.ProviderGrok, model.ProviderDeepSeek, model.ProviderMistral,
}

// DetectProvider picks the first provider whose key is set. Ollama is never auto-picked
// (it has no key to detect); ask for it explicitly with --provider ollama.
func DetectProvider(getenv func(string) string) (model.Provider, error) {
	for _, p := range detectOrder {
		if getenv(KeyEnv[p]) != "" {
			return p, nil
		}
	}
	var names []string
	for _, p := range detectOrder {
		names = append(names, KeyEnv[p])
	}
	return "", fmt.Errorf("no API key found: set one of %s, or use --provider ollama for a local model", strings.Join(names, ", "))
}

// RunnerFor builds a direct-API runner for provider, with its key taken from the environment.
func RunnerFor(provider model.Provider, getenv func(string) string) (runner.AgentRunner, error) {
	env, ok := KeyEnv[provider]
	if !ok {
		return nil, fmt.Errorf("unsupported provider %q", provider)
	}
	keys := map[model.Provider]string{}
	if env != "" {
		key := getenv(env)
		if key == "" {
			return nil, fmt.Errorf("set %s to use provider %s", env, provider)
		}
		keys[provider] = key
	}
	return runner.NewApiAgentRunner(keys), nil
}

// ParseProvider accepts a provider name in any case.
func ParseProvider(name string) (model.Provider, error) {
	p := model.Provider(strings.ToUpper(strings.TrimSpace(name)))
	if _, ok := KeyEnv[p]; !ok {
		return "", fmt.Errorf("unsupported provider %q", name)
	}
	return p, nil
}
