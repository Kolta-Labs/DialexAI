package com.dialex.data.repository

import com.dialex.data.datasource.EngineDataSource
import com.dialex.domain.model.KnowledgeEdge
import com.dialex.domain.model.KnowledgeGraph
import com.dialex.domain.model.KnowledgeNode
import com.dialex.domain.repository.GraphRepository

class GraphRepositoryImpl(
    private val dataSource: EngineDataSource
) : GraphRepository {

    override suspend fun getActiveGraph(projectId: String, minWeight: Double): KnowledgeGraph {
        return dataSource.getActiveGraph(projectId, minWeight)
    }

    override suspend fun searchNodes(projectId: String, query: String, limit: Int): List<KnowledgeNode> {
        return dataSource.searchGraphNodes(projectId, query, limit)
    }

    override suspend fun upsertNode(projectId: String, node: KnowledgeNode): KnowledgeNode {
        return dataSource.upsertGraphNode(projectId, node)
    }

    override suspend fun deleteNode(nodeId: String) {
        dataSource.deleteGraphNode(nodeId)
    }

    override suspend fun upsertEdge(projectId: String, edge: KnowledgeEdge): KnowledgeEdge {
        return dataSource.upsertGraphEdge(projectId, edge)
    }

    override suspend fun triggerDecay(minThreshold: Double, maxStaleDays: Int): Long {
        return dataSource.triggerGraphDecay(minThreshold, maxStaleDays)
    }
}
