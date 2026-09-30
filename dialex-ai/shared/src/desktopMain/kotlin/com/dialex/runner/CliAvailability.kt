package com.dialex.runner

/**
 * Checks whether each known CLI binary resolves to a real file via [resolveBinary] — i.e.
 * the same lookup [CliAgentRunner] actually uses to launch it, so this status can't say
 * "installed" for something that then fails to spawn (or vice versa).
 * Google renamed the Gemini CLI to "Antigravity" — its binary is `agy`; older installs
 * may still have `gemini` or `antigravity`, so all three are checked.
 */
fun checkCliAvailability(): Map<String, Boolean> {
    fun onPath(bin: String): Boolean {
        val path = resolveBinary(bin)
        return "/" in path && java.io.File(path).exists()
    }

    val claude = onPath("claude")
    val codex = onPath("codex")
    val gemini = onPath("agy") || onPath("antigravity") || onPath("gemini")

    return mapOf(
        "Claude Code" to claude,
        "Claude" to claude,
        "claude" to claude,
        "ANTHROPIC" to claude,
        "Codex (OpenAI)" to codex,
        "Codex" to codex,
        "codex" to codex,
        "ChatGPT" to codex,
        "OPENAI" to codex,
        "Antigravity (Gemini)" to gemini,
        "Antigravity" to gemini,
        "Gemini" to gemini,
        "agy" to gemini,
        "antigravity" to gemini,
        "gemini" to gemini,
        "GEMINI" to gemini,
    )
}

/**
 * Checks whether the installed CLI tool has active credentials / login session on the machine.
 * When installed but unauthenticated, the UI displays "CLI - Not Logged In".
 */
fun checkCliLoginStatus(): Map<String, Boolean> {
    val home = System.getProperty("user.home") ?: ""

    val claudeLoggedIn = runCatching {
        val jsonFile = java.io.File(home, ".claude.json")
        val claudeDir = java.io.File(home, ".claude")
        val configDir = java.io.File(home, ".config/claude")
        val envKey = System.getenv("ANTHROPIC_API_KEY")
        !envKey.isNullOrBlank() ||
            (jsonFile.exists() && jsonFile.readText().let {
                "oauthAccount" in it ||
                    "hasCompletedOnboarding" in it ||
                    "sessionKey" in it ||
                    "accountUuid" in it
            }) ||
            (claudeDir.isDirectory && claudeDir.listFiles()?.isNotEmpty() == true) ||
            (configDir.isDirectory && configDir.listFiles()?.isNotEmpty() == true)
    }.getOrDefault(false)

    val geminiLoggedIn = runCatching {
        val geminiDir = java.io.File(home, ".gemini")
        val antigravityDir = java.io.File(home, ".antigravity")
        val envKey = System.getenv("GEMINI_API_KEY")
        !envKey.isNullOrBlank() ||
            java.io.File(geminiDir, "jetski-standalone-oauth-token").exists() ||
            java.io.File(geminiDir, "oauth_credentials.json").exists() ||
            (antigravityDir.isDirectory && antigravityDir.listFiles()?.isNotEmpty() == true)
    }.getOrDefault(false)

    val codexLoggedIn = runCatching {
        val codexDir = java.io.File(home, ".codex")
        val chatgptDir = java.io.File(home, ".chatgpt")
        val envKey = System.getenv("OPENAI_API_KEY")
        !envKey.isNullOrBlank() ||
            (codexDir.isDirectory && codexDir.listFiles()?.isNotEmpty() == true) ||
            (chatgptDir.isDirectory && chatgptDir.listFiles()?.isNotEmpty() == true)
    }.getOrDefault(false)

    return mapOf(
        "Claude Code" to claudeLoggedIn,
        "Claude" to claudeLoggedIn,
        "claude" to claudeLoggedIn,
        "ANTHROPIC" to claudeLoggedIn,
        "Codex (OpenAI)" to codexLoggedIn,
        "Codex" to codexLoggedIn,
        "codex" to codexLoggedIn,
        "ChatGPT" to codexLoggedIn,
        "OPENAI" to codexLoggedIn,
        "Antigravity (Gemini)" to geminiLoggedIn,
        "Antigravity" to geminiLoggedIn,
        "Gemini" to geminiLoggedIn,
        "agy" to geminiLoggedIn,
        "antigravity" to geminiLoggedIn,
        "gemini" to geminiLoggedIn,
        "GEMINI" to geminiLoggedIn,
    )
}
