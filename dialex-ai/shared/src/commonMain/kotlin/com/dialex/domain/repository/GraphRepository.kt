package com.dialex.domain.repository

import com.dialex.domain.model.KnowledgeEdge
import com.dialex.domain.model.KnowledgeGraph
import com.dialex.domain.model.KnowledgeNode

interface GraphRepository {
    suspend fun getActiveGraph(projectId: String, minWeight: Double = 0.1): KnowledgeGraph
    suspend fun searchNodes(projectId: String, query: String, limit: Int = 10): List<KnowledgeNode>
    suspend fun upsertNode(projectId: String, node: KnowledgeNode): KnowledgeNode
    suspend fun deleteNode(nodeId: String)
    suspend fun upsertEdge(projectId: String, edge: KnowledgeEdge): KnowledgeEdge
    suspend fun triggerDecay(minThreshold: Double = 0.05, maxStaleDays: Int = 180): Long
}
