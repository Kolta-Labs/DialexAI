package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.DepthConfig
import com.dialex.model.DepthMode
import com.dialex.model.Provider
import com.dialex.runner.AgentReply
import com.dialex.runner.AgentRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CognitiveDepthTest {

    private fun testAgent(id: String, ponytail: Boolean = false) = Agent(
        id = id,
        provider = Provider.ANTHROPIC,
        model = "claude-sonnet-5",
        ponytail = ponytail
    )

    @Test
    fun test_casual_mode_enforces_plain_english_and_bans_math() = runTest {
        val capturedPrompts = mutableListOf<String>()
        val capturedMaxTokens = mutableListOf<Int?>()

        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                capturedPrompts.add(agent.systemPrompt)
                capturedMaxTokens.add(agent.maxTokens)
                return AgentReply("Here is a simple take on phones. One has a great camera, but the other has better battery life.")
            }
        }

        val config = DebateConfig(
            topic = "iPhone vs Pixel",
            primary = testAgent("seat_1"),
            maxRounds = 1,
            depth = DepthConfig.preset(DepthMode.CASUAL)
        )

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        val turnPrompt = capturedPrompts.first()
        assertTrue(turnPrompt.contains("[COGNITIVE LOAD DIRECTIVE: CASUAL / ACCESSIBLE]"))
        assertTrue(turnPrompt.contains("NO mathematical equations"))
        assertTrue(turnPrompt.contains("Maximum 150 words per turn"))
        assertEquals(225, capturedMaxTokens.first()) // 150 * 1.5

        val reply = result.transcript.first().content
        assertFalse(SemanticEvaluator.hasMathOrLatex(reply))
        val ease = SemanticEvaluator.fleschKincaidReadingEase(reply)
        assertTrue(ease >= 60.0, "Casual reply reading ease should be >= 60, was $ease")
    }

    @Test
    fun test_executive_mode_enforces_structured_bullets_and_word_cap() = runTest {
        val capturedPrompts = mutableListOf<String>()
        val capturedMaxTokens = mutableListOf<Int?>()

        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                capturedPrompts.add(agent.systemPrompt)
                capturedMaxTokens.add(agent.maxTokens)
                return AgentReply(
                    "**AI will augment PRD synthesis but cannot absorb fiduciary release authority.**\n" +
                        "• **ROI Evidence:** Reduces spec drafting cycles by 60% across pilot teams.\n" +
                        "• **Operational Risk:** Hallucinated edge cases increase QA regression overhead."
                )
            }
        }

        val config = DebateConfig(
            topic = "Can AI replace product managers",
            primary = testAgent("seat_1"),
            maxRounds = 1,
            depth = DepthConfig.preset(DepthMode.EXECUTIVE)
        )

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        val turnPrompt = capturedPrompts.first()
        assertTrue(turnPrompt.contains("[COGNITIVE LOAD DIRECTIVE: EXECUTIVE / STRATEGIC]"))
        assertTrue(turnPrompt.contains("ROI-focused"))
        assertTrue(turnPrompt.contains("Maximum 300 words per turn"))
        assertEquals(525, capturedMaxTokens.first()) // 350 * 1.5

        val turnContent = result.transcript.first().content
        assertTrue(turnContent.contains("• **"))
        assertTrue(SemanticEvaluator.countWords(turnContent) <= 350)
    }

    @Test
    fun test_academic_mode_permits_latex_and_high_density() = runTest {
        val capturedPrompts = mutableListOf<String>()
        val capturedMaxTokens = mutableListOf<Int?>()

        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                capturedPrompts.add(agent.systemPrompt)
                capturedMaxTokens.add(agent.maxTokens)
                return AgentReply(
                    "Formally, consider the stochastic differential equation: " +
                        "\$dX_t = \\mu(X_t)dt + \\sigma(X_t)dW_t\$. The phase transition boundary is invariant."
                )
            }
        }

        val config = DebateConfig(
            topic = "Foundational limits of stochastic decision processes",
            primary = testAgent("seat_1"),
            maxRounds = 1,
            depth = DepthConfig.preset(DepthMode.ACADEMIC)
        )

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        val turnPrompt = capturedPrompts.first()
        assertTrue(turnPrompt.contains("[COGNITIVE LOAD DIRECTIVE: ACADEMIC / FIRST-PRINCIPLES]"))
        assertTrue(turnPrompt.contains("LaTeX"))
        assertTrue(turnPrompt.contains("Maximum 750 words per turn"))
        assertEquals(1125, capturedMaxTokens.first()) // 750 * 1.5

        val turnContent = result.transcript.first().content
        assertTrue(SemanticEvaluator.hasMathOrLatex(turnContent))
    }

    @Test
    fun test_depth_modes_adherence() = runTest {
        val capturedPrompts = mutableListOf<String>()

        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                capturedPrompts.add(agent.systemPrompt)
                return AgentReply("• AI draft PRD fast.\n• Human take legal blame.\n• 40% cost drop.")
            }
        }

        val testAgent = testAgent("seat_exec", ponytail = true)

        val config = DebateConfig(
            topic = "Can AI replace product managers",
            primary = testAgent,
            maxRounds = 1,
            depth = DepthConfig.preset(DepthMode.EXECUTIVE)
        )

        val orchestrator = DebateOrchestrator { runner }
        orchestrator.run(config)

        val turnPrompt = capturedPrompts.first()
        assertTrue(turnPrompt.contains("[COGNITIVE LOAD DIRECTIVE: EXECUTIVE / STRATEGIC]"))
    }
}
