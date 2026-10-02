package com.dialex.domain.usecase

import com.dialex.domain.model.DomainException
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.model.DebateConfig

/** Turns [config] into a one-key council for the given engine quickstart mode id. */
class ApplyQuickstartModeUseCase(
    private val discussionRepository: DiscussionRepository
) {
    suspend operator fun invoke(modeId: String, config: DebateConfig): DebateConfig {
        if (modeId.isBlank()) throw DomainException.InvalidRequest("Quickstart mode is required")
        return discussionRepository.applyQuickstart(modeId.trim(), config)
    }
}
