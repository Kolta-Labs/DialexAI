package com.dialex.domain.usecase

import com.dialex.domain.model.LegalConsent
import com.dialex.domain.repository.LegalConsentRepository

class RecordLegalConsentUseCase(
    private val repository: LegalConsentRepository
) {
    suspend operator fun invoke(
        termsVersion: String = LegalConsent.CURRENT_LEGAL_VERSION,
        privacyVersion: String = LegalConsent.CURRENT_LEGAL_VERSION,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        repository.recordConsent(termsVersion, privacyVersion, timestampMs)
    }
}
