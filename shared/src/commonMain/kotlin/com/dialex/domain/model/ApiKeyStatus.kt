package com.dialex.domain.model

import com.dialex.model.Provider
import kotlinx.serialization.Serializable

/**
 * Safe write-only representation of an API key.
 * The plaintext key itself is never exposed to the UI or exported.
 */
@Serializable
data class ApiKeyStatus(
    val provider: Provider,
    val isConfigured: Boolean,
    val maskedHint: String? = null
)

@Serializable
data class ProfileApiKeysStatus(
    val statuses: Map<Provider, ApiKeyStatus> = emptyMap()
) {
    fun isConfigured(provider: Provider): Boolean =
        statuses[provider]?.isConfigured == true
}
