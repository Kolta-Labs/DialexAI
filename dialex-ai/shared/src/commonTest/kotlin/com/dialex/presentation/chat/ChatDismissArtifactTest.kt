package com.dialex.presentation.chat

import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.DiscussionUsage
import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.DiscussionArtifact
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

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDismissArtifactTest {

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
        var lastUpdatedDiscussion: Discussion? = null

        override suspend fun getDiscussions(projectId: String?): List<Discussion> = listOf(currentDiscussion)
        override suspend fun getDiscussion(id: String): Discussion = currentDiscussion
        override suspend fun createDiscussion(projectId: String, name: String, config: DebateConfig): Discussion = currentDiscussion
        override suspend fun updateDiscussion(discussion: Discussion): Discussion {
            currentDiscussion = discussion
            lastUpdatedDiscussion = discussion
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

    private fun createSampleDiscussion(
        id: String = "disc_1",
        dismissedArtifactIds: List<String> = emptyList()
    ): Discussion {
        return Discussion(
            id = id,
            projectId = "proj_1",
            name = "Test Discussion",
            config = DebateConfig(
                topic = "Test Topic",
                primary = Agent(
                    provider = Provider.ANTHROPIC,
                    model = Provider.ANTHROPIC.defaultModel()
                )
            ),
            status = DiscussionStatus.DONE,
            artifacts = listOf(
                DiscussionArtifact(
                    id = "art_del_1",
                    name = "Decision Summary",
                    type = "Deliverable",
                    format = "md",
                    content = "# Summary content"
                ),
                DiscussionArtifact(
                    id = "art_sum_1",
                    name = "Discussion Recap",
                    type = "Summary",
                    format = "md",
                    content = "# Recap content"
                )
            ),
            dismissedArtifactIds = dismissedArtifactIds
        )
    }

    @Test
    fun discussion_withDismissedArtifactIds_serializesAndDeserializesCorrectly() {
        val original = createSampleDiscussion(
            dismissedArtifactIds = listOf("art_del_1", "art_sum_1")
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<Discussion>(encoded)

        assertEquals(original.dismissedArtifactIds, decoded.dismissedArtifactIds)
        assertEquals(listOf("art_del_1", "art_sum_1"), decoded.dismissedArtifactIds)
    }

    @Test
    fun dismissArtifactBanner_addsArtifactIdToDiscussionAndPersists() = runTest(testDispatcher) {
        val initialDiscussion = createSampleDiscussion()
        val repository = FakeDiscussionRepository(initialDiscussion)
        val viewModel = ChatViewModel(
            discussionRepository = repository,
            discussionId = initialDiscussion.id,
            tokenBudget = 100_000
        )
        advanceUntilIdle()

        // Initially no dismissed artifacts
        assertEquals(emptyList(), viewModel.state.value.discussion?.dismissedArtifactIds)

        // Dismiss first artifact banner
        viewModel.onIntent(ChatIntent.DismissArtifactBanner("art_del_1"))
        advanceUntilIdle()

        // Verify updated in state
        val updatedDismissed = viewModel.state.value.discussion?.dismissedArtifactIds
        assertEquals(listOf("art_del_1"), updatedDismissed)

        // Verify persisted to repository
        assertEquals(listOf("art_del_1"), repository.lastUpdatedDiscussion?.dismissedArtifactIds)

        // Dismiss second artifact banner
        viewModel.onIntent(ChatIntent.DismissArtifactBanner("art_sum_1"))
        advanceUntilIdle()

        assertEquals(listOf("art_del_1", "art_sum_1"), viewModel.state.value.discussion?.dismissedArtifactIds)
        assertEquals(listOf("art_del_1", "art_sum_1"), repository.lastUpdatedDiscussion?.dismissedArtifactIds)

        // Re-dismissing an already dismissed banner is idempotent
        viewModel.onIntent(ChatIntent.DismissArtifactBanner("art_del_1"))
        advanceUntilIdle()

        assertEquals(listOf("art_del_1", "art_sum_1"), viewModel.state.value.discussion?.dismissedArtifactIds)
    }
}
