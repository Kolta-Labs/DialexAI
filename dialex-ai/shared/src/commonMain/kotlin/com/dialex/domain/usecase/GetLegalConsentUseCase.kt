package com.dialex.domain.usecase

import com.dialex.domain.model.LegalConsent
import com.dialex.domain.repository.LegalConsentRepository
import kotlinx.coroutines.flow.Flow

class GetLegalConsentUseCase(
    private val repository: LegalConsentRepository
) {
    suspend operator fun invoke(): LegalConsent =
        repository.getLegalConsent()

    fun observe(): Flow<LegalConsent> =
        repository.observeLegalConsent()

    suspend fun isConsentRequired(currentVersion: String = LegalConsent.CURRENT_LEGAL_VERSION): Boolean =
        repository.isConsentRequired(currentVersion)
}
