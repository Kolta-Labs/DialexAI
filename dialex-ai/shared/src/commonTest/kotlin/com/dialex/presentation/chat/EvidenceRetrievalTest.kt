package com.dialex.presentation.chat

import com.dialex.domain.model.EvidenceItem
import com.dialex.domain.model.EvidenceSourceType
import com.dialex.domain.model.RoundEvidence
import com.dialex.domain.model.forRound
import com.dialex.domain.model.totalItemCount
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.DiscussionUsage
import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Provider
import com.dialex.model.defaultModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EvidenceRetrievalTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeDiscussionRepository(
        var currentDiscussion: Discussion
    ) : DiscussionRepository {
        override suspend fun getDiscussions(projectId: String?): List<Discussion> = listOf(currentDiscussion)
        override suspend fun getDiscussion(id: String): Discussion = currentDiscussion
        override suspend fun createDiscussion(projectId: String, name: String, config: DebateConfig): Discussion = currentDiscussion
        override suspend fun updateDiscussion(discussion: Discussion): Discussion {
            currentDiscussion = discussion
            return discussion
        }
        override suspend fun deleteDiscussion(id: String) {}
        override suspend fun duplicateDiscussion(id: String): Discussion = currentDiscussion
        override suspend fun startDiscussion(id: String) {}
        override suspend fun pauseDiscussion(id: String) {}
        override suspend fun stopDiscussion(id: String) {}
        override suspend fun resumeDiscussion(id: String) {}
        override fun streamDiscussion(id: String): Flow<Discussion> = emptyFlow()
        override suspend fun generateHandoffPrompt(id: String): String = ""
        override suspend fun generateDeliverableFormat(discussionId: String, format: com.dialex.model.DeliverableFormat): String = ""
        override suspend fun generateDiscussionTitle(id: String): String = "Generated Title"
        override suspend fun getUsage(id: String): DiscussionUsage = DiscussionUsage(0, 0, 0.0)
    }

    @Test
    fun testEvidenceModelsSerialization() {
        val evidenceItem = EvidenceItem(
            id = "ev_g_sqlite_wal",
            round = 2,
            query = "sqlite wal concurrency",
            sourceType = EvidenceSourceType.KNOWLEDGE_GRAPH,
            sourceId = "node_sqlite_wal",
            sourceTitle = "SQLite WAL Concurrency",
            snippet = "In WAL mode, readers do not block writers and writers do not block readers.",
            score = 0.94,
            attributionBadge = "[Evidence: Graph Node \"SQLite WAL Concurrency\"]",
            timestampMs = 1727244000000L
        )

        val roundEvidence = RoundEvidence(
            round = 2,
            triggerQueries = listOf("sqlite wal concurrency", "postgres replication latency"),
            items = listOf(evidenceItem),
            summaryContext = "[DYNAMIC GROUNDING EVIDENCE FOR ROUND 2]\n1. SQLite WAL Concurrency..."
        )

        val encoded = json.encodeToString(roundEvidence)
        val decoded = json.decodeFromString<RoundEvidence>(encoded)

        assertEquals(2, decoded.round)
        assertEquals(2, decoded.triggerQueries.size)
        assertEquals(1, decoded.items.size)

        val item = decoded.items[0]
        assertEquals("ev_g_sqlite_wal", item.id)
        assertEquals(EvidenceSourceType.KNOWLEDGE_GRAPH, item.sourceType)
        assertEquals(0.94, item.score)
        assertEquals("94%", item.formattedScore())
        assertEquals("[Evidence: Graph Node \"SQLite WAL Concurrency\"]", item.attributionBadge)
    }

    @Test
    fun testEvidenceHelperFunctions() {
        val r2 = RoundEvidence(
            round = 2,
            items = listOf(
                EvidenceItem("1", 2, "q1", EvidenceSourceType.KNOWLEDGE_GRAPH, "s1", "T1", "sn1", 0.9),
                EvidenceItem("2", 2, "q2", EvidenceSourceType.ATTACHED_FILE, "s2", "T2", "sn2", 0.8)
            )
        )
        val r3 = RoundEvidence(
            round = 3,
            items = listOf(
                EvidenceItem("3", 3, "q3", EvidenceSourceType.WORKSPACE_DOC, "s3", "T3", "sn3", 0.75)
            )
        )

        val list = listOf(r2, r3)

        assertEquals(3, list.totalItemCount())
        assertNotNull(list.forRound(2))
        assertEquals(2, list.forRound(2)?.items?.size)
        assertNotNull(list.forRound(3))
        assertEquals(1, list.forRound(3)?.items?.size)
        assertNull(list.forRound(4))
    }

    @Test
    fun testChatViewModelEvidenceDrawerLifecycle() = runTest(testDispatcher) {
        val evidenceItem = EvidenceItem(
            id = "ev_doc_1",
            round = 2,
            query = "postgresql write latency",
            sourceType = EvidenceSourceType.ATTACHED_FILE,
            sourceId = "doc_benchmarks_p0",
            sourceTitle = "benchmarks.md",
            snippet = "PostgreSQL sustained 14,200 writes/sec.",
            score = 0.88,
            attributionBadge = "[Evidence: File \"benchmarks.md\"]"
        )
        val initialEvidence = listOf(
            RoundEvidence(round = 2, triggerQueries = listOf("postgresql write latency"), items = listOf(evidenceItem))
        )

        val discussion = Discussion(
            id = "disc_rag_100",
            projectId = "proj_test",
            name = "RAG Deliberation",
            config = DebateConfig(
                topic = "Storage Architecture",
                primary = Agent(
                    id = "agent_primary",
                    provider = Provider.ANTHROPIC,
                    model = Provider.ANTHROPIC.defaultModel()
                )
            ),
            status = DiscussionStatus.PAUSED,
            retrievedEvidence = initialEvidence
        )

        val repo = FakeDiscussionRepository(discussion)
        val viewModel = ChatViewModel(
            discussionRepository = repo,
            discussionId = "disc_rag_100",
            tokenBudget = 100_000
        )
        advanceUntilIdle()

        // 1. Initial State: retrievedEvidence synchronized from Discussion
        assertEquals(1, viewModel.state.value.retrievedEvidence.size)
        assertEquals(1, viewModel.state.value.retrievedEvidence[0].items.size)
        assertEquals("ev_doc_1", viewModel.state.value.retrievedEvidence[0].items[0].id)
        assertFalse(viewModel.state.value.isEvidenceDrawerOpen)
        assertNull(viewModel.state.value.selectedEvidenceRound)

        // 2. Toggle Evidence Drawer open for Round 2
        viewModel.onIntent(ChatIntent.ToggleEvidenceDrawer(round = 2))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isEvidenceDrawerOpen)
        assertEquals(2, viewModel.state.value.selectedEvidenceRound)

        // 3. Switch round filter in drawer
        viewModel.onIntent(ChatIntent.ToggleEvidenceDrawer(round = null))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isEvidenceDrawerOpen)

        // 4. Set explicit open
        viewModel.onIntent(ChatIntent.SetEvidenceDrawerOpen(open = true, round = 2))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isEvidenceDrawerOpen)
        assertEquals(2, viewModel.state.value.selectedEvidenceRound)

        // 5. Dismiss drawer
        viewModel.onIntent(ChatIntent.SetEvidenceDrawerOpen(open = false))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isEvidenceDrawerOpen)
    }
}
