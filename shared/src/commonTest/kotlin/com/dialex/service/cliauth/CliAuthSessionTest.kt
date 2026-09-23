package com.dialex.service.cliauth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CliAuthSessionTest {

    private class FakeCliProcessSession : CliProcessSession {
        val flow = MutableSharedFlow<String>(replay = 10, extraBufferCapacity = 10)
        val receivedInputs = mutableListOf<String>()
        val exitDeferred = CompletableDeferred<Int>()
        var cancelled = false

        override val stdoutLines: Flow<String> = flow.asSharedFlow()
        override val isAlive: Boolean = !cancelled

        override suspend fun sendInput(text: String) {
            receivedInputs.add(text)
        }

        override suspend fun waitForExit(): Int = exitDeferred.await()

        override fun cancel() {
            cancelled = true
            exitDeferred.complete(-1)
        }
    }

    private class FakeCliProcessRunner(val session: FakeCliProcessSession) : CliProcessRunner {
        override fun isSupported(): Boolean = true
        override suspend fun startInteractiveProcess(command: String): CliProcessSession = session
    }

    @Test
    fun testGuidedStrategyExtractsUrlAndSubmitsCode() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val session = FakeCliProcessSession()
        val runner = FakeCliProcessRunner(session)
        val service = CliAuthSessionService(runner, dispatcher)

        service.startSession("claude login", this)
        testScheduler.advanceUntilIdle()

        session.flow.emit("Opening auth: https://claude.ai/oauth/authorize?req=1")
        testScheduler.advanceUntilIdle()
        assertEquals("https://claude.ai/oauth/authorize?req=1", service.guidedStrategy.extractedUrl.value)
        assertIs<CliAuthStatus.AwaitingBrowserAuth>(service.guidedStrategy.status.value)

        session.flow.emit("Please enter authorization code:")
        testScheduler.advanceUntilIdle()
        assertIs<CliAuthStatus.AwaitingCodeInput>(service.guidedStrategy.status.value)

        service.submitInput("auth_code_12345")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("auth_code_12345\n"), session.receivedInputs)

        session.flow.emit("Authentication successful!")
        testScheduler.advanceUntilIdle()
        session.exitDeferred.complete(0)
        testScheduler.advanceUntilIdle()

        assertIs<CliAuthStatus.Success>(service.guidedStrategy.status.value)
    }

    @Test
    fun testTerminalStrategyReceivesLinesAndInputs() = runTest(UnconfinedTestDispatcher()) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val session = FakeCliProcessSession()
        val runner = FakeCliProcessRunner(session)
        val service = CliAuthSessionService(runner, dispatcher)
        service.setJourneyMode(CliAuthJourneyMode.TERMINAL)

        service.startSession("claude login", this)
        testScheduler.advanceUntilIdle()

        session.flow.emit("Line 1: Init")
        session.flow.emit("Line 2: Ready")
        testScheduler.advanceUntilIdle()

        assertTrue(service.terminalStrategy.lines.value.any { it.contains("Line 1: Init") })
        assertTrue(service.terminalStrategy.lines.value.any { it.contains("Line 2: Ready") })

        service.submitInput("my_test_command")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("my_test_command\n"), session.receivedInputs)

        session.exitDeferred.complete(0)
        testScheduler.advanceUntilIdle()
        assertIs<CliAuthStatus.Success>(service.terminalStrategy.status.value)
    }
}
