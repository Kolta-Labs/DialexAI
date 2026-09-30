package com.dialex.domain.model

/**
 * Encapsulates the user's legal consent record for Terms of Service and Privacy Policy.
 */
data class LegalConsent(
    val isAccepted: Boolean,
    val termsVersion: String,
    val privacyVersion: String,
    val acceptedAtTimestamp: Long
) {
    companion object {
        const val DEFAULT_ID = "terms_and_privacy"
        const val CURRENT_LEGAL_VERSION = "1.0.0"

        val NotAccepted = LegalConsent(
            isAccepted = false,
            termsVersion = "",
            privacyVersion = "",
            acceptedAtTimestamp = 0L
        )
    }
}
