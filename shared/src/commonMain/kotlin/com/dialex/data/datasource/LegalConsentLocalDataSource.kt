package com.dialex.data.datasource

import com.dialex.data.db.SqlDatabase
import com.dialex.domain.model.LegalConsent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class LegalConsentLocalDataSource(private val db: SqlDatabase) {
    private val consentFlow = MutableStateFlow<LegalConsent>(LegalConsent.NotAccepted)

    init {
        refreshFlow()
    }

    fun getConsent(): LegalConsent {
        val sql = "SELECT terms_version, privacy_version, accepted_at FROM legal_consent WHERE id = ? LIMIT 1"
        val consent = db.querySingle(sql, listOf(LegalConsent.DEFAULT_ID)) { cursor ->
            LegalConsent(
                isAccepted = true,
                termsVersion = cursor.getString(0) ?: "",
                privacyVersion = cursor.getString(1) ?: "",
                acceptedAtTimestamp = cursor.getLong(2)
            )
        } ?: LegalConsent.NotAccepted
        return consent
    }

    fun observeConsent(): Flow<LegalConsent> {
        refreshFlow()
        return consentFlow.asStateFlow()
    }

    fun recordConsent(termsVersion: String, privacyVersion: String, timestampMs: Long) {
        val sql = """
            INSERT OR REPLACE INTO legal_consent (id, terms_version, privacy_version, accepted_at)
            VALUES (?, ?, ?, ?)
        """.trimIndent()
        db.exec(sql, listOf(LegalConsent.DEFAULT_ID, termsVersion, privacyVersion, timestampMs))
        refreshFlow()
    }

    private fun refreshFlow() {
        consentFlow.value = getConsent()
    }
}
