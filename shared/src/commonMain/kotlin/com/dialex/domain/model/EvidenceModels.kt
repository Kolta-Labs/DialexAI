package com.dialex.domain.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable

@Serializable
enum class EvidenceSourceType {
    KNOWLEDGE_GRAPH,
    ATTACHED_FILE,
    WORKSPACE_DOC
}

@Serializable
data class EvidenceItem(
    val id: String,
    val round: Int,
    val query: String,
    val sourceType: EvidenceSourceType,
    val sourceId: String,
    val sourceTitle: String,
    val snippet: String,
    val score: Double = 0.0,
    val attributionBadge: String = "",
    val timestampMs: Long = 0L
) {
    fun formattedScore(): String = "${(score * 100).toInt()}%"
}

@Serializable
data class RoundEvidence(
    val round: Int,
    val triggerQueries: List<String> = emptyList(),
    val items: List<EvidenceItem> = emptyList(),
    val summaryContext: String = ""
) {
    val immutableItems: ImmutableList<EvidenceItem>
        get() = items.toImmutableList()
}

fun List<RoundEvidence>.forRound(round: Int): RoundEvidence? =
    find { it.round == round }

fun List<RoundEvidence>.totalItemCount(): Int =
    sumOf { it.items.size }
