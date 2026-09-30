package com.dialex.data.repository

import com.dialex.data.datasource.EngineDataSource
import com.dialex.domain.model.CredenceLedger
import com.dialex.domain.repository.CredenceRepository

class CredenceRepositoryImpl(
    private val dataSource: EngineDataSource
) : CredenceRepository {
    override suspend fun getCredenceLedger(discussionId: String): CredenceLedger =
        dataSource.getCredenceLedger(discussionId)

    override suspend fun recalculateCredence(discussionId: String): CredenceLedger =
        dataSource.recalculateCredence(discussionId)
}
