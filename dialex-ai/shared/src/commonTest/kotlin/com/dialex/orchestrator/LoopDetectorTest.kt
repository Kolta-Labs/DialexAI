package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.AntiLoopConfig
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.LoopAction
import com.dialex.model.LoopInspectionResult
import com.dialex.model.Provider
import com.dialex.runner.AgentReply
import com.dialex.runner.AgentRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoopDetectorTest {

    @Test
    fun test_verbatim_duplicate_block_intercepted() {
        val original = """
        The Cybernetic Anschluss: On the Hubris of the Grand Synthesis.
        We observe that categorical imperatives cannot be reconciled with stochastic gradient descent without introducing formal contradictions into the ontological framework.
        Specifically, as demonstrated in Theorem 4.2 of recursive semantics, the alignment guarantee degenerates asymptotically.
        """.trimIndent()

        val duplicate = """
        The Cybernetic Anschluss: On the Hubris of the Grand Synthesis.
        We observe that categorical imperatives cannot be reconciled with stochastic gradient descent without introducing formal contradictions into the ontological framework.
        Specifically, as demonstrated in Theorem 4.2 of recursive semantics, the alignment guarantee degenerates asymptotically.
        """.trimIndent()

        val result = LoopDetector.inspect(
            newText = duplicate,
            priorTurns = listOf(original),
            threshold = 0.65,
            maxContiguous = 100
        )

        assertIs<LoopInspectionResult.LoopDetected>(result)
        assertTrue(result.longestContiguousMatch.length >= 100)
    }

    @Test
    fun test_jaccard_ngram_similarity_threshold() {
        val turn1 = "The fundamental constraint in distributed consensus is network partitioning and latency asymmetry across wide-area networks."
        val highOverlapParaphrase = "The fundamental constraint in distributed consensus protocols is network partitioning and latency asymmetry across regional networks."
        val novelCounterArgument = "In contrast to network partitioning, modern cryptographic proofs like zk-SNARKs eliminate the latency verification bottleneck entirely."

        val detected = LoopDetector.inspect(
            newText = highOverlapParaphrase,
            priorTurns = listOf(turn1),
            threshold = 0.60
        )
        assertIs<LoopInspectionResult.LoopDetected>(detected)

        val clean = LoopDetector.inspect(
            newText = novelCounterArgument,
            priorTurns = listOf(turn1),
            threshold = 0.60
        )
        assertIs<LoopInspectionResult.Clean>(clean)
    }

    @Test
    fun test_retry_waterfall_applies_temperature_bump_and_directive() = runTest {
        val duplicateEssay = "We have firmly established that automated systems cannot exhibit authentic judgment without empathetic context."
        val novelEssay = "Examining empirical telemetry from automated trading systems reveals that deterministic execution outperforms intuitive judgment in high-frequency regimes."

        var callCount = 0
        var receivedDirective = false
        var receivedBumpedTemp = false

        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                callCount++
                if (agent.id == "seat_claude" && callCount > 2) {
                    if (commonInstructions.contains("REPETITION DETECTED") || agent.systemPrompt.contains("REPETITION DETECTED")) {
                        receivedDirective = true
                    }
                    if ((agent.temperature ?: 0.0) > 0.7) {
                        receivedBumpedTemp = true
                    }
                }

                return if (agent.id == "seat_claude") {
                    if (callCount == 1) {
                        AgentReply(duplicateEssay)
                    } else if (callCount == 3) {
                        // In round 2 attempt 0, repeat duplicate
                        AgentReply(duplicateEssay)
                    } else {
                        // In round 2 retry attempt 1, provide novel essay
                        AgentReply(novelEssay)
                    }
                } else {
                    AgentReply("Opposing perspective by Gemini in round 1")
                }
            }
        }

        val config = DebateConfig(
            topic = "Automation and Judgment",
            primary = Agent(Provider.ANTHROPIC, "claude-3-5-sonnet", id = "seat_claude", temperature = 0.7),
            secondary = Agent(Provider.GEMINI, "gemini-1.5-flash", id = "seat_gemini", temperature = 0.7),
            maxRounds = 2,
            antiLoop = AntiLoopConfig(
                enabled = true,
                maxSimilarityThreshold = 0.60,
                maxRetries = 1,
                retryTemperatureBump = 0.25
            )
        )

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        assertTrue(receivedDirective, "Retry attempt must include anti-loop directive")
        assertTrue(receivedBumpedTemp, "Retry attempt must have temperature bumped")
        assertNull(result.error)
        assertTrue(result.transcript.any { it.isLoopRecovered }, "Turn should be flagged as loop-recovered")
    }

    @Test
    fun test_fallback_circuit_breaker_prevents_transcript_crash() = runTest {
        val persistentDuplicate = "Persistent identical argument repeated forever."

        val runner = object : AgentRunner {
            var calls = 0
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                calls++
                return AgentReply(persistentDuplicate)
            }
        }

        val config = DebateConfig(
            topic = "Loop Test",
            primary = Agent(Provider.ANTHROPIC, "claude-3-5-sonnet", id = "seat_claude"),
            secondary = Agent(Provider.GEMINI, "gemini-1.5-flash", id = "seat_gemini"),
            maxRounds = 2,
            antiLoop = AntiLoopConfig(
                enabled = true,
                maxRetries = 1,
                fallbackAction = LoopAction.CONVERT_TO_CONCESSION
            )
        )

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        assertNull(result.error, "Circuit breaker must not crash debate with error")
        assertTrue(result.transcript.any { it.isStalledConcession }, "Repeated turn should be converted to clean concession")
        assertNotNull(result.conclusion, "Debate must conclude successfully")
    }
}
