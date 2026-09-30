package com.dialex.domain.usecase

import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.DiscussionUsage
import com.dialex.model.DebateConfig
import com.dialex.model.DeliverableFormat
import com.dialex.model.Discussion
import kotlinx.coroutines.flow.Flow

class GetDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String): Discussion = repository.getDiscussion(id)
}

class GetDiscussionsUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(projectId: String? = null): List<Discussion> =
        repository.getDiscussions(projectId)
}

class CreateDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(
        projectId: String,
        name: String,
        config: DebateConfig,
        mode: com.dialex.domain.model.DiscussionMode = com.dialex.domain.model.DiscussionMode.COUNCIL,
        socraticConfig: com.dialex.domain.model.SocraticConfig? = null
    ): Discussion = repository.createDiscussion(projectId, name, config, mode, socraticConfig)
}

class StreamDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    operator fun invoke(id: String): Flow<Discussion> = repository.streamDiscussion(id)
}

class StartDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String) = repository.startDiscussion(id)
}

class PauseDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String) = repository.pauseDiscussion(id)
}

class ResumeDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String) = repository.resumeDiscussion(id)
}

class StopDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String) = repository.stopDiscussion(id)
}

class UpdateDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(discussion: Discussion): Discussion =
        repository.updateDiscussion(discussion)
}

class DeleteDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String) = repository.deleteDiscussion(id)
}

class DuplicateDiscussionUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String): Discussion = repository.duplicateDiscussion(id)
}

class GetDiscussionUsageUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String): DiscussionUsage = repository.getUsage(id)
}

class GenerateDiscussionTitleUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String): String = repository.generateDiscussionTitle(id)
}

class GenerateDeliverableFormatUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(discussionId: String, format: DeliverableFormat): String =
        repository.generateDeliverableFormat(discussionId, format)
}

class GenerateHandoffPromptUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(id: String): String = repository.generateHandoffPrompt(id)
}
