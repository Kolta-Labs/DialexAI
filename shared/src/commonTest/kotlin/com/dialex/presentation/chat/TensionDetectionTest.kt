package com.dialex.presentation.chat

import com.dialex.domain.model.AgentAssertion
import com.dialex.domain.model.TensionFilter
import com.dialex.domain.model.TensionPair
import com.dialex.domain.model.TensionStatus
import com.dialex.domain.model.hasOpenTensions
import com.dialex.domain.model.openCount
import com.dialex.domain.model.resolvedCount
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TensionDetectionTest {

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
    fun testTensionPairSerialization() {
        val tension = TensionPair(
            id = "t_101",
            thesis = AgentAssertion(
                seatId = "seat_1",
                provider = Provider.ANTHROPIC,
                authorDisplayName = "Claude (Architect)",
                statement = "Synchronous consensus quorum required for durability.",
                quote = "...zero data loss requires synchronous raft...",
                round = 1
            ),
            antithesis = AgentAssertion(
                seatId = "seat_2",
                provider = Provider.GEMINI,
                authorDisplayName = "Gemini (Low-Latency)",
                statement = "Asynchronous replication required for <5ms p99 write SLA.",
                quote = "...p99 SLA cannot tolerate cross-datacenter RTT...",
                round = 1
            ),
            underlyingConflict = "ACID Quorum vs. Write Latency SLA",
            status = TensionStatus.OPEN,
            severity = 0.85f,
            detectedInRound = 1
        )

        val serialized = json.encodeToString(tension)
        val deserialized = json.decodeFromString<TensionPair>(serialized)

        assertEquals("t_101", deserialized.id)
        assertEquals(TensionStatus.OPEN, deserialized.status)
        assertEquals(Provider.ANTHROPIC, deserialized.thesis.provider)
        assertEquals("ACID Quorum vs. Write Latency SLA", deserialized.underlyingConflict)
        assertEquals(0.85f, deserialized.severity)
    }

    @Test
    fun testTensionHelpers() {
        val t1 = TensionPair(
            id = "t1",
            thesis = AgentAssertion(provider = Provider.ANTHROPIC, authorDisplayName = "A", statement = "A", round = 1),
            antithesis = AgentAssertion(provider = Provider.OPENAI, authorDisplayName = "B", statement = "B", round = 1),
            underlyingConflict = "Conflict 1",
            status = TensionStatus.OPEN
        )
        val t2 = TensionPair(
            id = "t2",
            thesis = AgentAssertion(provider = Provider.ANTHROPIC, authorDisplayName = "A", statement = "A", round = 1),
            antithesis = AgentAssertion(provider = Provider.OPENAI, authorDisplayName = "B", statement = "B", round = 1),
            underlyingConflict = "Conflict 2",
            status = TensionStatus.RESOLVED,
            synthesis = "Synthesized"
        )
        val t3 = TensionPair(
            id = "t3",
            thesis = AgentAssertion(provider = Provider.ANTHROPIC, authorDisplayName = "A", statement = "A", round = 1),
            antithesis = AgentAssertion(provider = Provider.OPENAI, authorDisplayName = "B", statement = "B", round = 1),
            underlyingConflict = "Conflict 3",
            status = TensionStatus.ACCEPTED_TRADE_OFF,
            tradeOffRationale = "Accepted trade-off"
        )

        val list = listOf(t1, t2, t3)
        assertTrue(list.hasOpenTensions())
        assertEquals(1, list.openCount())
        assertEquals(2, list.resolvedCount())

        val resolvedList = listOf(t2, t3)
        assertFalse(resolvedList.hasOpenTensions())
        assertEquals(0, resolvedList.openCount())
        assertEquals(2, resolvedList.resolvedCount())
    }

    @Test
    fun testChatViewModelTensionDrawerLifecycle() = runTest(testDispatcher) {
        val sampleTension = TensionPair(
            id = "t1",
            thesis = AgentAssertion(provider = Provider.ANTHROPIC, authorDisplayName = "Claude", statement = "Use Rust", round = 1),
            antithesis = AgentAssertion(provider = Provider.GEMINI, authorDisplayName = "Gemini", statement = "Use Go", round = 1),
            underlyingConflict = "Zero Overhead vs Shipping Velocity",
            status = TensionStatus.OPEN,
            detectedInRound = 1
        )

        val config = DebateConfig(
            topic = "Rust vs Go",
            primary = Agent(provider = Provider.ANTHROPIC, model = Provider.ANTHROPIC.defaultModel(), displayName = "Claude"),
            secondary = Agent(provider = Provider.GEMINI, model = Provider.GEMINI.defaultModel(), displayName = "Gemini")
        )

        val discussion = Discussion(
            id = "disc_100",
            projectId = "proj_1",
            name = "Rust vs Go Evaluation",
            config = config,
            status = DiscussionStatus.PAUSED,
            tensionPairs = listOf(sampleTension)
        )

        val repo = FakeDiscussionRepository(discussion)
        val vm = ChatViewModel(
            discussionRepository = repo,
            discussionId = "disc_100",
            tokenBudget = 100_000
        )
        advanceUntilIdle()

        // 1. Initial State verification
        assertEquals(1, vm.state.value.tensionPairs.size)
        assertEquals("Zero Overhead vs Shipping Velocity", vm.state.value.tensionPairs[0].underlyingConflict)
        assertFalse(vm.state.value.isTensionDrawerOpen)
        assertEquals(TensionFilter.ALL, vm.state.value.tensionFilter)

        // 2. Open tension drawer
        vm.onIntent(ChatIntent.ToggleTensionDrawer)
        assertTrue(vm.state.value.isTensionDrawerOpen)

        // 3. Filter switching
        vm.onIntent(ChatIntent.SetTensionFilter(TensionFilter.OPEN_ONLY))
        assertEquals(TensionFilter.OPEN_ONLY, vm.state.value.tensionFilter)

        vm.onIntent(ChatIntent.SetTensionFilter(TensionFilter.RESOLVED_ONLY))
        assertEquals(TensionFilter.RESOLVED_ONLY, vm.state.value.tensionFilter)

        // 4. Close tension drawer
        vm.onIntent(ChatIntent.SetTensionDrawerOpen(false))
        assertFalse(vm.state.value.isTensionDrawerOpen)
    }
}
