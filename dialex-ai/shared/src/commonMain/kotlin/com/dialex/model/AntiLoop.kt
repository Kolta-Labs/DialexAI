package com.dialex.model

import kotlinx.serialization.Serializable

@Serializable
enum class LoopAction {
    /** Prompt the agent with an anti-repetition directive and retry with higher temperature. */
    RETRY_WITH_DIRECTIVE,
    /** Convert the turn into a short, structured concession if arguments are exhausted. */
    CONVERT_TO_CONCESSION,
    /** Trigger the debate moderator to inject a new question or force topic advancement. */
    MODERATOR_INTERVENE,
    /** Drop the turn and mark round as stalled. */
    HALT_OR_ADVANCE
}

@Serializable
data class AntiLoopConfig(
    val enabled: Boolean = true,
    /** Maximum allowable token 3-gram/4-gram Jaccard similarity against an agent's prior turn (0.0 to 1.0). */
    val maxSimilarityThreshold: Double = 0.65,
    /** Maximum contiguous identical character sequence allowed before flagging verbatim copy (e.g., 180 chars). */
    val maxContiguousDuplicateChars: Int = 180,
    /** Maximum retry attempts per turn before triggering fallback action. */
    val maxRetries: Int = 1,
    /** Temperature boost applied to sampling when retrying a looped turn (e.g., +0.25). */
    val retryTemperatureBump: Double = 0.25,
    /** Fallback action if retry still fails the deduplication guard. */
    val fallbackAction: LoopAction = LoopAction.CONVERT_TO_CONCESSION
)

sealed interface LoopInspectionResult {
    data object Clean : LoopInspectionResult

    data class LoopDetected(
        val similarityScore: Double,
        val longestContiguousMatch: String,
        val duplicatedTurnRound: Int = 0,
        val reason: String
    ) : LoopInspectionResult
}
