package com.dialex.domain.repository

import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import kotlinx.coroutines.flow.Flow

/** Domain-layer contract for discussion operations. Implemented in data layer; used by UseCases. */
interface DiscussionRepository {
    suspend fun getDiscussions(projectId: String? = null): List<Discussion>
    suspend fun getDiscussion(id: String): Discussion
    suspend fun createDiscussion(
        projectId: String,
        name: String,
        config: DebateConfig,
    ): Discussion

    suspend fun createDiscussion(
        projectId: String,
        name: String,
        config: DebateConfig,
        mode: com.dialex.domain.model.DiscussionMode = com.dialex.domain.model.DiscussionMode.COUNCIL,
        socraticConfig: com.dialex.domain.model.SocraticConfig? = null,
    ): Discussion = createDiscussion(projectId, name, config)
    suspend fun updateDiscussion(discussion: Discussion): Discussion
    suspend fun deleteDiscussion(id: String)
    suspend fun duplicateDiscussion(id: String): Discussion

    suspend fun startDiscussion(id: String)
    suspend fun pauseDiscussion(id: String)
    suspend fun stopDiscussion(id: String)
    suspend fun resumeDiscussion(id: String)

    /** Returns a cold [Flow] of SSE-streamed [Discussion] snapshots while the debate runs.
     * Collect on a background dispatcher; cancel the collection to stop streaming. */
    fun streamDiscussion(id: String): Flow<Discussion>

    suspend fun generateHandoffPrompt(id: String): String

    suspend fun generateDeliverableFormat(discussionId: String, format: com.dialex.model.DeliverableFormat): String
    suspend fun generateDiscussionTitle(id: String): String
    suspend fun setupDiscussionWithAi(request: com.dialex.domain.model.AiSetupRequest): Discussion =
        throw NotImplementedError()

    // ── Socratic Interview ─────────────────────────────────────────────────────

    suspend fun socraticTurn(discussionId: String, request: com.dialex.domain.model.SocraticTurnRequest): com.dialex.domain.model.SocraticTurnResponse =
        throw NotImplementedError()
    suspend fun socraticDigest(discussionId: String): com.dialex.domain.model.SocraticDigest =
        throw NotImplementedError()
    suspend fun socraticElevate(discussionId: String, request: com.dialex.domain.model.SocraticElevateRequest): com.dialex.domain.model.ElevateResult =
        throw NotImplementedError()

    /** Token usage totals for a single discussion (in, out, estimated cost in USD). */
    suspend fun getUsage(id: String): DiscussionUsage
}

data class DiscussionUsage(
    val tokensIn: Long,
    val tokensOut: Long,
    val estimatedCostUsd: Double,
)
