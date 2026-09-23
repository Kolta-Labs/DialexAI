package com.dialex.orchestrator

import com.dialex.model.AgentStance
import com.dialex.model.ConsensusConfig
import com.dialex.model.ConsensusEvaluationResult
import com.dialex.model.ConsensusMode
import com.dialex.model.ConsensusStrategy
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage

object ConsensusDetector {

    /**
     * Layer 1: Normalized Regex & Multi-Prefix Matcher.
     * Matches common affirmative variations, ignoring leading markdown headers (#),
     * bold/italics (*, _), blockquotes (>), brackets, and whitespace.
     */
    val CONSENSUS_PREFIX_REGEX = Regex(
        pattern = """^(?:>\s*)*(?:#{1,6}\s*)?(?:\[\s*)?(?:\*{1,2}|_{1,2})?\s*(?:AGREED|CONCUR|CONSENSUS REACHED|UNANIMOUS AGREEMENT|I AGREE)\b\s*(?:\])?\s*[:—\-]?(?:\*{1,2}|_{1,2})?""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
    )

    private val NEGATION_REGEX = Regex(
        """(?<!no\s)(?<!no\sfurther\s)(?<!without\s)\b(?:not\s+agree|do\s+not\s+agree|don't\s+agree|cannot\s+agree|can't\s+agree|disagree|disagrees|not\s+concede|do\s+not\s+concede|cannot\s+concede|refuse\s+to\s+concede|still\s+contest)\b""",
        RegexOption.IGNORE_CASE
    )

    private val CONCESSION_PATTERNS = listOf(
        "i concede to",
        "concede to the position",
        "concede to the council",
        "nothing left to contest",
        "nothing left to audit",
        "i align with the council",
        "align with the other participants",
        "align with the panel",
        "no further substantive disagreement",
        "no further disagreement",
        "no remaining objections",
        "nothing new to add and agree",
        "fully concur with",
        "i fully concur",
        "i fully concede"
    )

    /**
     * Layer 1 match check.
     */
    fun matchesPrefix(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        val firstLine = trimmed.lines().firstOrNull { it.isNotBlank() } ?: return false
        val match = CONSENSUS_PREFIX_REGEX.find(firstLine) ?: return false
        // Verify no negation follows in first line
        val remainder = firstLine.substring(match.range.last + 1).trim()
        return !NEGATION_REGEX.containsMatchIn(remainder)
    }

    /**
     * Layer 2: Heuristic structural keyword and concession parser.
     * Inspects the opening (first 250 chars) and closing (last 250 chars) of a turn.
     */
    fun matchesHeuristics(content: String): Boolean {
        val clean = content.trim()
        if (clean.isEmpty()) return false

        val sample = buildString {
            append(clean.take(250))
            append(" ")
            if (clean.length > 250) {
                append(clean.takeLast(250))
            }
        }.lowercase()

        // Fast reject if strong negation phrases present
        if (NEGATION_REGEX.containsMatchIn(sample)) {
            return false
        }

        return CONCESSION_PATTERNS.any { sample.contains(it) }
    }

    /**
     * Determine an agent's stance from a single message content according to strategy.
     */
    fun detectStance(content: String, strategy: ConsensusStrategy): AgentStance {
        if (strategy == ConsensusStrategy.PREFIX_AND_PATTERN) {
            return if (matchesPrefix(content)) AgentStance.AGREED else AgentStance.DISAGREE
        }

        // HEURISTIC_HYBRID or MODEL_CLASSIFIED fallback
        if (matchesPrefix(content)) {
            return AgentStance.AGREED
        }

        if (matchesHeuristics(content)) {
            return AgentStance.CONCEDED
        }

        return AgentStance.DISAGREE
    }

    /**
     * Quick boolean check whether a single turn signifies consensus agreement.
     */
    fun isTurnInConsensus(content: String, strategy: ConsensusStrategy): Boolean {
        val stance = detectStance(content, strategy)
        return stance == AgentStance.AGREED || stance == AgentStance.CONCEDED
    }

    /**
     * Evaluates current debate consensus status across all active seats.
     * Keyed strictly on immutable seat identity (seatId or agent.id).
     */
    fun evaluateConsensus(
        currentRound: Int,
        transcript: List<DebateMessage>,
        config: DebateConfig
    ): ConsensusEvaluationResult {
        val consensusConfig = config.consensus
        if (consensusConfig.mode == ConsensusMode.DISABLED) {
            return ConsensusEvaluationResult.NotReady(reason = "Consensus detection disabled.")
        }
        if (currentRound < consensusConfig.minRoundsBeforeExit) {
            return ConsensusEvaluationResult.NotReady(
                reason = "Minimum rounds (${consensusConfig.minRoundsBeforeExit}) not reached."
            )
        }

        val activeSeats = config.agents.map { it.id }
        if (activeSeats.isEmpty()) {
            return ConsensusEvaluationResult.NotReady(reason = "No active agents.")
        }

        val lastUserCommentIndex = transcript.indexOfLast { it.isUserComment }

        // Find the latest valid turn for each seat (after the latest user comment, if any)
        val latestTurnsPerSeat = activeSeats.associateWith { seatId ->
            val agent = config.agents.find { it.id == seatId }
            transcript.findLast { msg ->
                (transcript.indexOf(msg) > lastUserCommentIndex) &&
                    (msg.seatId == seatId || (msg.seatId.isBlank() && agent != null && msg.agentId == agent.provider)) &&
                    !msg.isError && !msg.isUserComment && !msg.isSystem
            }
        }

        // If any agent hasn't spoken at all yet, debate is still ongoing
        if (latestTurnsPerSeat.values.any { it == null }) {
            val validTurns = latestTurnsPerSeat.values.filterNotNull()
            val agreedCount = validTurns.count { isTurnInConsensus(it.content, consensusConfig.strategy) }
            return ConsensusEvaluationResult.Ongoing(agreedCount = agreedCount, totalCount = activeSeats.size)
        }

        val agreedSeats = latestTurnsPerSeat.filter { (_, msg) ->
            msg != null && isTurnInConsensus(msg.content, consensusConfig.strategy)
        }.keys

        val agreedRatio = agreedSeats.size.toDouble() / activeSeats.size.toDouble()

        val mode = if (consensusConfig.mode == ConsensusMode.UNANIMOUS && config.consensusTolerance in 0.5..0.999) {
            ConsensusMode.SUPERMAJORITY
        } else {
            consensusConfig.mode
        }

        val isConsensus = when (mode) {
            ConsensusMode.UNANIMOUS -> agreedSeats.size == activeSeats.size
            ConsensusMode.SUPERMAJORITY -> {
                val threshold = if (consensusConfig.consensusThreshold in 0.5..0.999) {
                    consensusConfig.consensusThreshold
                } else if (config.consensusTolerance in 0.5..0.999) {
                    config.consensusTolerance
                } else {
                    0.66
                }
                agreedRatio >= threshold
            }
            ConsensusMode.SIMPLE_MAJORITY -> agreedRatio > 0.50
            ConsensusMode.DISABLED -> false
        }

        return if (isConsensus) {
            ConsensusEvaluationResult.Achieved(
                agreedSeats = agreedSeats.toList(),
                round = currentRound,
                ratio = agreedRatio
            )
        } else {
            ConsensusEvaluationResult.Ongoing(agreedCount = agreedSeats.size, totalCount = activeSeats.size)
        }
    }
}
