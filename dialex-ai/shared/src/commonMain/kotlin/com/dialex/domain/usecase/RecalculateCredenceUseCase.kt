package com.dialex.domain.usecase

import com.dialex.domain.model.CredenceLedger
import com.dialex.domain.repository.CredenceRepository

class RecalculateCredenceUseCase(
    private val repository: CredenceRepository
) {
    suspend operator fun invoke(discussionId: String): Result<CredenceLedger> = runCatching {
        repository.recalculateCredence(discussionId)
    }
}
