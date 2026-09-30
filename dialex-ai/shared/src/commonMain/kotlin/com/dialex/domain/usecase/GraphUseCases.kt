package com.dialex.domain.usecase

import com.dialex.domain.model.KnowledgeEdge
import com.dialex.domain.model.KnowledgeGraph
import com.dialex.domain.model.KnowledgeNode
import com.dialex.domain.repository.GraphRepository

class GetActiveGraphUseCase(
    private val graphRepository: GraphRepository
) {
    suspend operator fun invoke(projectId: String, minWeight: Double = 0.1): KnowledgeGraph {
        return graphRepository.getActiveGraph(projectId, minWeight)
    }
}

class SearchGraphNodesUseCase(
    private val graphRepository: GraphRepository
) {
    suspend operator fun invoke(projectId: String, query: String, limit: Int = 10): List<KnowledgeNode> {
        return graphRepository.searchNodes(projectId, query, limit)
    }
}

class UpsertGraphNodeUseCase(
    private val graphRepository: GraphRepository
) {
    suspend operator fun invoke(projectId: String, node: KnowledgeNode): KnowledgeNode {
        return graphRepository.upsertNode(projectId, node)
    }
}

class DeleteGraphNodeUseCase(
    private val graphRepository: GraphRepository
) {
    suspend operator fun invoke(nodeId: String) {
        graphRepository.deleteNode(nodeId)
    }
}
