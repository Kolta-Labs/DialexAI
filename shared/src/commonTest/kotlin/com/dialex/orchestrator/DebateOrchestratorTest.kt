package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Provider
import com.dialex.model.RoundMode
import com.dialex.model.brandName
import com.dialex.model.label
import com.dialex.runner.AgentReply
import com.dialex.runner.AgentRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fake runner: echoes provider + transcript length, no network/process involved. */
private class FakeRunner : AgentRunner {
    override suspend fun respond(
        agent: Agent,
        topic: String,
        commonContext: String,
        commonInstructions: String,
        transcript: List<DebateMessage>,
        modelOverride: String?,
    ) = AgentReply("${agent.provider}-turn-${transcript.size}")
}

/** Fails from the Nth call onward (1-indexed) — everything before that behaves like
 * FakeRunner. Persistent rather than one-shot so it stays a real failure even through the
 * orchestrator's one retry. */
private class FailingRunner(private val failOnCall: Int, private val failureMessage: String) : AgentRunner {
    private var calls = 0
    override suspend fun respond(
        agent: Agent,
        topic: String,
        commonContext: String,
        commonInstructions: String,
        transcript: List<DebateMessage>,
        modelOverride: String?,
    ): AgentReply {
        calls++
        if (calls >= failOnCall) error(failureMessage)
        return AgentReply("${agent.provider}-turn-${transcript.size}")
    }
}

private fun claude() = Agent(Provider.ANTHROPIC, "claude-x")
private fun gemini() = Agent(Provider.GEMINI, "gemini-x")

class DebateOrchestratorTest {

    @Test
    fun claude_speaks_first_every_round_then_gives_the_final_decision() = runTest {
        val fake = FakeRunner()
        val config = DebateConfig(topic = "t", primary = claude(), secondary = gemini(), maxRounds = 2)
        val orchestrator = DebateOrchestrator { fake }

        val result = orchestrator.run(config)

        // 2 agents * 2 rounds = 4 turns in transcript, Claude's conclusion call not counted.
        assertEquals(4, result.transcript.size)
        assertEquals(listOf(Provider.ANTHROPIC, Provider.GEMINI, Provider.ANTHROPIC, Provider.GEMINI), result.transcript.map { it.agentId })
        assertEquals(listOf(1, 1, 2, 2), result.transcript.map { it.round })
        assertEquals("ANTHROPIC-turn-4", result.conclusion)
    }

    @Test
    fun unlimited_mode_pauses_mid_debate_and_resume_continues_from_the_same_point() = runTest {
        val fake = FakeRunner()
        val config = DebateConfig(topic = "t", primary = claude(), secondary = gemini(), roundMode = RoundMode.UNLIMITED)
        val orchestrator = DebateOrchestrator { fake }

        var turnsSoFar = 0
        val paused = orchestrator.run(config, isStopped = { turnsSoFar >= 4 }) { turnsSoFar++ }

        // Paused right after 4 turns (2 full rounds) — nothing extra appended, resumable.
        assertEquals(4, paused.transcript.size)
        assertEquals(listOf(Provider.ANTHROPIC, Provider.GEMINI, Provider.ANTHROPIC, Provider.GEMINI), paused.transcript.map { it.agentId })
        assertTrue(paused.paused)
        assertNull(paused.error)
        assertNull(paused.conclusion)

        // Resuming picks up exactly where it left off — round 3 starts fresh.
        val resumed = orchestrator.run(config, initialTranscript = paused.transcript, isStopped = { turnsSoFar >= 6 }) { turnsSoFar++ }
        assertEquals(6, resumed.transcript.size)
        assertEquals(listOf(1, 1, 2, 2, 3, 3), resumed.transcript.map { it.round })
        assertTrue(resumed.paused)
    }

    @Test
    fun a_failed_turn_ends_the_debate_immediately_with_an_error_message() = runTest {
        val failing = FailingRunner(failOnCall = 3, failureMessage = "CLI exploded")
        val config = DebateConfig(topic = "t", primary = claude(), secondary = gemini(), maxRounds = 5)
        val orchestrator = DebateOrchestrator { failing }

        val result = orchestrator.run(config)

        // Claude(1) succeeds, Gemini(1) succeeds, Claude(2) fails — debate stops right
        // there, no remaining rounds, no conclusion.
        assertEquals(3, result.transcript.size)
        assertEquals("CLI exploded", result.error)
        assertTrue(result.transcript.last().isError)
        assertNull(result.conclusion)
    }

