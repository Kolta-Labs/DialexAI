package com.dialex.domain.usecase

import com.dialex.domain.model.ConsensusResult
import com.dialex.domain.model.DomainException
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.DiscussionUsage
import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.DeliverableFormat
import com.dialex.model.Discussion
import com.dialex.model.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConsensusAndQuickstartUseCaseTest {

    private class FakeRepo : DiscussionRepository {
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

        var lastModeId: String? = null
        var lastRound: Int? = null

        override suspend fun applyQuickstart(modeId: String, config: DebateConfig): DebateConfig {
            lastModeId = modeId
            return config.copy(maxRounds = 3)
        }

        override suspend fun evaluateConsensus(config: DebateConfig, transcript: List<DebateMessage>, round: Int?): ConsensusResult {
            lastRound = round
            return ConsensusResult(achieved = true, agreedCount = transcript.size, totalCount = transcript.size, ratio = 1.0)
        }
    }

    private val config = DebateConfig(topic = "t", primary = Agent(provider = Provider.ANTHROPIC, model = "m"))

    @Test
    fun applyQuickstartTrimsIdAndReturnsEngineConfig() = runTest {
        val repo = FakeRepo()
        val result = ApplyQuickstartModeUseCase(repo)(" redteam ", config)
        assertEquals("redteam", repo.lastModeId)
        assertEquals(3, result.maxRounds)
    }

    @Test
    fun applyQuickstartRejectsBlankId() = runTest {
        assertFailsWith<DomainException.InvalidRequest> { ApplyQuickstartModeUseCase(FakeRepo())("  ", config) }
    }

    @Test
    fun evaluateConsensusPassesThroughEngineResult() = runTest {
        val repo = FakeRepo()
        val msgs = listOf(DebateMessage(seatId = "a", round = 1, content = "x", agreed = true))
        val result = EvaluateConsensusUseCase(repo)(config, msgs, round = 2)
        assertTrue(result.achieved)
        assertEquals(1, result.agreedCount)
        assertEquals(2, repo.lastRound)
    }
}
