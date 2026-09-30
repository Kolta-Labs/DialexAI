package com.dialex.orchestrator

import com.dialex.export.toMarkdown
import com.dialex.model.Agent
import com.dialex.model.ContextGuard
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.label
import com.dialex.model.Provider
import com.dialex.model.RoundMode
import com.dialex.runner.AgentReply
import com.dialex.runner.AgentRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformReliabilityTest {

    @Test
    fun test_multiple_agents_with_same_provider_preserve_unique_names() = runTest {
        val agent1 = Agent(
            id = "seat_1001",
            provider = Provider.ANTHROPIC,
            model = "claude-sonnet-5",
            displayName = "Subject Matter Expert"
        )
        val agent2 = Agent(
            id = "seat_1002",
            provider = Provider.ANTHROPIC,
            model = "claude-sonnet-5",
            displayName = "Theoretical Scientist & First-Principles Modeler"
        )
        val agent3 = Agent(
            id = "seat_1003",
            provider = Provider.ANTHROPIC,
            model = "claude-sonnet-5",
            displayName = "Cultural Critic & Public Intellectual"
        )

        val config = DebateConfig(
            topic = "Can AI replace product managers",
            primary = agent1,
            secondary = agent2,
            tertiary = agent3,
            maxRounds = 1
        )

        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply = AgentReply("Argument from ${agent.label()}")
        }

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        val discussion = Discussion(
            id = "disc_1",
            projectId = "proj_1",
            name = "Benchmark Deliberation",
            config = config,
            status = DiscussionStatus.COMPLETED,
            transcript = result.transcript,
            conclusion = result.conclusion
        )

        val markdown = discussion.toMarkdown()

        assertTrue(
            markdown.contains("### Subject Matter Expert — round 1"),
            "Markdown must contain Subject Matter Expert header"
        )
        assertTrue(
            markdown.contains("### Theoretical Scientist & First-Principles Modeler — round 1"),
            "Markdown must contain Theoretical Scientist header"
        )
        assertTrue(
            markdown.contains("### Cultural Critic & Public Intellectual — round 1"),
            "Markdown must contain Cultural Critic header (no persona collision with second speaker)"
        )
        // Verify turn count
        assertEquals(3, result.transcript.size)
        assertEquals("seat_1001", result.transcript[0].seatId)
        assertEquals("seat_1002", result.transcript[1].seatId)
        assertEquals("seat_1003", result.transcript[2].seatId)
    }

    @Test
    fun test_turn_failure_in_late_round_produces_emergency_conclusion() = runTest {
        val claude = Agent(id = "seat_c", provider = Provider.ANTHROPIC, model = "claude-sonnet-5", displayName = "Claude")
        val gemini = Agent(id = "seat_g", provider = Provider.GEMINI, model = "gemini-3.7-flash", displayName = "Gemini")

        val config = DebateConfig(
            topic = "Platform Reliability Architecture",
            primary = claude,
            secondary = gemini,
            roundMode = RoundMode.FIXED,
            maxRounds = 6
        )

        var callCount = 0
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
                // Rounds 1-4 take 8 turns (2 agents * 4 rounds). Turn 9 is Round 5 Turn 1.
                // Fail on turn 9.
                if (callCount in 9..10) {
                    throw RuntimeException("Simulated provider timeout / connection drop")
                }
                return AgentReply("Reply ${callCount} by ${agent.label()}")
            }
        }

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        assertNull(result.error, "Late turn failure must not abort debate as error")
        assertNotNull(result.conclusion, "Emergency synthesis must produce a final conclusion")
        assertNotNull(result.warning, "Emergency synthesis must record a warning")
        assertTrue(result.warning!!.contains("emergency synthesis"), "Warning must mention emergency synthesis")
        assertTrue(result.transcript.any { it.isError && it.round == 5 }, "Transcript must record turn error warning")
    }

    @Test
    fun test_preflight_context_guard_trims_oversized_transcript() {
        val largeTurnContent = "Word ".repeat(20_000) // ~25,000 tokens each
        val messages = (1..10).map { i ->
            DebateMessage(
                seatId = "seat_$i",
                provider = Provider.OPENAI,
                authorDisplayName = "Agent $i",
                round = i,
                content = largeTurnContent,
                tokensOut = 20_000
            )
        }
        // Total estimated tokens = 200,000 tokens, well exceeding OpenAI safe limit of 120,000 * 0.85 = 102,000
        val protectedTranscript = ContextGuard.ensurePayloadWithinCeiling(Provider.OPENAI, messages)

        assertEquals(6, protectedTranscript.size, "Payload exceeding 85% ceiling must be emergency trimmed to 6 recent turns")
        assertEquals(messages.takeLast(6), protectedTranscript)
    }

    @Test
    fun test_regression_benchmark_9_rounds_turn_3_drop() = runTest {
        val sme = Agent(id = "seat_sme", provider = Provider.ANTHROPIC, model = "claude-sonnet-5", displayName = "Subject Matter Expert")
        val theorist = Agent(id = "seat_theorist", provider = Provider.ANTHROPIC, model = "claude-sonnet-5", displayName = "Theoretical Scientist & First-Principles Modeler")
        val critic = Agent(id = "seat_critic", provider = Provider.ANTHROPIC, model = "claude-sonnet-5", displayName = "Cultural Critic & Public Intellectual")

        val config = DebateConfig(
            topic = "Can AI replace product managers",
            primary = sme,
            secondary = theorist,
            tertiary = critic,
            roundMode = RoundMode.FIXED,
            maxRounds = 9
        )

        var callCount = 0
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
                if (agent.id == "seat_critic" && transcript.count { it.round == 9 } >= 2) {
                    throw RuntimeException("Simulated Anthropic context_length_exceeded / rate limit in Round 9 Turn 3")
                }
                return AgentReply("Deliberation turn $callCount from ${agent.label()}")
            }
        }

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        // 1. Must produce emergency conclusion
        assertNotNull(result.conclusion, "Final executive outcome must be salvaged")
        assertNotNull(result.warning, "Status warning must be populated")
        assertNull(result.error, "Debate must not crash or fail completely")

        // 2. Attribution integrity check: All 3 agents must have distinct labels in Markdown export
        val discussion = Discussion(
            id = "benchmark_disc",
            projectId = "benchmark_proj",
            name = "Benchmark Deliberation",
            config = config,
            status = DiscussionStatus.COMPLETED_WITH_WARNING,
            transcript = result.transcript,
            conclusion = result.conclusion,
            warning = result.warning
        )

        val md = discussion.toMarkdown()
        assertTrue(md.contains("### Subject Matter Expert — round 1"))
        assertTrue(md.contains("### Theoretical Scientist & First-Principles Modeler — round 1"))
        assertTrue(md.contains("### Cultural Critic & Public Intellectual — round 1"))

        // In Round 9, Turn 3 should be marked as warning
        assertTrue(md.contains("> ⚠️ **Warning (round 9):** Turn dropped for Cultural Critic & Public Intellectual"))
    }
}
