package com.dialex.model

import kotlinx.serialization.Serializable

/**
 * Granular tool capabilities that AI debate agents may request during execution.
 */
@Serializable
enum class ToolCapability(val label: String, val description: String) {
    WEB_SEARCH("Web Search & URLs", "Allows agents to search the web and read URL contents"),
    FILE_READ("Workspace File Read", "Allows reading files in the project workspace and attached reference directories"),
    SAFE_SHELL("Safe Shell Inspections", "Allows running read-only inspection commands (git status, log, ls, grep)"),
    FILE_WRITE("Workspace File Write", "Allows creating, editing, and deleting project files"),
    SHELL_COMMANDS("Arbitrary Shell Commands", "Allows running command-line tools and terminal scripts"),
    MCP_TOOLS("MCP Plugins", "Allows invoking configured Model Context Protocol tools")
}

/**
 * Trust mode applied to an attached workspace directory.
 */
@Serializable
enum class WorkspaceTrustMode(val label: String, val description: String) {
    TRUSTED("Trusted", "Full execution within workspace directory; CLI tools run headlessly"),
    RESTRICTED_READONLY("Restricted Read-Only", "Safe file inspection only; mutating tools strictly disallowed"),
    ISOLATED("Isolated", "Workspace excluded from direct CLI directory context; injected as reference text only")
}

/**
 * Encapsulates granular permissions configuration across Global Settings, Project Settings,
 * and Discussion/Chat overrides.
 *
 * Defaults follow "Secure & Productive by Default":
 * - Safe reads (Web Search, File Read, Safe Shell) are enabled by default.
 * - Mutating operations (File Write, Shell Commands) are guarded.
 */
@Serializable
data class PermissionConfig(
    val allowWebSearch: Boolean = true,
    val allowFileRead: Boolean = true,
    val allowSafeShell: Boolean = true,
    val allowFileWrite: Boolean = false,
    val allowShellCommands: Boolean = false,
    val allowMcpTools: Boolean = true,
    /** Whitelisted command patterns (e.g. ["git *", "npm test", "pytest"]). */
    val allowedCommandPatterns: List<String> = emptyList(),
    /** Whitelisted domain patterns (e.g. ["*.kotlinlang.org", "github.com"]). */
    val allowedDomains: List<String> = emptyList(),
    /** Per-agent seat WebSearch permission overrides (agentId -> allowed). */
    val agentWebSearchOverrides: Map<String, Boolean> = emptyMap()
) {
    /**
     * Checks whether web search is allowed for a specific agent seat.
     */
    fun isWebSearchAllowedFor(agentId: String): Boolean =
        agentWebSearchOverrides[agentId] ?: allowWebSearch
    /**
     * Checks whether a given shell command is permitted under this configuration.
     */
    fun isCommandAllowed(command: String, isAutopilot: Boolean = false): Boolean {
        if (isAutopilot || allowShellCommands) return true
        val trimmed = command.trim()
        if (allowSafeShell && isSafeShellCommand(trimmed)) return true
        return allowedCommandPatterns.any { patternMatches(it, trimmed) }
    }

    /**
     * Checks whether a given domain/URL is permitted under this configuration.
     */
    fun isDomainAllowed(domainOrUrl: String): Boolean {
        if (!allowWebSearch) return false
        if (allowedDomains.isEmpty()) return true
        val host = domainOrUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
        return allowedDomains.any { patternMatches(it, host) }
    }

    companion object {
        private val SAFE_COMMAND_PREFIXES = listOf(
            "git status", "git log", "git diff", "git show", "git branch",
            "ls ", "ls", "pwd", "grep ", "find ", "cat ", "head ", "tail "
        )

        fun isSafeShellCommand(command: String): Boolean {
            val cmd = command.trim()
            return SAFE_COMMAND_PREFIXES.any { cmd == it || cmd.startsWith(it) }
        }

        private fun patternMatches(pattern: String, value: String): Boolean {
            if (pattern == "*" || pattern == value) return true
            val regex = Regex("^" + Regex.escape(pattern).replace("\\*", ".*") + "$", RegexOption.IGNORE_CASE)
            return regex.matches(value)
        }
    }
}
