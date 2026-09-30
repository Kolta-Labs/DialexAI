package com.dialex.data.repository

import com.dialex.data.datasource.LegalConsentLocalDataSource
import com.dialex.domain.model.LegalConsent
import com.dialex.domain.repository.LegalConsentRepository
import kotlinx.coroutines.flow.Flow

class LegalConsentRepositoryImpl(
    private val dataSource: LegalConsentLocalDataSource
) : LegalConsentRepository {

    override suspend fun getLegalConsent(): LegalConsent =
        dataSource.getConsent()

    override fun observeLegalConsent(): Flow<LegalConsent> =
        dataSource.observeConsent()

    override suspend fun recordConsent(termsVersion: String, privacyVersion: String, timestampMs: Long) {
        dataSource.recordConsent(termsVersion, privacyVersion, timestampMs)
    }

    override suspend fun isConsentRequired(currentTermsVersion: String): Boolean {
        val consent = dataSource.getConsent()
        return !consent.isAccepted || consent.termsVersion != currentTermsVersion
    }
}
