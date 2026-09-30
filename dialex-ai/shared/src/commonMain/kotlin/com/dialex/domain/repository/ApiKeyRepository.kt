package com.dialex.domain.repository

import com.dialex.domain.model.ApiKeyStatus
import com.dialex.domain.model.ProfileApiKeysStatus
import com.dialex.model.Provider

interface ApiKeyRepository {
    /** Safe write-only statuses for UI display */
    suspend fun getApiKeyStatuses(profileId: String): ProfileApiKeysStatus

    /** Sets/replaces a key. Ciphertext is saved; original key is discarded from memory. */
    suspend fun setApiKey(profileId: String, provider: Provider, key: String)

    /** Removes a key from secure storage. */
    suspend fun removeApiKey(profileId: String, provider: Provider)

    /**
     * Decrypts and returns the raw key strictly for executing AI calls.
     * Never forward to Presentation layer / UI models.
     */
    suspend fun getRawApiKey(profileId: String, provider: Provider): String?
}
