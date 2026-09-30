package com.dialex.domain.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.serialization.Serializable

@Serializable
data class CredenceHypothesis(
    val id: String,
    val index: Int = 1,
    val label: String,
    val description: String = "",
    val colorHex: String = "#10B981"
)

@Serializable
data class PersonaCredencePoint(
    val personaId: String,
    val personaName: String,
    val hypothesisCredence: Map<String, Double> = emptyMap(),
    val certaintyScore: Double = 0.5,
    val coreRationale: String = ""
) {
    val immutableCredence: ImmutableMap<String, Double>
        get() = hypothesisCredence.toImmutableMap()
}

@Serializable
data class EpistemicTippingPoint(
    val roundIndex: Int,
    val evidenceSnippet: String,
    val likelihoodRatio: Double = 1.0,
    val affectedHypothesis: String = "",
    val shiftDelta: Double = 0.0
)

@Serializable
data class RoundCredenceSnapshot(
    val roundIndex: Int,
    val personaCredences: List<PersonaCredencePoint> = emptyList(),
    val aggregatedCredence: Map<String, Double> = emptyMap(),
    val entropy: Double = 0.0,
    val dominantHypothesis: String? = null,
    val tippingPoints: List<EpistemicTippingPoint> = emptyList()
) {
    val immutablePersonaCredences: ImmutableList<PersonaCredencePoint>
        get() = personaCredences.toImmutableList()

    val immutableAggregatedCredence: ImmutableMap<String, Double>
        get() = aggregatedCredence.toImmutableMap()

    val immutableTippingPoints: ImmutableList<EpistemicTippingPoint>
        get() = tippingPoints.toImmutableList()

    fun probabilityFor(hypothesisId: String): Double =
        aggregatedCredence[hypothesisId] ?: 0.0

    fun formattedProbabilityFor(hypothesisId: String): String =
        "${((probabilityFor(hypothesisId)) * 100).toInt()}%"
}

@Serializable
data class CredenceLedger(
    val discussionId: String,
    val topic: String = "",
    val hypotheses: List<CredenceHypothesis> = emptyList(),
    val snapshots: List<RoundCredenceSnapshot> = emptyList(),
    val finalEntropy: Double = 0.0,
    val status: String = "IN_PROGRESS" // "CONVERGED" | "STALEMATE" | "IN_PROGRESS"
) {
    val immutableHypotheses: ImmutableList<CredenceHypothesis>
        get() = hypotheses.toImmutableList()

    val immutableSnapshots: ImmutableList<RoundCredenceSnapshot>
        get() = snapshots.toImmutableList()

    val latestSnapshot: RoundCredenceSnapshot?
        get() = snapshots.maxByOrNull { it.roundIndex }

    val isConverged: Boolean
        get() = status.equals("CONVERGED", ignoreCase = true)

    val isStalemate: Boolean
        get() = status.equals("STALEMATE", ignoreCase = true)
}
