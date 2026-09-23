package com.dialex.presentation.graph

import com.dialex.domain.model.GraphNodeType
import com.dialex.domain.model.GraphRelationType
import com.dialex.domain.model.KnowledgeEdge
import com.dialex.domain.model.KnowledgeGraph
import com.dialex.domain.model.KnowledgeNode
import com.dialex.domain.repository.GraphRepository
import com.dialex.domain.usecase.DeleteGraphNodeUseCase
import com.dialex.domain.usecase.GetActiveGraphUseCase
import com.dialex.domain.usecase.SearchGraphNodesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private class FakeGraphRepository : GraphRepository {
    val nodes = mutableListOf<KnowledgeNode>()
    val edges = mutableListOf<KnowledgeEdge>()

    override suspend fun getActiveGraph(projectId: String, minWeight: Double): KnowledgeGraph {
        return KnowledgeGraph(
            nodes = nodes.filter { it.projectId == projectId && it.currentWeight >= minWeight },
            edges = edges.filter { it.projectId == projectId }
        )
    }

    override suspend fun searchNodes(projectId: String, query: String, limit: Int): List<KnowledgeNode> {
        return nodes.filter {
            it.projectId == projectId && (it.title.contains(query, ignoreCase = true) || it.content.contains(query, ignoreCase = true))
        }.take(limit)
    }

    override suspend fun upsertNode(projectId: String, node: KnowledgeNode): KnowledgeNode {
        nodes.removeAll { it.id == node.id }
        nodes.add(node)
        return node
    }

    override suspend fun deleteNode(nodeId: String) {
        nodes.removeAll { it.id == nodeId }
        edges.removeAll { it.sourceId == nodeId || it.targetId == nodeId }
    }

    override suspend fun upsertEdge(projectId: String, edge: KnowledgeEdge): KnowledgeEdge {
        edges.removeAll { it.id == edge.id }
        edges.add(edge)
        return edge
    }

    override suspend fun triggerDecay(minThreshold: Double, maxStaleDays: Int): Long {
        val count = nodes.count { it.currentWeight < minThreshold }
        nodes.removeAll { it.currentWeight < minThreshold }
        return count.toLong()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GraphViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeGraphRepository
    private lateinit var viewModel: GraphViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeGraphRepository().apply {
            nodes.add(
                KnowledgeNode(
                    id = "node-1",
                    projectId = "p-test",
                    type = GraphNodeType.CONCEPT,
                    title = "CAP Theorem",
                    content = "Consistency, Availability, Partition Tolerance",
                    weight = 1.0,
                    currentWeight = 1.0
                )
            )
            nodes.add(
                KnowledgeNode(
                    id = "node-2",
                    projectId = "p-test",
                    type = GraphNodeType.TENSION,
                    title = "Latency vs Strong Consistency",
                    content = "Trade-offs in synchronous replication",
                    weight = 0.8,
                    currentWeight = 0.8
                )
            )
            edges.add(
                KnowledgeEdge(
                    id = "edge-1",
                    projectId = "p-test",
                    sourceId = "node-1",
                    targetId = "node-2",
                    relation = GraphRelationType.SUPPORTS,
                    strength = 0.7
                )
            )
        }
        viewModel = GraphViewModel(
            getActiveGraphUseCase = GetActiveGraphUseCase(fakeRepository),
            searchGraphNodesUseCase = SearchGraphNodesUseCase(fakeRepository),
            deleteGraphNodeUseCase = DeleteGraphNodeUseCase(fakeRepository)
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadGraph updates state with active nodes and edges`() = runTest(testDispatcher) {
        viewModel.onIntent(GraphIntent.LoadGraph("p-test"))
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("p-test", state.projectId)
        assertEquals(2, state.nodes.size)
        assertEquals(1, state.edges.size)
        assertEquals(false, state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun `selectNode and deleteNode updates selection and removes node`() = runTest(testDispatcher) {
        viewModel.onIntent(GraphIntent.LoadGraph("p-test"))
        testDispatcher.scheduler.advanceUntilIdle()

        val nodeToSelect = viewModel.state.value.nodes.first()
        viewModel.onIntent(GraphIntent.SelectNode(nodeToSelect))
        assertEquals("node-1", viewModel.state.value.selectedNode?.id)

        viewModel.onIntent(GraphIntent.DeleteNode("node-1"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.state.value.nodes.size)
        assertNull(viewModel.state.value.selectedNode)
        assertEquals(0, viewModel.state.value.edges.size) // Cascaded edge removal
    }
}
