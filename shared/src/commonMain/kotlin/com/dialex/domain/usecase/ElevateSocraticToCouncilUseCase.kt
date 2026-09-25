package com.dialex.domain.usecase

import com.dialex.domain.model.ElevateResult
import com.dialex.domain.model.SocraticElevateRequest
import com.dialex.domain.repository.DiscussionRepository

class ElevateSocraticToCouncilUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(
        discussionId: String,
        request: SocraticElevateRequest
    ): ElevateResult {
        require(discussionId.isNotEmpty()) { "Discussion ID cannot be empty" }
        require(request.projectId.isNotEmpty()) { "Project ID cannot be empty" }
        return repository.socraticElevate(discussionId, request)
    }
}
