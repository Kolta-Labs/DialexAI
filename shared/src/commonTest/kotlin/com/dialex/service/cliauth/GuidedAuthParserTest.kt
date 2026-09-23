package com.dialex.service.cliauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GuidedAuthParserTest {

    @Test
    fun testStripAnsi() {
        val ansiText = "\u001B[32m\u001B[1mAuthentication Successful!\u001B[0m"
        assertEquals("Authentication Successful!", GuidedAuthParser.stripAnsi(ansiText))
    }

    @Test
    fun testExtractClaudeOAuthUrl() {
        val stdout = """
            Please visit the following URL to log in:
            https://claude.ai/oauth/authorize?client_id=12345&response_type=code
            Waiting for authentication...
        """.trimIndent()

        val url = GuidedAuthParser.extractOAuthUrl(stdout)
        assertNotNull(url)
        assertTrue(url.contains("claude.ai/oauth/authorize"))
    }

    @Test
    fun testExtractGoogleOAuthUrl() {
        val stdout = """
            Go to https://accounts.google.com/o/oauth2/auth?client_id=gemini-cli to authenticate.
            Enter authorization code:
        """.trimIndent()

        val url = GuidedAuthParser.extractOAuthUrl(stdout)
        assertNotNull(url)
        assertTrue(url.contains("accounts.google.com"))
    }

    @Test
    fun testIsWaitingForCode() {
        val prompt1 = "Paste your authorization code here:"
        val prompt2 = "Enter the code displayed in your browser: "
        val prompt3 = "Waiting for browser callback on http://localhost:8080"

        assertTrue(GuidedAuthParser.isWaitingForCode(prompt1))
        assertTrue(GuidedAuthParser.isWaitingForCode(prompt2))
        assertFalse(GuidedAuthParser.isWaitingForCode(prompt3))
    }

    @Test
    fun testIsSuccessful() {
        val success1 = "Successfully logged in as user@example.com."
        val success2 = "Authentication successful! Credentials saved to ~/.claude.json"
        val failed = "OAuth error: Token expired."

        assertTrue(GuidedAuthParser.isSuccessful(success1))
        assertTrue(GuidedAuthParser.isSuccessful(success2))
        assertFalse(GuidedAuthParser.isSuccessful(failed))
        assertTrue(GuidedAuthParser.isAuthError(failed))
    }
}
