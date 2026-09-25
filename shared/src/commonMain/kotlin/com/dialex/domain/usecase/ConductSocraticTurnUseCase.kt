package com.dialex.domain.usecase

import com.dialex.domain.model.SocraticTurnRequest
import com.dialex.domain.model.SocraticTurnResponse
import com.dialex.domain.repository.DiscussionRepository

class ConductSocraticTurnUseCase(
    private val repository: DiscussionRepository
) {
    suspend operator fun invoke(
        discussionId: String,
        request: SocraticTurnRequest
    ): SocraticTurnResponse {
        val trimmedMsg = request.message.trim()
        require(trimmedMsg.isNotEmpty()) { "Message cannot be empty" }
        require(discussionId.isNotEmpty()) { "Discussion ID cannot be empty" }
        return repository.socraticTurn(discussionId, request.copy(message = trimmedMsg))
    }
}
