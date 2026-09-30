package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.ModerationConfig
import com.dialex.model.ModerationStyle
import com.dialex.model.ModeratorPersona
import com.dialex.model.Provider
import com.dialex.runner.AgentReply
import com.dialex.runner.AgentRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DynamicModeratorTest {

    private fun testAgent(id: String) = Agent(
        id = id,
        provider = Provider.ANTHROPIC,
        model = "claude-sonnet-5",
        displayName = "Participant $id"
    )

    @Test
    fun test_semantic_drift_triggers_moderator_steerage() = runTest {
        val offTopicPhysicsTurn = "We must evaluate the fluid viscosity and rheometer calibration drag coefficients under non-Newtonian flow."
        val topicalPMTurn = "Product managers bridge technical roadmaps with enterprise customer feedback to prioritize engineering headcount."

        val similarityOffTopic = SemanticEvaluator.similarity(offTopicPhysicsTurn, "Can AI replace product managers")
        val similarityOnTopic = SemanticEvaluator.similarity(topicalPMTurn, "Can AI replace product managers")

        assertTrue(similarityOffTopic < 0.45, "Off-topic turn should have similarity < 0.45, was $similarityOffTopic")
        assertTrue(similarityOnTopic > 0.45, "On-topic turn should have similarity > 0.45, was $similarityOnTopic")

        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                return if (agent.systemPrompt.contains("[TRIGGER: TOPICAL DRIFT") || agent.systemPrompt.contains("Deliberation Chair.")) {
                    AgentReply("### 🏛️ Deliberation Chair — Intervention (Round 1)\n**Order in the council.** Stop debating rheometers. Return to product managers.")
                } else if (agent.id == "seat_1") {
                    AgentReply(offTopicPhysicsTurn)
                } else {
                    AgentReply(topicalPMTurn)
                }
            }
        }

        val config = DebateConfig(
            topic = "Can AI replace product managers",
            primary = testAgent("seat_1"),
            secondary = testAgent("seat_2"),
            maxRounds = 1,
            moderation = ModerationConfig(
                enabled = true,
                style = ModerationStyle.DYNAMIC_ACTIVE_STEERAGE,
                driftThreshold = 0.45,
                persona = ModeratorPersona.DELIBERATION_CHAIR
            )
        )

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        val interventions = result.transcript.filter { it.isModeratorIntervention }
        assertEquals(1, interventions.size)
        val intervention = interventions.first()
        assertEquals("Deliberation Chair", intervention.authorDisplayName)
        assertTrue(intervention.content.contains("Order in the council"))
    }

    @Test
    fun test_periodic_checkpoint_fires_on_modulo_rounds() = runTest {
        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                return if (agent.systemPrompt.contains("PERIODIC COUNCIL CHECKPOINT")) {
                    AgentReply("### 🏛️ Deliberation Chair — Interim Checkpoint (End of Round 2)\n• ESTABLISHED: Automation is feasible.\n• CONTESTED: Fiduciary risk.")
                } else {
                    AgentReply("Participant statement on ${topic}")
                }
            }
        }

        val config = DebateConfig(
            topic = "Can AI replace product managers",
            primary = testAgent("seat_1"),
            secondary = testAgent("seat_2"),
            maxRounds = 3,
            moderation = ModerationConfig(
                enabled = true,
                style = ModerationStyle.PERIODIC_CHECKPOINT,
                checkpointFrequencyRounds = 2,
                persona = ModeratorPersona.DELIBERATION_CHAIR
            )
        )

        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        val checkpoints = result.transcript.filter { it.isModeratorIntervention }
        assertEquals(1, checkpoints.size)
        val cp = checkpoints.first()
        assertEquals(2, cp.round)
        assertTrue(cp.content.contains("Interim Checkpoint"))
    }

    @Test
    fun test_moderator_directive_injected_into_next_agent_prompt() = runTest {
        var subsequentAgentReceivedDirective = false

        val runner = object : AgentRunner {
            override suspend fun respond(
                agent: Agent,
                topic: String,
                commonContext: String,
                commonInstructions: String,
                transcript: List<DebateMessage>,
                modelOverride: String?
            ): AgentReply {
                if (agent.systemPrompt.contains("[MANDATORY MODERATOR STEERAGE DIRECTIVE]")) {
                    subsequentAgentReceivedDirective = true
                }

                return if (agent.systemPrompt.contains("Deliberation Chair") || agent.systemPrompt.contains("TOPICAL DRIFT")) {
                    AgentReply("Focus strictly on release engineering accountability.")
                } else if (agent.id == "seat_1") {
                    AgentReply("Discussing pure fluid viscosity and boundary friction coefficients.")
                } else {
                    AgentReply("Addressing release accountability as mandated.")
                }
            }
        }

        val config = DebateConfig(
            topic = "Can AI replace product managers",
            primary = testAgent("seat_1"),
            secondary = testAgent("seat_2"),
            maxRounds = 1,
            moderation = ModerationConfig(
                enabled = true,
                style = ModerationStyle.DYNAMIC_ACTIVE_STEERAGE,
                driftThreshold = 0.45,
                enforceSteerageDirectives = true
            )
        )

        val orchestrator = DebateOrchestrator { runner }
        orchestrator.run(config)

        assertTrue(subsequentAgentReceivedDirective, "Next agent should receive mandatory steerage directive")
    }

    @Test
    fun test_moderator_personas_display_name() = runTest {
        val personas = listOf(
            ModeratorPersona.DELIBERATION_CHAIR to "Deliberation Chair",
            ModeratorPersona.EXECUTIVE_ARBITER to "Executive Arbiter",
            ModeratorPersona.SOCRATIC_PROBE to "Socratic Inquirer",
            ModeratorPersona.DEVILS_ADVOCATE_CHAIR to "Devil's Advocate Chair"
        )

        for ((persona, expectedLabel) in personas) {
            val runner = object : AgentRunner {
                override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                    return if (agent.systemPrompt.contains("TOPICAL DRIFT") || agent.systemPrompt.contains("Deliberation") || agent.systemPrompt.contains("Executive Arbiter") || agent.systemPrompt.contains("Socratic") || agent.systemPrompt.contains("Devil's Advocate")) {
                        AgentReply("Steerage")
                    } else {
                        AgentReply("viscosity drag SDEs")
                    }
                }
            }

            val config = DebateConfig(
                topic = "Enterprise architecture",
                primary = testAgent("seat_1"),
                maxRounds = 1,
                moderation = ModerationConfig(
                    enabled = true,
                    style = ModerationStyle.DYNAMIC_ACTIVE_STEERAGE,
                    driftThreshold = 0.45,
                    persona = persona
                )
            )

            val orchestrator = DebateOrchestrator { runner }
            val result = orchestrator.run(config)
            val intervention = result.transcript.find { it.isModeratorIntervention }
            assertNotNull(intervention)
            assertEquals(expectedLabel, intervention.authorDisplayName)
        }
    }
}
