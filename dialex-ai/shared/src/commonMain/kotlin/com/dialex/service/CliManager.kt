package com.dialex.service

import com.dialex.model.Provider
import kotlinx.serialization.Serializable

@Serializable
data class CliToolDescriptor(
    val id: String,
    val name: String,
    val provider: Provider,
    val binaryName: String,
    val packageName: String,
    val installCommand: String,
    val loginCommand: String,
    val docsUrl: String,
    val description: String,
)

sealed interface CliInstallState {
    data object Idle : CliInstallState
    data class Installing(val toolId: String, val logs: List<String>) : CliInstallState
    data class Success(val toolId: String, val message: String) : CliInstallState
    data class Failed(val toolId: String, val error: String, val logs: List<String>) : CliInstallState
}

val SupportedCliTools = listOf(
    CliToolDescriptor(
        id = "claude",
        name = "Claude Code",
        provider = Provider.ANTHROPIC,
        binaryName = "claude",
        packageName = "@anthropic-ai/claude-code",
        installCommand = "npm install -g @anthropic-ai/claude-code",
        loginCommand = "claude auth login",
        docsUrl = "https://docs.claude.com/claude-code",
        description = "Anthropic's official agentic CLI for code synthesis and autonomous debate moderation."
    ),
    CliToolDescriptor(
        id = "gemini",
        name = "Antigravity (Gemini)",
        provider = Provider.GEMINI,
        binaryName = "agy",
        packageName = "@google/gemini-cli",
        installCommand = "npm install -g @google/gemini-cli",
        loginCommand = "agy",
        docsUrl = "https://github.com/google-gemini/gemini-cli",
        description = "Google DeepMind's Antigravity CLI (binary `agy`) for high-throughput multimodal debate."
    ),
    CliToolDescriptor(
        id = "codex",
        name = "Codex (OpenAI)",
        provider = Provider.OPENAI,
        binaryName = "codex",
        packageName = "@openai/codex",
        installCommand = "npm install -g @openai/codex",
        loginCommand = "codex login",
        docsUrl = "https://developers.openai.com/codex",
        description = "OpenAI Codex CLI runner for reasoning, debate synthesis, and code intelligence."
    ),
    CliToolDescriptor(
        id = "ollama",
        name = "Ollama (Local LLMs)",
        provider = Provider.CUSTOM,
        binaryName = "ollama",
        packageName = "ollama",
        installCommand = "brew install ollama",
        loginCommand = "ollama serve",
        docsUrl = "https://ollama.com",
        description = "Runs open-weight models (Llama 3, Mistral, DeepSeek) completely offline on your device."
    )
)

/**
 * Returns true if the provider has an official, first-party dedicated CLI tool
 * supported by Dialex (Anthropic Claude Code, Google Gemini / Antigravity, OpenAI Codex).
 * Cloud-only API providers (Grok, DeepSeek, Mistral) return false.
 */
fun hasDedicatedCli(provider: Provider): Boolean =
    provider == Provider.ANTHROPIC || provider == Provider.GEMINI || provider == Provider.OPENAI

expect fun isPlatformCliSupported(): Boolean

expect fun checkPlatformCliLogins(): Map<String, Boolean>

expect fun checkPlatformCliAvailability(): Map<String, Boolean>

expect fun launchCliLoginTerminal(command: String): Result<Unit>

expect suspend fun runCliInstallCommand(command: String, onOutputLine: (String) -> Unit): Result<Unit>
