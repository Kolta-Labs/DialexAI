package com.dialex.service.cliauth

import kotlinx.coroutines.flow.Flow

/**
 * Interface representing a live running CLI process session.
 * Adheres to Interface Segregation Principle (ISP) with separate input/output/lifecycle contracts.
 */
interface CliProcessSession {
    val stdoutLines: Flow<String>
    val isAlive: Boolean
    suspend fun sendInput(text: String)
    suspend fun waitForExit(): Int
    fun cancel()
}

/**
 * Abstraction for platform-specific process execution (DIP / OCP).
 */
interface CliProcessRunner {
    fun isSupported(): Boolean
    suspend fun startInteractiveProcess(command: String): CliProcessSession
}

/**
 * Factory method for obtaining the platform-specific CLI process runner.
 */
expect fun createPlatformCliProcessRunner(): CliProcessRunner

/**
 * Strategy interface for handling a specific CLI authentication journey.
 */
interface CliAuthJourneyHandler {
    val mode: CliAuthJourneyMode
    suspend fun onSessionStarted(session: CliProcessSession)
    suspend fun onLineReceived(line: String)
    suspend fun onInputSubmitted(input: String)
    suspend fun onSessionCompleted(exitCode: Int)
}
