package com.dialex.service

import com.dialex.model.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliManagerTest {

    @Test
    fun supportedCliTools_containsCoreEngines() {
        val toolIds = SupportedCliTools.map { it.id }
        assertTrue(toolIds.contains("claude"), "Should include Claude Code")
        assertTrue(toolIds.contains("gemini"), "Should include Gemini / Antigravity")
        assertTrue(toolIds.contains("codex"), "Should include Codex")
        assertTrue(toolIds.contains("ollama"), "Should include Ollama")
    }

    @Test
    fun supportedCliTools_hasValidCommands() {
        SupportedCliTools.forEach { tool ->
            assertTrue(tool.binaryName.isNotBlank(), "Binary name should not be blank for ${tool.name}")
            assertTrue(tool.installCommand.isNotBlank(), "Install command should not be blank for ${tool.name}")
            assertTrue(tool.loginCommand.isNotBlank(), "Login command should not be blank for ${tool.name}")
            assertTrue(tool.docsUrl.startsWith("http"), "Docs URL should be HTTP link for ${tool.name}")
        }
    }

    @Test
    fun supportedCliTools_providerMapping() {
        val claude = SupportedCliTools.firstOrNull { it.provider == Provider.ANTHROPIC }
        assertNotNull(claude)
        assertEquals("claude", claude.binaryName)

        val gemini = SupportedCliTools.firstOrNull { it.provider == Provider.GEMINI }
        assertNotNull(gemini)
        assertEquals("agy", gemini.binaryName)

        val codex = SupportedCliTools.firstOrNull { it.provider == Provider.OPENAI }
        assertNotNull(codex)
        assertEquals("codex", codex.binaryName)
    }

    @Test
    fun hasDedicatedCli_onlyReturnsTrueForToolsWithDedicatedCli() {
        assertTrue(hasDedicatedCli(Provider.ANTHROPIC), "Anthropic has Claude Code CLI")
        assertTrue(hasDedicatedCli(Provider.GEMINI), "Gemini has Antigravity / Gemini CLI")
        assertTrue(hasDedicatedCli(Provider.OPENAI), "OpenAI has Codex CLI")

        // Cloud-only API providers should return false
        assertFalse(hasDedicatedCli(Provider.GROK), "Grok does not have a CLI")
        assertFalse(hasDedicatedCli(Provider.DEEPSEEK), "DeepSeek does not have a CLI")
        assertFalse(hasDedicatedCli(Provider.MISTRAL), "Mistral does not have a dedicated CLI")
        assertFalse(hasDedicatedCli(Provider.CUSTOM), "Custom without command is not dedicated")
    }
}

