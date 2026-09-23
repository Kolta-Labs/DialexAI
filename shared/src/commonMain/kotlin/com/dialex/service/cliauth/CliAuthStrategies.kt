package com.dialex.service.cliauth

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Strategy 1: Guided OAuth Journey Handler.
 * Parses stdout to identify OAuth URLs and prompt states, guiding the user step-by-step.
 */
class GuidedAuthStrategy : CliAuthJourneyHandler {
    override val mode: CliAuthJourneyMode = CliAuthJourneyMode.GUIDED

    private val _status = MutableStateFlow<CliAuthStatus>(CliAuthStatus.Idle)
    val status: StateFlow<CliAuthStatus> = _status.asStateFlow()

    private val _extractedUrl = MutableStateFlow<String?>(null)
    val extractedUrl: StateFlow<String?> = _extractedUrl.asStateFlow()

    private var activeSession: CliProcessSession? = null

    override suspend fun onSessionStarted(session: CliProcessSession) {
        activeSession = session
        _status.value = CliAuthStatus.Starting
    }

    override suspend fun onLineReceived(line: String) {
        val clean = GuidedAuthParser.stripAnsi(line)
        val url = GuidedAuthParser.extractOAuthUrl(clean)
        if (url != null && _extractedUrl.value == null) {
            _extractedUrl.value = url
            _status.value = CliAuthStatus.AwaitingBrowserAuth(url)
        }

        if (GuidedAuthParser.isWaitingForCode(clean)) {
            _status.value = CliAuthStatus.AwaitingCodeInput(clean.trim(), _extractedUrl.value)
        } else if (GuidedAuthParser.isSuccessful(clean)) {
            _status.value = CliAuthStatus.Success(clean.trim())
        } else if (GuidedAuthParser.isAuthError(clean)) {
            _status.value = CliAuthStatus.Failed(clean.trim(), null)
        }
    }

    override suspend fun onInputSubmitted(input: String) {
        activeSession?.sendInput(input)
        _status.value = CliAuthStatus.Running("Verifying authentication code...")
    }

    override suspend fun onSessionCompleted(exitCode: Int) {
        if (exitCode == 0) {
            _status.value = CliAuthStatus.Success("Authentication completed successfully!")
        } else if (_status.value !is CliAuthStatus.Success) {
            _status.value = CliAuthStatus.Failed("Process exited with code $exitCode", exitCode)
        }
    }

    fun reset() {
        _status.value = CliAuthStatus.Idle
        _extractedUrl.value = null
        activeSession = null
    }
}

/**
 * Strategy 2: Interactive In-App Terminal Console Journey Handler.
 * Buffers streaming terminal lines and handles bidirectional terminal interactions.
 */
class TerminalAuthStrategy(private val maxLines: Int = 500) : CliAuthJourneyHandler {
    override val mode: CliAuthJourneyMode = CliAuthJourneyMode.TERMINAL

    private val _lines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val lines: StateFlow<ImmutableList<String>> = _lines.asStateFlow()

    private val _status = MutableStateFlow<CliAuthStatus>(CliAuthStatus.Idle)
    val status: StateFlow<CliAuthStatus> = _status.asStateFlow()

    private var activeSession: CliProcessSession? = null

    override suspend fun onSessionStarted(session: CliProcessSession) {
        activeSession = session
        _lines.value = persistentListOf("🚀 Starting terminal session...")
        _status.value = CliAuthStatus.Starting
    }

    override suspend fun onLineReceived(line: String) {
        val clean = GuidedAuthParser.stripAnsi(line)
        val current = _lines.value
        val updated = if (current.size >= maxLines) {
            (current.drop(1) + clean).toImmutableList()
        } else {
            (current + clean).toImmutableList()
        }
        _lines.value = updated
        _status.value = CliAuthStatus.Running(clean)
    }

    override suspend fun onInputSubmitted(input: String) {
        val current = _lines.value
        _lines.value = (current + "> ${input.trimEnd()}").toImmutableList()
        activeSession?.sendInput(input)
    }

    override suspend fun onSessionCompleted(exitCode: Int) {
        val current = _lines.value
        val completionMsg = if (exitCode == 0) {
            "✅ Process finished successfully (exit code 0)"
        } else {
            "❌ Process finished with exit code $exitCode"
        }
        _lines.value = (current + completionMsg).toImmutableList()
        if (exitCode == 0) {
            _status.value = CliAuthStatus.Success(completionMsg)
        } else {
            _status.value = CliAuthStatus.Failed(completionMsg, exitCode)
        }
    }

    fun reset() {
        _lines.value = persistentListOf()
        _status.value = CliAuthStatus.Idle
        activeSession = null
    }
}
