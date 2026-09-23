package com.dialex.model

import kotlinx.serialization.Serializable

/** Global API keys, one per provider — set once in Settings, used by every API-mode agent. */
@Serializable
data class ApiKeys(
    val anthropic: String = "",
    val openai: String = "",
    val gemini: String = "",
    val grok: String = "",
    val deepseek: String = "",
    val mistral: String = "",
    val ollama: String = "http://localhost:11434",
) {
    // CUSTOM is CLI-only — no API shape to call, so no key concept applies. Always CLI mode
    // for that provider, so this is never actually read for it.
    fun forProvider(provider: Provider): String = when (provider) {
        Provider.ANTHROPIC -> anthropic
        Provider.OPENAI -> openai
        Provider.GEMINI -> gemini
        Provider.GROK -> grok
        Provider.DEEPSEEK -> deepseek
        Provider.MISTRAL -> mistral
        Provider.OLLAMA -> ollama
        Provider.CUSTOM -> ""
    }

    fun asMap(): Map<Provider, String> = mapOf(
        Provider.ANTHROPIC to anthropic,
        Provider.OPENAI to openai,
        Provider.GEMINI to gemini,
        Provider.GROK to grok,
        Provider.DEEPSEEK to deepseek,
        Provider.MISTRAL to mistral,
        Provider.OLLAMA to ollama,
    )
}

/** Global CLI invocations, one per provider — set once in Settings, used by every CLI-mode
 * agent on that provider. Google renamed the Gemini CLI to "Antigravity", whose binary is
 * `agy` — that's the default now, with `gemini` still checked as a fallback for older
 * installs. Grok/DeepSeek/Mistral have no widely-established dedicated CLI the way
 * Claude/Codex/Antigravity do, so they default blank — set one in Settings if you have one,
 * otherwise stick to API mode for those three. */
@Serializable
data class CliCommands(
    val anthropic: String = "claude --print",
    val openai: String = "codex exec",
    val gemini: String = "agy --print",
    val grok: String = "",
    val deepseek: String = "",
    val mistral: String = "",
    val ollama: String = "ollama run",
    /** Any other CLI tool (Aider, Cursor CLI, ...) — freeform since there's no fixed brand,
     * used only by an [Agent] with `provider == Provider.CUSTOM`. */
    val custom: String = "",
) {
    fun forProvider(provider: Provider): String = when (provider) {
        Provider.ANTHROPIC -> anthropic
        Provider.OPENAI -> openai
        Provider.GEMINI -> gemini
        Provider.GROK -> grok
        Provider.DEEPSEEK -> deepseek
        Provider.MISTRAL -> mistral
        Provider.OLLAMA -> ollama
        Provider.CUSTOM -> custom
    }
}

/** Global agent model sampling parameters (temperature, top_p, max_tokens). */
@Serializable
data class AgentParams(
    val temperature: Double? = 0.7,
    val topP: Double? = 0.95,
    val maxTokens: Int? = 4096,
)

/** Global default agent sampling parameters per provider — configured in Settings, inherited by all agents. */
@Serializable
data class ProviderAgentDefaults(
    val anthropic: AgentParams = AgentParams(temperature = 0.7, topP = 0.95, maxTokens = 4096),
    val openai: AgentParams = AgentParams(temperature = 0.7, topP = 0.95, maxTokens = 4096),
    val gemini: AgentParams = AgentParams(temperature = 0.7, topP = 0.95, maxTokens = 4096),
    val grok: AgentParams = AgentParams(temperature = 0.7, topP = 0.95, maxTokens = 4096),
    val deepseek: AgentParams = AgentParams(temperature = 0.7, topP = 0.95, maxTokens = 4096),
    val mistral: AgentParams = AgentParams(temperature = 0.7, topP = 0.95, maxTokens = 4096),
    val ollama: AgentParams = AgentParams(temperature = 0.7, topP = 0.95, maxTokens = 4096),
    val custom: AgentParams = AgentParams(temperature = 0.7, topP = 0.95, maxTokens = 4096),
) {
    fun forProvider(provider: Provider): AgentParams = when (provider) {
        Provider.ANTHROPIC -> anthropic
        Provider.OPENAI -> openai
        Provider.GEMINI -> gemini
        Provider.GROK -> grok
        Provider.DEEPSEEK -> deepseek
        Provider.MISTRAL -> mistral
        Provider.OLLAMA -> ollama
        Provider.CUSTOM -> custom
    }

    fun updateForProvider(provider: Provider, params: AgentParams): ProviderAgentDefaults = when (provider) {
        Provider.ANTHROPIC -> copy(anthropic = params)
        Provider.OPENAI -> copy(openai = params)
        Provider.GEMINI -> copy(gemini = params)
        Provider.GROK -> copy(grok = params)
        Provider.DEEPSEEK -> copy(deepseek = params)
        Provider.MISTRAL -> copy(mistral = params)
        Provider.OLLAMA -> copy(ollama = params)
        Provider.CUSTOM -> copy(custom = params)
    }
}

