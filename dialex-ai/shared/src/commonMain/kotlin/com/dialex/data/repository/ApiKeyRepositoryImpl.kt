package com.dialex.data.repository

import com.dialex.data.datasource.SecureKeyDataSource
import com.dialex.domain.model.ProfileApiKeysStatus
import com.dialex.domain.repository.ApiKeyRepository
import com.dialex.model.Provider

class ApiKeyRepositoryImpl(
    private val dataSource: SecureKeyDataSource
) : ApiKeyRepository {
    override suspend fun getApiKeyStatuses(profileId: String): ProfileApiKeysStatus =
        dataSource.getApiKeyStatuses(profileId)

    override suspend fun setApiKey(profileId: String, provider: Provider, key: String) =
        dataSource.storeKey(profileId, provider, key)

    override suspend fun removeApiKey(profileId: String, provider: Provider) =
        dataSource.removeKey(profileId, provider)

    override suspend fun getRawApiKey(profileId: String, provider: Provider): String? =
        dataSource.getRawKey(profileId, provider)
}
