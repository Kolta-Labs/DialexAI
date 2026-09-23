package com.dialex.service.cliauth

/**
 * Utility responsible for extracting URLs, prompts, and authentication statuses from CLI output.
 * Follows Single Responsibility Principle (SRP).
 */
object GuidedAuthParser {

    private val ANSI_REGEX = Regex("\u001B\\[[;\\d]*[ -/]*[@-~]")
    private val URL_REGEX = Regex("https?://[^\\s\"'<>)\\]]+")

    fun stripAnsi(text: String): String = ANSI_REGEX.replace(text, "")

    fun extractUrls(text: String): List<String> {
        val clean = stripAnsi(text)
        return URL_REGEX.findAll(clean).map { it.value.trimEnd('.', ',', ';', ':') }.toList()
    }

    fun extractOAuthUrl(text: String): String? {
        val urls = extractUrls(text)
        return urls.firstOrNull { url ->
            val lower = url.lowercase()
            lower.contains("oauth") ||
                lower.contains("auth") ||
                lower.contains("login") ||
                lower.contains("accounts.google.com") ||
                lower.contains("claude.ai") ||
                lower.contains("openai.com") ||
                lower.contains("device") ||
                lower.contains("authorize")
        } ?: urls.firstOrNull()
    }

    fun isWaitingForCode(text: String): Boolean {
        val clean = stripAnsi(text).lowercase()
        return clean.contains("paste") ||
            clean.contains("enter authorization code") ||
            clean.contains("enter code") ||
            clean.contains("verification code") ||
            clean.contains("enter the code") ||
            clean.contains("authorization code:") ||
            clean.contains("code:") ||
            (clean.contains("press enter") && clean.contains("after"))
    }

    fun isSuccessful(text: String): Boolean {
        val clean = stripAnsi(text).lowercase()
        return clean.contains("logged in") ||
            clean.contains("authentication successful") ||
            clean.contains("login successful") ||
            clean.contains("authenticated") ||
            clean.contains("credentials saved") ||
            clean.contains("welcome") ||
            clean.contains("success")
    }

    fun isAuthError(text: String): Boolean {
        val clean = stripAnsi(text).lowercase()
        return clean.contains("authentication failed") ||
            clean.contains("invalid code") ||
            clean.contains("expired") ||
            clean.contains("unauthorized") ||
            clean.contains("access denied") ||
            clean.contains("oauth error")
    }
}
