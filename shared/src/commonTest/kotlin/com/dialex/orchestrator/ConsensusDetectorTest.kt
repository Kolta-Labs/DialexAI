package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.AgentStance
import com.dialex.model.ConsensusConfig
import com.dialex.model.ConsensusEvaluationResult
import com.dialex.model.ConsensusMode
import com.dialex.model.ConsensusStrategy
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsensusDetectorTest {

    @Test
    fun test_prefix_regex_handles_markdown_and_case_variations() {
        val validPrefixes = listOf(
            "AGREED:",
            "AGREED: Absolutely concur with previous points.",
            "## Agreed",
            "### AGREED: Makes complete sense.",
            "**AGREED:** I concur.",
            "**Consensus Reached:** Core objections addressed.",
            "i agree: moving forward with plan.",
            "> AGREED: nothing more to add.",
            "[AGREED] Fully aligned.",
            "CONCUR: Valid argument.",
            "UNANIMOUS AGREEMENT: All issues resolved."
        )

        for (text in validPrefixes) {
            assertTrue(
                ConsensusDetector.matchesPrefix(text),
                "Expected '$text' to match consensus prefix"
            )
            assertEquals(
                AgentStance.AGREED,
                ConsensusDetector.detectStance(text, ConsensusStrategy.PREFIX_AND_PATTERN)
            )
        }

        val invalidPrefixes = listOf(
            "I do not agree with the SME proposal.",
            "I cannot agree at this stage.",
            "disagree: this overlooks major latency pitfalls.",
            "Turn 1: Here is my thesis.",
            "AGREED that X is good, but I do not agree with Y.",
            "",
            "   "
        )

        for (text in invalidPrefixes) {
            assertFalse(
                ConsensusDetector.matchesPrefix(text),
                "Expected '$text' NOT to match consensus prefix"
            )
        }
    }

    @Test
    fun test_heuristic_concession_detection() {
        val concessionTexts = listOf(
            "After reviewing the trade-off matrix, I concede to the position of Agent 1.",
            "There is nothing left to contest in the design. The benchmarks speak for themselves.",
            "I align with the council on the proposed architecture and have no further substantive disagreement.",
            "I fully concur with the points raised by Gemini.",
            "We have evaluated the edge cases and there are no remaining objections on our side."
        )

        for (text in concessionTexts) {
            assertTrue(
                ConsensusDetector.matchesHeuristics(text),
                "Expected heuristic match for '$text'"
            )
            assertTrue(
                ConsensusDetector.isTurnInConsensus(text, ConsensusStrategy.HEURISTIC_HYBRID)
            )
        }

        val nonConcessions = listOf(
            "I do not concede to this approach; the risks are too great.",
            "There is still much to contest before we finalize.",
            "I cannot agree with the proposed latency budget."
        )

        for (text in nonConcessions) {
            assertFalse(
                ConsensusDetector.matchesHeuristics(text),
                "Expected no heuristic match for '$text'"
            )
        }
    }

    @Test
    fun test_evaluate_consensus_unanimous_mode() {
        val agent1 = Agent(Provider.ANTHROPIC, "m1", id = "seat_1")
        val agent2 = Agent(Provider.GEMINI, "m2", id = "seat_2")
        val config = DebateConfig(
            topic = "test",
            primary = agent1,
            secondary = agent2,
            consensus = ConsensusConfig(mode = ConsensusMode.UNANIMOUS, minRoundsBeforeExit = 2)
        )

        // Round 1: Both agree, but minRoundsBeforeExit is 2 -> NotReady
        val round1Transcript = listOf(
            DebateMessage(seatId = "seat_1", provider = Provider.ANTHROPIC, authorDisplayName = "A1", round = 1, content = "AGREED: Yes"),
            DebateMessage(seatId = "seat_2", provider = Provider.GEMINI, authorDisplayName = "A2", round = 1, content = "AGREED: Yes")
        )
        val r1Result = ConsensusDetector.evaluateConsensus(1, round1Transcript, config)
        assertTrue(r1Result is ConsensusEvaluationResult.NotReady)

        // Round 2: Only 1 agrees -> Ongoing
        val round2Partial = round1Transcript + listOf(
            DebateMessage(seatId = "seat_1", provider = Provider.ANTHROPIC, authorDisplayName = "A1", round = 2, content = "AGREED: Still agree"),
            DebateMessage(seatId = "seat_2", provider = Provider.GEMINI, authorDisplayName = "A2", round = 2, content = "Wait, I have a doubt")
        )
        val r2PartialResult = ConsensusDetector.evaluateConsensus(2, round2Partial, config)
        assertTrue(r2PartialResult is ConsensusEvaluationResult.Ongoing)
        assertEquals(1, r2PartialResult.agreedCount)

        // Round 2: Both agree -> Achieved
        val round2Unanimous = round1Transcript + listOf(
            DebateMessage(seatId = "seat_1", provider = Provider.ANTHROPIC, authorDisplayName = "A1", round = 2, content = "AGREED: Still agree"),
            DebateMessage(seatId = "seat_2", provider = Provider.GEMINI, authorDisplayName = "A2", round = 2, content = "AGREED: Solved")
        )
        val r2Result = ConsensusDetector.evaluateConsensus(2, round2Unanimous, config)
        assertTrue(r2Result is ConsensusEvaluationResult.Achieved)
        assertEquals(2, r2Result.agreedSeats.size)
        assertEquals(1.0, r2Result.ratio)
    }

    @Test
    fun test_evaluate_consensus_supermajority_mode() {
        val a1 = Agent(Provider.ANTHROPIC, "m1", id = "seat_1")
        val a2 = Agent(Provider.GEMINI, "m2", id = "seat_2")
        val a3 = Agent(Provider.OPENAI, "m3", id = "seat_3")
        val config = DebateConfig(
            topic = "test",
            primary = a1,
            secondary = a2,
            tertiary = a3,
            consensus = ConsensusConfig(
                mode = ConsensusMode.SUPERMAJORITY,
                consensusThreshold = 0.66,
                minRoundsBeforeExit = 1
            )
        )

        // 2 of 3 agree (66.6% >= 66%) -> Achieved
        val transcript = listOf(
            DebateMessage(seatId = "seat_1", provider = Provider.ANTHROPIC, authorDisplayName = "A1", round = 1, content = "AGREED: Yes"),
            DebateMessage(seatId = "seat_2", provider = Provider.GEMINI, authorDisplayName = "A2", round = 1, content = "AGREED: Yes"),
            DebateMessage(seatId = "seat_3", provider = Provider.OPENAI, authorDisplayName = "A3", round = 1, content = "Disagree strongly")
        )
        val result = ConsensusDetector.evaluateConsensus(1, transcript, config)
        assertTrue(result is ConsensusEvaluationResult.Achieved)
        assertEquals(2, result.agreedSeats.size)
    }
}
