package com.dialex.domain.usecase

import com.dialex.domain.model.AiSetupRequest
import com.dialex.domain.model.DomainException
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.model.Discussion

import com.dialex.domain.model.SelectedAgentSeat

/**
 * UseCase that takes raw user input (text or speech transcript) and requests the AI deliberation
 * architect to determine topic, context, constraints, and custom council personas.
 */
class SetupDiscussionWithAiUseCase(
    private val discussionRepository: DiscussionRepository
) {
    suspend operator fun invoke(
        prompt: String,
        projectId: String,
        model: String? = null,
        provider: String? = null,
        autoStart: Boolean = false,
        numAgents: Int = 3,
        selectedAgents: List<SelectedAgentSeat>? = null
    ): Discussion {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) {
            throw DomainException.InvalidRequest("Prompt cannot be empty")
        }
        val request = AiSetupRequest(
            prompt = trimmed,
            projectId = projectId.trim(),
            model = model?.trim()?.ifBlank { null },
            provider = provider?.trim()?.ifBlank { null },
            autoStart = autoStart,
            numAgents = numAgents,
            selectedAgents = selectedAgents?.ifEmpty { null }
        )
        return discussionRepository.setupDiscussionWithAi(request)
    }
}
