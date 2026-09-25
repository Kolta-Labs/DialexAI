package com.dialex.data.repository

import com.dialex.data.datasource.EngineDataSource
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.DiscussionUsage
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import kotlinx.coroutines.flow.Flow

class DiscussionRepositoryImpl(private val dataSource: EngineDataSource) : DiscussionRepository {
    override suspend fun getDiscussions(projectId: String?): List<Discussion> =
        dataSource.listDiscussions(projectId)

    override suspend fun getDiscussion(id: String): Discussion = dataSource.getDiscussion(id)

    override suspend fun createDiscussion(
        projectId: String,
        name: String,
        config: DebateConfig,
    ): Discussion = dataSource.createDiscussion(projectId, name, config, com.dialex.domain.model.DiscussionMode.COUNCIL, null)

    override suspend fun createDiscussion(
        projectId: String,
        name: String,
        config: DebateConfig,
        mode: com.dialex.domain.model.DiscussionMode,
        socraticConfig: com.dialex.domain.model.SocraticConfig?,
    ): Discussion = dataSource.createDiscussion(projectId, name, config, mode, socraticConfig)

    override suspend fun updateDiscussion(discussion: Discussion): Discussion =
        dataSource.updateDiscussion(discussion)

    override suspend fun deleteDiscussion(id: String) = dataSource.deleteDiscussion(id)

    override suspend fun duplicateDiscussion(id: String): Discussion =
        dataSource.duplicateDiscussion(id)

    override suspend fun startDiscussion(id: String) = dataSource.startDiscussion(id)
    override suspend fun pauseDiscussion(id: String) = dataSource.pauseDiscussion(id)
    override suspend fun stopDiscussion(id: String) = dataSource.stopDiscussion(id)
    override suspend fun resumeDiscussion(id: String) = dataSource.resumeDiscussion(id)

    override fun streamDiscussion(id: String): Flow<Discussion> = dataSource.streamDiscussion(id)

    override suspend fun generateHandoffPrompt(id: String): String =
        dataSource.generateHandoffPrompt(id)

    override suspend fun generateDeliverableFormat(discussionId: String, format: com.dialex.model.DeliverableFormat): String =
        dataSource.generateDeliverableFormat(discussionId, format)

    override suspend fun generateDiscussionTitle(id: String): String =
        dataSource.generateDiscussionTitle(id)

    override suspend fun setupDiscussionWithAi(request: com.dialex.domain.model.AiSetupRequest): Discussion =
        dataSource.setupDiscussionWithAi(request)

    override suspend fun socraticTurn(
        discussionId: String,
        request: com.dialex.domain.model.SocraticTurnRequest,
    ): com.dialex.domain.model.SocraticTurnResponse =
        dataSource.socraticTurn(discussionId, request)

    override suspend fun socraticDigest(
        discussionId: String,
    ): com.dialex.domain.model.SocraticDigest =
        dataSource.socraticDigest(discussionId)

    override suspend fun socraticElevate(
        discussionId: String,
        request: com.dialex.domain.model.SocraticElevateRequest,
    ): com.dialex.domain.model.ElevateResult =
        dataSource.socraticElevate(discussionId, request)

    override suspend fun getUsage(id: String): DiscussionUsage {
        val resp = dataSource.getDiscussionUsage(id)
        return DiscussionUsage(
            tokensIn = resp.tokensIn,
            tokensOut = resp.tokensOut,
            estimatedCostUsd = resp.estimatedCostUsd,
        )
    }
}
