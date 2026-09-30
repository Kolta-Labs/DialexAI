package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.CompactionStrategy
import com.dialex.model.CostEfficiencyConfig
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.PricingTable
import com.dialex.model.Provider
import com.dialex.model.RoundMode
import com.dialex.model.label
import com.dialex.runner.AgentReply
import com.dialex.runner.AgentRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CostEfficiencyTest {

    private fun claude() = Agent(Provider.ANTHROPIC, "claude-3-5-sonnet", id = "seat_claude")
    private fun gemini() = Agent(Provider.GEMINI, "gemini-1.5-flash", id = "seat_gemini")

    @Test
    fun test_pricing_table_spend_calculation() {
        // Claude 3.5 Sonnet: input = $3/M, output = $15/M, cached = $0.30/M
        val spend = PricingTable.calculateSpend(
            model = "claude-3-5-sonnet",
            tokensIn = 100_000,
            tokensOut = 10_000,
            tokensCached = 80_000
        )
        // uncachedIn = 20,000 * 3.0 / 1M = 0.06
        // cachedIn = 80,000 * 0.30 / 1M = 0.024
        // output = 10,000 * 15.0 / 1M = 0.15
        // total = 0.06 + 0.024 + 0.15 = 0.234
        val diff = kotlin.math.abs(spend - 0.234)
        assertTrue(diff < 0.0001, "Expected ~0.234 USD but was $spend")
    }

    @Test
    fun test_dynamic_compaction_triggers_on_token_density_not_count() = runTest {
        var compactionTriggered = false
        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                if (agent.systemPrompt.contains("debate knowledge synthesizer") ||
                    agent.systemPrompt.contains("Summarize this debate") ||
                    modelOverride == "claude-haiku-4-5-20251001"
                ) {
                    compactionTriggered = true
                    return AgentReply("[SHARED MEMORY BLACKBOARD — ROUND 2]\n• TOPIC ESSENCE: AI efficiency")
                }
                // Each turn reports 2,500 tokens out
                return AgentReply("Substantial dense argument by ${agent.provider}", tokensIn = 500, tokensOut = 2500)
            }
        }

        // 4 messages each with 2,500 tokens = 10,000 tokens (far above 6,000 threshold, but only 4 turns)
        val initialTranscript = (1..4).map { i ->
            DebateMessage(
                seatId = if (i % 2 == 1) "seat_claude" else "seat_gemini",
                provider = if (i % 2 == 1) Provider.ANTHROPIC else Provider.GEMINI,
                authorDisplayName = "Speaker $i",
                round = (i - 1) / 2 + 1,
                content = "Dense technical proof $i",
                tokensOut = 2500
            )
        }

        val config = DebateConfig(
            topic = "Optimal AI scaling",
            primary = claude(),
            secondary = gemini(),
            roundMode = RoundMode.FIXED,
            maxRounds = 3,
            costEfficiency = CostEfficiencyConfig(
                enabled = true,
                triggerTokenThreshold = 6_000,
                strategy = CompactionStrategy.HYBRID_BLACKBOARD_RECENT
            )
        )

        val orchestrator = DebateOrchestrator { runner }
        orchestrator.run(config, initialTranscript = initialTranscript)

        assertTrue(compactionTriggered, "Dynamic token-based compaction must trigger at 10,000 tokens even with only 4 turns")
    }

    @Test
    fun test_structured_blackboard_replaces_older_history() = runTest {
        val seenContexts = mutableListOf<List<DebateMessage>>()
        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                seenContexts += transcript
                if (modelOverride != null || agent.systemPrompt.contains("Shared Memory Blackboard")) {
                    return AgentReply("[SHARED MEMORY BLACKBOARD — ROUND 2]\n• TOPIC ESSENCE: Architecture")
                }
                return AgentReply("Reply by ${agent.label()}", tokensIn = 100, tokensOut = 3000)
            }
        }

        val initialTranscript = (1..6).map { i ->
            DebateMessage(
                seatId = if (i % 2 == 1) "seat_claude" else "seat_gemini",
                provider = if (i % 2 == 1) Provider.ANTHROPIC else Provider.GEMINI,
                authorDisplayName = "Speaker $i",
                round = (i - 1) / 2 + 1,
                content = "Round ${(i - 1) / 2 + 1} argument",
                tokensOut = 3000
            )
        }

        val config = DebateConfig(
            topic = "Architecture",
            primary = claude(),
            secondary = gemini(),
            maxRounds = 4,
            costEfficiency = CostEfficiencyConfig(
                enabled = true,
                triggerTokenThreshold = 5_000,
                keepRecentVerbatimTurns = 2,
                strategy = CompactionStrategy.HYBRID_BLACKBOARD_RECENT
            )
        )

        val orchestrator = DebateOrchestrator { runner }
        orchestrator.run(config, initialTranscript = initialTranscript)

        // Find turns after compaction occurred
        val blackboardContext = seenContexts.find { ctx ->
            ctx.any { it.content.contains("[SHARED MEMORY BLACKBOARD") }
        }
        assertNotNull(blackboardContext, "ContextView should contain [SHARED MEMORY BLACKBOARD]")
        // Assert that the context view dropped older history (not all 6 original turns present)
        assertTrue(blackboardContext.size <= 4, "Context size with blackboard must be small (blackboard + recent 2)")
    }

    @Test
    fun test_budget_cap_produces_completed_conclusion_not_error() = runTest {
        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                return AgentReply("Turn content by ${agent.label()}", tokensIn = 1000, tokensOut = 50_000)
            }
        }

        val config = DebateConfig(
            topic = "Cost Budget Test",
            primary = claude(),
            secondary = gemini(),
            maxRounds = 5,
            costEfficiency = CostEfficiencyConfig(
                enabled = true,
                runTokenBudget = 80_000 // Reached in Round 1 Turn 2
            )
        )

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        assertNull(result.error, "Budget ceiling must not produce a hard crash error")
        assertEquals("BUDGET_CEILING_REACHED", result.earlyExitReason)
        assertNotNull(result.conclusion, "Moderator conclusion must be produced upon reaching budget cap")
    }
}
