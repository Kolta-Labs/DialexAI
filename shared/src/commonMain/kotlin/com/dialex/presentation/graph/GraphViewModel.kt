package com.dialex.presentation.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dialex.domain.usecase.DeleteGraphNodeUseCase
import com.dialex.domain.usecase.GetActiveGraphUseCase
import com.dialex.domain.usecase.SearchGraphNodesUseCase
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GraphViewModel(
    private val getActiveGraphUseCase: GetActiveGraphUseCase,
    private val searchGraphNodesUseCase: SearchGraphNodesUseCase,
    private val deleteGraphNodeUseCase: DeleteGraphNodeUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(GraphState())
    val state = _state.asStateFlow()

    private val _effects = Channel<GraphEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onIntent(intent: GraphIntent) {
        when (intent) {
            is GraphIntent.LoadGraph -> loadGraph(intent.projectId)
            is GraphIntent.UpdateSearchQuery -> {
                _state.update { it.copy(searchQuery = intent.query) }
                performSearch(intent.query)
            }
            is GraphIntent.UpdateWeightFilter -> {
                _state.update { it.copy(minWeightFilter = intent.minWeight) }
                loadGraph(_state.value.projectId)
            }
            is GraphIntent.SelectNode -> {
                _state.update { it.copy(selectedNode = intent.node) }
            }
            is GraphIntent.DeleteNode -> deleteNode(intent.nodeId)
            is GraphIntent.RefreshGraph -> loadGraph(_state.value.projectId)
            is GraphIntent.DismissError -> _state.update { it.copy(errorMessage = null) }
        }
    }

    private fun loadGraph(projectId: String) {
        if (projectId.isBlank()) return
        _state.update { it.copy(isLoading = true, projectId = projectId) }
        viewModelScope.launch {
            try {
                val graph = getActiveGraphUseCase(projectId, _state.value.minWeightFilter.toDouble())
                _state.update {
                    it.copy(
                        isLoading = false,
                        nodes = graph.nodes.toPersistentList(),
                        edges = graph.edges.toPersistentList(),
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to load knowledge graph"
                _state.update { it.copy(isLoading = false, errorMessage = msg) }
                _effects.send(GraphEffect.ShowSnackbar(msg))
            }
        }
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) {
            _state.update { it.copy(searchResults = kotlinx.collections.immutable.persistentListOf(), isSearching = false) }
            return
        }
        _state.update { it.copy(isSearching = true) }
        viewModelScope.launch {
            try {
                val results = searchGraphNodesUseCase(_state.value.projectId, query, limit = 15)
                _state.update {
                    it.copy(
                        searchResults = results.toPersistentList(),
                        isSearching = false
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isSearching = false) }
            }
        }
    }

    private fun deleteNode(nodeId: String) {
        viewModelScope.launch {
            try {
                deleteGraphNodeUseCase(nodeId)
                _state.update {
                    it.copy(
                        nodes = it.nodes.filter { n -> n.id != nodeId }.toPersistentList(),
                        edges = it.edges.filter { e -> e.sourceId != nodeId && e.targetId != nodeId }.toPersistentList(),
                        selectedNode = if (it.selectedNode?.id == nodeId) null else it.selectedNode
                    )
                }
                _effects.send(GraphEffect.ShowSnackbar("Node deleted"))
            } catch (e: Exception) {
                _effects.send(GraphEffect.ShowSnackbar(e.message ?: "Failed to delete node"))
            }
        }
    }
}
