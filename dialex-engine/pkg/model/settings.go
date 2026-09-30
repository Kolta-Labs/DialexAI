package model

// ApiKeys are global, one per provider — set once in Settings, used by every API-mode
// agent on that provider.
type ApiKeys struct {
	Anthropic string `json:"anthropic"`
	OpenAI    string `json:"openai"`
	Gemini    string `json:"gemini"`
	Grok      string `json:"grok"`
	DeepSeek  string `json:"deepseek"`
	Mistral   string `json:"mistral"`
	Ollama    string `json:"ollama"`
}

// ForProvider returns the key for one provider. CUSTOM is CLI-only — no API shape to call,
// so no key concept applies; always returns empty for it.
func (k ApiKeys) ForProvider(p Provider) string {
	switch p {
	case ProviderAnthropic:
		return k.Anthropic
	case ProviderOpenAI:
		return k.OpenAI
	case ProviderGemini:
		return k.Gemini
	case ProviderGrok:
		return k.Grok
	case ProviderDeepSeek:
		return k.DeepSeek
	case ProviderMistral:
		return k.Mistral
	case ProviderOllama:
		return k.Ollama
	default:
		return ""
	}
}

// AsMap is every provider's key keyed by Provider (CUSTOM excluded — it has none).
func (k ApiKeys) AsMap() map[Provider]string {
	return map[Provider]string{
		ProviderAnthropic: k.Anthropic,
		ProviderOpenAI:    k.OpenAI,
		ProviderGemini:    k.Gemini,
		ProviderGrok:      k.Grok,
		ProviderDeepSeek:  k.DeepSeek,
		ProviderMistral:   k.Mistral,
		ProviderOllama:    k.Ollama,
	}
}

// CliCommands are global CLI invocations, one per provider — set once in Settings, used by
// every CLI-mode agent on that provider. Google renamed the Gemini CLI to "Antigravity",
// whose binary is `agy` — that's the default now, with `gemini` still checked as a fallback
// for older installs (see runner.ResolveCLIBinary). Grok/DeepSeek/Mistral have no
// widely-established dedicated CLI, so they default blank.
type CliCommands struct {
	Anthropic string `json:"anthropic"`
	OpenAI    string `json:"openai"`
	Gemini    string `json:"gemini"`
	Grok      string `json:"grok"`
	DeepSeek  string `json:"deepseek"`
	Mistral   string `json:"mistral"`
	Ollama    string `json:"ollama"`
	// Any other CLI tool (Aider, Cursor CLI, ...) — freeform since there's no fixed brand,
	// used only by an Agent with Provider == CUSTOM.
	Custom string `json:"custom"`
}

// DefaultCliCommands mirrors the Kotlin CliCommands() default constructor.
func DefaultCliCommands() CliCommands {
	return CliCommands{
		Anthropic: "claude --print",
		OpenAI:    "codex exec",
		Gemini:    "agy --print",
		Ollama:    "ollama run",
	}
}

// ForProvider returns the configured command for one provider.
func (c CliCommands) ForProvider(p Provider) string {
	switch p {
	case ProviderAnthropic:
		return c.Anthropic
	case ProviderOpenAI:
		return c.OpenAI
	case ProviderGemini:
		return c.Gemini
	case ProviderGrok:
		return c.Grok
	case ProviderDeepSeek:
		return c.DeepSeek
	case ProviderMistral:
		return c.Mistral
	case ProviderOllama:
		return c.Ollama
	case ProviderCustom:
		return c.Custom
	default:
		return ""
	}
}

// AgentParams defines sampling parameters for an LLM agent.
type AgentParams struct {
	Temperature float64 `json:"temperature"`
	TopP        float64 `json:"topP"`
	MaxTokens   int     `json:"maxTokens"`
}

// DefaultAgentParams returns standard sampling defaults (0.7 temp, 0.95 top-p, 4096 tokens).
func DefaultAgentParams() AgentParams {
	return AgentParams{
		Temperature: 0.7,
		TopP:        0.95,
		MaxTokens:   4096,
	}
}

// ProviderAgentDefaults maps sampling defaults per provider.
type ProviderAgentDefaults struct {
	Anthropic AgentParams `json:"anthropic"`
	OpenAI    AgentParams `json:"openai"`
	Gemini    AgentParams `json:"gemini"`
	Grok      AgentParams `json:"grok"`
	DeepSeek  AgentParams `json:"deepseek"`
	Mistral   AgentParams `json:"mistral"`
	Ollama    AgentParams `json:"ollama"`
	Custom    AgentParams `json:"custom"`
}

func DefaultProviderAgentDefaults() ProviderAgentDefaults {
	d := DefaultAgentParams()
	return ProviderAgentDefaults{
		Anthropic: d,
		OpenAI:    d,
		Gemini:    d,
		Grok:      d,
		DeepSeek:  d,
		Mistral:   d,
		Ollama:    d,
		Custom:    d,
	}
}

func (p ProviderAgentDefaults) ForProvider(prov Provider) AgentParams {
	switch prov {
	case ProviderAnthropic:
		return p.Anthropic
	case ProviderOpenAI:
		return p.OpenAI
	case ProviderGemini:
		return p.Gemini
	case ProviderGrok:
		return p.Grok
	case ProviderDeepSeek:
		return p.DeepSeek
	case ProviderMistral:
		return p.Mistral
	case ProviderOllama:
		return p.Ollama
	case ProviderCustom:
		return p.Custom
	default:
		return DefaultAgentParams()
	}
}