    @Test
    fun unanimous_agreement_ends_a_fixed_debate_before_maxRounds() = runTest {
        // Everyone agrees starting round 2 — round 1 is normal disagreement.
        val agreeing = object : AgentRunner {
            override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                val currentRound = transcript.size / 2 + 1
                return AgentReply(if (currentRound >= 2) "AGREED: makes sense" else "${agent.provider}-disagrees")
            }
        }
        val config = DebateConfig(topic = "t", primary = claude(), secondary = gemini(), maxRounds = 5)
        val orchestrator = DebateOrchestrator { agreeing }

        val result = orchestrator.run(config)

        // Round 1 (2 turns) + round 2 (2 turns, both AGREED) = 4 turns, then conclusion —
        // stops well short of maxRounds=5.
        val agentTurns = result.transcript.filter { !it.isSystem }
        assertEquals(4, agentTurns.size)
        assertEquals(listOf(1, 1, 2, 2), agentTurns.map { it.round })
        assertTrue(agentTurns.takeLast(2).all { it.content.startsWith("AGREED", ignoreCase = true) })
        assertEquals("AGREED: makes sense", result.conclusion)
        assertTrue(result.isConsensusReached)
        assertEquals("CONSENSUS", result.earlyExitReason)
    }

    @Test
    fun a_hard_stop_cancellation_propagates_instead_of_being_recorded_as_a_turn_error() = runTest {
        // Simulates CliAgentRunner destroying the process mid-turn on hard stop: the
        // coroutine gets cancelled while a turn is in flight.
        val cancelling = object : AgentRunner {
            var calls = 0
            override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                calls++
                if (calls == 2) throw CancellationException("hard stop")
                return AgentReply("${agent.provider}-turn-${transcript.size}")
            }
        }
        val config = DebateConfig(topic = "t", primary = claude(), secondary = gemini(), maxRounds = 5)
        val orchestrator = DebateOrchestrator { cancelling }

        // Must actually throw out of run() — a caught-and-swallowed cancellation would
        // instead return a DebateResult with an error, which is exactly the bug this guards.
        assertFailsWith<CancellationException> { orchestrator.run(config) }
    }

    @Test
    fun long_debates_get_compacted_before_being_sent_to_the_model_but_not_in_persisted_state() = runTest {
        val recording = object : AgentRunner {
            val seenContextSizes = mutableListOf<Int>()
            override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                seenContextSizes += transcript.size
                return AgentReply("${agent.provider}-turn-${transcript.size}")
            }
        }
        // 22 prior turns (past the 20-message compaction threshold), Claude/Gemini alternating.
        val priorTranscript = (1..22).map { i ->
            val provider = if (i % 2 == 1) Provider.ANTHROPIC else Provider.GEMINI
            DebateMessage(provider, round = (i - 1) / 2 + 1, content = "turn $i")
        }
        val config = DebateConfig(topic = "t", primary = claude(), secondary = gemini(), maxRounds = 13)
        val orchestrator = DebateOrchestrator { recording }

        val result = orchestrator.run(config, initialTranscript = priorTranscript)

        // Persisted transcript is untouched by compaction — full history, nothing dropped.
        assertEquals(26, result.transcript.size) // 22 prior + 4 new turns (round 12 already done, round 13 remains)
        assertEquals("turn 1", result.transcript.first().content)

        // What was actually SENT to the model is far smaller than the real history at every
        // call — the summary call sees only the older chunk (14), and every turn after that
        // sees the 1 recap message + the recent tail, never the full growing transcript.
        assertTrue(recording.seenContextSizes.first() == 14) // summarizing turns 1..14
        assertTrue(recording.seenContextSizes.drop(1).all { it < 14 }) // recap(1) + recent tail, always small
    }

    @Test
    fun compaction_call_uses_the_configured_model_override_but_normal_turns_dont() = runTest {
        val recording = object : AgentRunner {
            val seenOverrides = mutableListOf<String?>()
            override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                seenOverrides += modelOverride
                return AgentReply("${agent.provider}-turn-${transcript.size}")
            }
        }
        val priorTranscript = (1..22).map { i ->
            val provider = if (i % 2 == 1) Provider.ANTHROPIC else Provider.GEMINI
            DebateMessage(provider, round = (i - 1) / 2 + 1, content = "turn $i")
        }
        val config = DebateConfig(topic = "t", primary = claude(), secondary = gemini(), maxRounds = 13)
        val orchestrator = DebateOrchestrator { recording }

        orchestrator.run(config, initialTranscript = priorTranscript, compactionModel = "claude-haiku-4-5-20251001")

        // Only the very first call (the compaction summary) carries the override — every
        // normal debate turn after it leaves the agent's own model untouched.
        assertEquals("claude-haiku-4-5-20251001", recording.seenOverrides.first())
        assertTrue(recording.seenOverrides.drop(1).all { it == null })
    }

    @Test
    fun three_way_debate_speaks_in_claude_gemini_chatgpt_order() = runTest {
        val fake = FakeRunner()
        val chatgpt = Agent(Provider.OPENAI, "gpt-x")
        val config = DebateConfig(topic = "t", primary = claude(), secondary = gemini(), tertiary = chatgpt, maxRounds = 1)
        val orchestrator = DebateOrchestrator { fake }

        val result = orchestrator.run(config)

        assertEquals(3, result.transcript.size)
        assertEquals(listOf(Provider.ANTHROPIC, Provider.GEMINI, Provider.OPENAI), result.transcript.map { it.agentId })
    }

    @Test
    fun five_way_debate_speaks_in_seat_order_every_round() = runTest {
        val fake = FakeRunner()
        val config = DebateConfig(
            topic = "t",
            primary = claude(),
            secondary = gemini(),
            tertiary = Agent(Provider.OPENAI, "gpt-x"),
            quaternary = Agent(Provider.GROK, "grok-x"),
            quinary = Agent(Provider.DEEPSEEK, "deepseek-x"),
            maxRounds = 2,
        )
        val orchestrator = DebateOrchestrator { fake }

        val result = orchestrator.run(config)

        val order = listOf(Provider.ANTHROPIC, Provider.GEMINI, Provider.OPENAI, Provider.GROK, Provider.DEEPSEEK)
        assertEquals(10, result.transcript.size)
        assertEquals(order + order, result.transcript.map { it.agentId })
    }

    @Test
    fun test_unanimous_agreement_halts_immediately_on_multi_provider_agents() = runTest {
        // 3 agents where 2 agents share Provider.CUSTOM (different seat IDs)
        val agent1 = Agent(Provider.CUSTOM, "custom-1", id = "seat_custom_1")
        val agent2 = Agent(Provider.CUSTOM, "custom-2", id = "seat_custom_2")
        val agent3 = Agent(Provider.ANTHROPIC, "claude-3", id = "seat_claude")

        val runner = object : AgentRunner {
            override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                val currentRound = transcript.filter { !it.isSystem && !it.isUserComment }.size / 3 + 1
                return AgentReply(if (currentRound >= 2) "## Agreed: We concur" else "${agent.id}-disagrees")
            }
        }

        val config = DebateConfig(
            topic = "test",
            primary = agent1,
            secondary = agent2,
            tertiary = agent3,
            maxRounds = 5,
            consensus = com.dialex.model.ConsensusConfig(
                mode = com.dialex.model.ConsensusMode.UNANIMOUS,
                minRoundsBeforeExit = 2
            )
        )
        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        val agentTurns = result.transcript.filter { !it.isSystem }
        assertEquals(6, agentTurns.size) // 3 turns Round 1 + 3 turns Round 2
        assertTrue(result.isConsensusReached)
        assertEquals("CONSENSUS", result.earlyExitReason)
    }

    @Test
    fun test_mid_round_early_exit_stops_remaining_agents() = runTest {
        // 3 agents: In Round 2, Agent 1 and Agent 2 agree under SUPERMAJORITY (2 of 3 >= 66%).
        // With allowMidRoundTermination = true, Agent 3 should NOT be asked to speak in Round 2.
        val a1 = Agent(Provider.ANTHROPIC, "m1", id = "seat_1")
        val a2 = Agent(Provider.GEMINI, "m2", id = "seat_2")
        val a3 = Agent(Provider.OPENAI, "m3", id = "seat_3")

        var a3SpokeInRound2 = false
        val runner = object : AgentRunner {
            override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                val round = transcript.filter { !it.isSystem && !it.isUserComment }.size / 3 + 1
                if (agent.id == "seat_3" && round == 2) {
                    a3SpokeInRound2 = true
                }
                return AgentReply(if (round >= 2) "AGREED: Concur" else "${agent.id}-argument")
            }
        }

        val config = DebateConfig(
            topic = "test",
            primary = a1,
            secondary = a2,
            tertiary = a3,
            maxRounds = 5,
            consensus = com.dialex.model.ConsensusConfig(
                mode = com.dialex.model.ConsensusMode.SUPERMAJORITY,
                consensusThreshold = 0.66,
                minRoundsBeforeExit = 2,
                allowMidRoundTermination = true
            )
        )
        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        assertTrue(result.isConsensusReached)
        // Agent 3 did not speak in Round 2 because consensus was achieved after Agent 2 spoke
        assertFalse(a3SpokeInRound2)
        val agentTurns = result.transcript.filter { !it.isSystem }
        assertEquals(5, agentTurns.size) // 3 from Round 1 + 2 from Round 2
    }

    @Test
    fun test_min_rounds_guard_prevents_premature_exit() = runTest {
        // Agents agree in Round 1, but minRoundsBeforeExit = 2
        val runner = object : AgentRunner {
            override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                return AgentReply("AGREED: from the beginning")
            }
        }

        val config = DebateConfig(
            topic = "test",
            primary = claude(),
            secondary = gemini(),
            maxRounds = 5,
            consensus = com.dialex.model.ConsensusConfig(
                mode = com.dialex.model.ConsensusMode.UNANIMOUS,
                minRoundsBeforeExit = 2,
                allowMidRoundTermination = false
            )
        )
        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        val agentTurns = result.transcript.filter { !it.isSystem }
        assertEquals(4, agentTurns.size) // 2 turns Round 1 + 2 turns Round 2
        assertTrue(result.isConsensusReached)
        assertEquals(listOf(1, 1, 2, 2), agentTurns.map { it.round })
    }

    @Test
    fun test_consensus_modes_supermajority_and_simple_majority() = runTest {
        val a1 = Agent(Provider.ANTHROPIC, "m1", id = "seat_1")
        val a2 = Agent(Provider.GEMINI, "m2", id = "seat_2")
        val a3 = Agent(Provider.OPENAI, "m3", id = "seat_3")

        // Only a1 and a2 agree; a3 persistently disagrees
        val runner = object : AgentRunner {
            override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                val round = transcript.filter { !it.isSystem && !it.isUserComment }.size / 3 + 1
                return if (round >= 2 && agent.id != "seat_3") {
                    AgentReply("AGREED: align with majority")
                } else {
                    AgentReply("${agent.id}-counterargument")
                }
            }
        }

        val config = DebateConfig(
            topic = "test",
            primary = a1,
            secondary = a2,
            tertiary = a3,
            maxRounds = 5,
            consensus = com.dialex.model.ConsensusConfig(
                mode = com.dialex.model.ConsensusMode.SIMPLE_MAJORITY,
                minRoundsBeforeExit = 2
            )
        )
        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        assertTrue(result.isConsensusReached)
        assertEquals("CONSENSUS", result.earlyExitReason)
    }

    @Test
    fun test_end_to_end_consensus_simulation() = runTest {
        // Can AI replace product managers benchmark simulation
        val runner = object : AgentRunner {
            override suspend fun respond(agent: Agent, topic: String, commonContext: String, commonInstructions: String, transcript: List<DebateMessage>, modelOverride: String?): AgentReply {
                val currentRound = transcript.filter { !it.isSystem && !it.isUserComment }.size / 2 + 1
                return if (currentRound >= 3) {
                    AgentReply("AGREED: Core consensus reached: AI augments product specification and data analysis but cannot replace cross-functional alignment and strategic intuition.")
                } else {
                    AgentReply("${agent.provider.brandName()} perspective on $topic in round $currentRound")
                }
            }
        }

        val config = DebateConfig(
            topic = "Can AI replace product managers",
            primary = claude(),
            secondary = gemini(),
            maxRounds = 10,
            consensus = com.dialex.model.ConsensusConfig(
                mode = com.dialex.model.ConsensusMode.UNANIMOUS,
                minRoundsBeforeExit = 2
            )
        )
        val orchestrator = DebateOrchestrator { runner }
        val result = orchestrator.run(config)

        val agentTurns = result.transcript.filter { !it.isSystem }
        assertEquals(6, agentTurns.size) // Round 1 (2), Round 2 (2), Round 3 (2)
        assertTrue(result.isConsensusReached)
        assertEquals("CONSENSUS", result.earlyExitReason)
        assertTrue(result.conclusion != null && result.conclusion!!.isNotBlank())
        assertNull(result.error)
    }
}
