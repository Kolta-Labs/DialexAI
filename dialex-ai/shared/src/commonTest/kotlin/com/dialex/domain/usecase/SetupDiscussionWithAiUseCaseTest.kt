package com.dialex.domain.usecase

import com.dialex.domain.model.AiSetupRequest
import com.dialex.domain.model.DomainException
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.DiscussionUsage
import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.DeliverableFormat
import com.dialex.model.Discussion
import com.dialex.model.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SetupDiscussionWithAiUseCaseTest {

    private class FakeDiscussionRepository : DiscussionRepository {
        var lastRequest: AiSetupRequest? = null

        override suspend fun getDiscussions(projectId: String?): List<Discussion> = emptyList()
        override suspend fun getDiscussion(id: String): Discussion = throw NotImplementedError()
        override suspend fun createDiscussion(projectId: String, name: String, config: DebateConfig): Discussion = throw NotImplementedError()
        override suspend fun updateDiscussion(discussion: Discussion): Discussion = throw NotImplementedError()
        override suspend fun deleteDiscussion(id: String) {}
        override suspend fun duplicateDiscussion(id: String): Discussion = throw NotImplementedError()
        override suspend fun startDiscussion(id: String) {}
        override suspend fun pauseDiscussion(id: String) {}
        override suspend fun stopDiscussion(id: String) {}
        override suspend fun resumeDiscussion(id: String) {}
        override fun streamDiscussion(id: String): Flow<Discussion> = emptyFlow()
        override suspend fun generateHandoffPrompt(id: String): String = ""
        override suspend fun generateDeliverableFormat(discussionId: String, format: DeliverableFormat): String = ""
        override suspend fun generateDiscussionTitle(id: String): String = ""
        override suspend fun getUsage(id: String): DiscussionUsage = DiscussionUsage(0, 0, 0.0)

        override suspend fun setupDiscussionWithAi(request: AiSetupRequest): Discussion {
            lastRequest = request
            return Discussion(
                id = "disc_test_123",
                projectId = request.projectId,
                name = "Generated Title",
                config = DebateConfig(
                    topic = "Test Topic",
                    primary = Agent(provider = Provider.ANTHROPIC, model = "claude-haiku-4-5-20251001")
                )
            )
        }
    }

    @Test
    fun blankPromptThrowsInvalidRequestException() = runTest {
        val repo = FakeDiscussionRepository()
        val useCase = SetupDiscussionWithAiUseCase(repo)

        assertFailsWith<DomainException.InvalidRequest> {
            useCase(prompt = "   ", projectId = "proj_1")
        }
    }

    @Test
    fun validPromptConstructsRequestAndReturnsDiscussion() = runTest {
        val repo = FakeDiscussionRepository()
        val useCase = SetupDiscussionWithAiUseCase(repo)

        val result = useCase(
            prompt = "  Should we choose Postgres or ClickHouse for telemetry?  ",
            projectId = "proj_analytics",
            model = "claude-haiku-4-5-20251001",
            autoStart = true
        )

        assertEquals("disc_test_123", result.id)
        assertEquals("Should we choose Postgres or ClickHouse for telemetry?", repo.lastRequest?.prompt)
        assertEquals("proj_analytics", repo.lastRequest?.projectId)
        assertEquals("claude-haiku-4-5-20251001", repo.lastRequest?.model)
        assertEquals(true, repo.lastRequest?.autoStart)
    }

    @Test
    fun validPromptWithSelectedAgentsPassesThemToRepository() = runTest {
        val repo = FakeDiscussionRepository()
        val useCase = SetupDiscussionWithAiUseCase(repo)

        val selected = listOf(
            com.dialex.domain.model.SelectedAgentSeat("ANTHROPIC", "claude-3-7-sonnet", "API"),
            com.dialex.domain.model.SelectedAgentSeat("OPENAI", "gpt-4o", "CLI"),
            com.dialex.domain.model.SelectedAgentSeat("GEMINI", "gemini-2.0-flash", "API")
        )

        val result = useCase(
            prompt = "Should we adopt Rust or Go for high throughput networking?",
            projectId = "proj_backend",
            model = "claude-haiku-4-5-20251001",
            autoStart = false,
            selectedAgents = selected
        )

        assertEquals("disc_test_123", result.id)
        assertEquals(3, repo.lastRequest?.selectedAgents?.size)
        assertEquals("ANTHROPIC", repo.lastRequest?.selectedAgents?.get(0)?.provider)
        assertEquals("claude-3-7-sonnet", repo.lastRequest?.selectedAgents?.get(0)?.model)
        assertEquals("API", repo.lastRequest?.selectedAgents?.get(0)?.runMode)
        assertEquals("OPENAI", repo.lastRequest?.selectedAgents?.get(1)?.provider)
        assertEquals("CLI", repo.lastRequest?.selectedAgents?.get(1)?.runMode)
    }
}
