package com.dialex.domain.repository

import com.dialex.domain.model.SyncMetadata
import com.dialex.model.Discussion
import com.dialex.model.Project
import kotlinx.coroutines.flow.Flow

interface LocalStoreRepository {
    // Projects
    suspend fun getProjects(profileId: String): List<Project>
    suspend fun getProject(profileId: String, id: String): Project?
    suspend fun saveProject(profileId: String, project: Project): Project
    suspend fun deleteProject(profileId: String, id: String)

    // Discussions
    suspend fun getDiscussions(profileId: String, projectId: String? = null): List<Discussion>
    suspend fun getDiscussion(profileId: String, id: String): Discussion?
    suspend fun saveDiscussion(profileId: String, discussion: Discussion): Discussion
    suspend fun deleteDiscussion(profileId: String, id: String)
    fun observeDiscussions(profileId: String): Flow<List<Discussion>>

    // Sync Foundation
    suspend fun getDirtyEntities(profileId: String): List<SyncMetadata>
    suspend fun markSynced(profileId: String, entityId: String, version: Long, updatedAt: Long)
}
