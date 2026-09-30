package com.dialex.domain.repository

import com.dialex.domain.model.LegalConsent
import kotlinx.coroutines.flow.Flow

interface LegalConsentRepository {
    suspend fun getLegalConsent(): LegalConsent
    fun observeLegalConsent(): Flow<LegalConsent>
    suspend fun recordConsent(termsVersion: String, privacyVersion: String, timestampMs: Long = System.currentTimeMillis())
    suspend fun isConsentRequired(currentTermsVersion: String = LegalConsent.CURRENT_LEGAL_VERSION): Boolean
}
