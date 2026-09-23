package com.dialex.domain.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.serialization.Serializable

@Serializable
enum class GraphNodeType {
    CONCEPT, ARGUMENT, CONSENSUS, TENSION, DELIVERABLE, SOURCE
}

@Serializable
enum class GraphRelationType {
    CONTRADICTS, SUPPORTS, BLENDS_INTO, DERIVED_FROM, PREREQUISITE_FOR
}

@Serializable
data class KnowledgeNode(
    val id: String,
    val projectId: String,
    val type: GraphNodeType,
    val title: String,
    val content: String,
    val weight: Double = 1.0,
    val currentWeight: Double = 1.0,
    val decayHalfLifeSecs: Long = 2592000L,
    val createdAt: Long = 0L,
    val lastAccessedAt: Long = 0L,
    val metadata: Map<String, String> = emptyMap()
)

@Serializable
data class KnowledgeEdge(
    val id: String,
    val projectId: String,
    val sourceId: String,
    val targetId: String,
    val relation: GraphRelationType,
    val strength: Double = 0.5,
    val createdAt: Long = 0L,
    val lastReinforcedAt: Long = 0L
)

@Serializable
data class KnowledgeGraph(
    val nodes: List<KnowledgeNode> = emptyList(),
    val edges: List<KnowledgeEdge> = emptyList()
)
