package com.dialex.domain.usecase

import com.dialex.domain.model.SocraticDigest
import com.dialex.domain.repository.DiscussionRepository

class GenerateSocraticDigestUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(discussionId: String): SocraticDigest {
        require(discussionId.isNotEmpty()) { "Discussion ID cannot be empty" }
        return repository.socraticDigest(discussionId)
    }
}
