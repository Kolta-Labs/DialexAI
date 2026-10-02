package com.dialex.domain.usecase

import com.dialex.domain.model.ConsensusResult
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage

/** Asks the engine whether the transcript so far satisfies the configured consensus rule. */
class EvaluateConsensusUseCase(
    private val discussionRepository: DiscussionRepository
) {
    suspend operator fun invoke(config: DebateConfig, transcript: List<DebateMessage>, round: Int? = null): ConsensusResult =
        discussionRepository.evaluateConsensus(config, transcript, round)
}
