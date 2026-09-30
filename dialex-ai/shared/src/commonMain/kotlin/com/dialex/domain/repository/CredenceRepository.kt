package com.dialex.domain.repository

import com.dialex.domain.model.CredenceLedger

interface CredenceRepository {
    suspend fun getCredenceLedger(discussionId: String): CredenceLedger
    suspend fun recalculateCredence(discussionId: String): CredenceLedger
}
