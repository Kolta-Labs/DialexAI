package coder

import (
	"fmt"
	"strings"

	"dialex/pkg/model"
	"dialex/pkg/runner"
)

// providerEnv maps each supported provider to the environment variable holding its API key.
// Keys are read from the environment only: never stored, never logged.
var providerEnv = map[model.Provider]string{
	model.ProviderAnthropic: "ANTHROPIC_API_KEY",
	model.ProviderOpenAI:    "OPENAI_API_KEY",
	model.ProviderGemini:    "GEMINI_API_KEY",
	model.ProviderGrok:      "XAI_API_KEY",
	model.ProviderDeepSeek:  "DEEPSEEK_API_KEY",
	model.ProviderMistral:   "MISTRAL_API_KEY",
	model.ProviderOllama:    "", // local, no key
}

// NewAPIRunnerFromEnv builds a direct-API runner and agent for the named provider, taking the
// API key from the environment via getenv (os.Getenv in production).
func NewAPIRunnerFromEnv(providerName, modelName string, getenv func(string) string) (runner.AgentRunner, model.Agent, error) {
	provider := model.Provider(strings.ToUpper(strings.TrimSpace(providerName)))
	envVar, ok := providerEnv[provider]
	if !ok {
		return nil, model.Agent{}, fmt.Errorf("unsupported provider %q (supported: anthropic, openai, gemini, grok, deepseek, mistral, ollama)", providerName)
	}
	if strings.TrimSpace(modelName) == "" {
		return nil, model.Agent{}, fmt.Errorf("a model name is required for provider %s", providerName)
	}
	keys := map[model.Provider]string{}
	if envVar != "" {
		key := getenv(envVar)
		if key == "" {
			return nil, model.Agent{}, fmt.Errorf("set %s to use provider %s", envVar, providerName)
		}
		keys[provider] = key
	}
	agent := model.NewAgent(provider, modelName)
	agent.RunMode = model.RunModeAPI
	return runner.NewApiAgentRunner(keys), agent, nil
}
