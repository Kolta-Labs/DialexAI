package com.dialex.service.cliauth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Service managing in-app CLI authentication sessions.
 * Coordinates between platform process runner and selected journey strategies.
 */
class CliAuthSessionService(
    private val processRunner: CliProcessRunner = createPlatformCliProcessRunner(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    val guidedStrategy = GuidedAuthStrategy()
    val terminalStrategy = TerminalAuthStrategy()

    private val _currentMode = MutableStateFlow(CliAuthJourneyMode.GUIDED)
    val currentMode: StateFlow<CliAuthJourneyMode> = _currentMode.asStateFlow()

    private var activeSession: CliProcessSession? = null
    private var sessionJob: Job? = null

    fun setJourneyMode(mode: CliAuthJourneyMode) {
        _currentMode.value = mode
    }

    fun startSession(
        command: String,
        scope: CoroutineScope
    ) {
        cancelSession()
        guidedStrategy.reset()
        terminalStrategy.reset()

        sessionJob = scope.launch(dispatcher) {
            try {
                if (!processRunner.isSupported()) {
                    val errorMsg = "Interactive CLI execution is not supported on this platform."
                    guidedStrategy.onSessionCompleted(1)
                    terminalStrategy.onLineReceived(errorMsg)
                    terminalStrategy.onSessionCompleted(1)
                    return@launch
                }

                val session = processRunner.startInteractiveProcess(command)
                activeSession = session

                guidedStrategy.onSessionStarted(session)
                terminalStrategy.onSessionStarted(session)

                val readJob = launch(dispatcher) {
                    session.stdoutLines.collect { line ->
                        guidedStrategy.onLineReceived(line)
                        terminalStrategy.onLineReceived(line)
                    }
                }

                val exitCode = session.waitForExit()
                readJob.cancel()

                guidedStrategy.onSessionCompleted(exitCode)
                terminalStrategy.onSessionCompleted(exitCode)
            } catch (e: Exception) {
                val err = e.message ?: "Session failed"
                guidedStrategy.onLineReceived("Error: $err")
                guidedStrategy.onSessionCompleted(-1)
                terminalStrategy.onLineReceived("Error: $err")
                terminalStrategy.onSessionCompleted(-1)
            } finally {
                activeSession = null
            }
        }
    }

    suspend fun submitInput(input: String) {
        withContext(dispatcher) {
            val formatted = if (!input.endsWith("\n")) "$input\n" else input
            when (_currentMode.value) {
                CliAuthJourneyMode.GUIDED -> guidedStrategy.onInputSubmitted(formatted)
                CliAuthJourneyMode.TERMINAL -> terminalStrategy.onInputSubmitted(formatted)
            }
        }
    }

    fun cancelSession() {
        sessionJob?.cancel()
        sessionJob = null
        activeSession?.cancel()
        activeSession = null
    }
}
