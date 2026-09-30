package com.dialex.presentation.graph

import com.dialex.domain.model.KnowledgeEdge
import com.dialex.domain.model.KnowledgeNode
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class GraphState(
    val projectId: String = "",
    val isLoading: Boolean = false,
    val searchQuery: String = "",
    val minWeightFilter: Float = 0.10f,
    val nodes: ImmutableList<KnowledgeNode> = persistentListOf(),
    val edges: ImmutableList<KnowledgeEdge> = persistentListOf(),
    val searchResults: ImmutableList<KnowledgeNode> = persistentListOf(),
    val selectedNode: KnowledgeNode? = null,
    val isSearching: Boolean = false,
    val errorMessage: String? = null
)

sealed interface GraphIntent {
    data class LoadGraph(val projectId: String) : GraphIntent
    data class UpdateSearchQuery(val query: String) : GraphIntent
    data class UpdateWeightFilter(val minWeight: Float) : GraphIntent
    data class SelectNode(val node: KnowledgeNode?) : GraphIntent
    data class DeleteNode(val nodeId: String) : GraphIntent
    object RefreshGraph : GraphIntent
    object DismissError : GraphIntent
}

sealed interface GraphEffect {
    data class ShowSnackbar(val message: String) : GraphEffect
    data class NavigateToDebate(val debateId: String) : GraphEffect
}
