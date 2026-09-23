package com.dialex.data.repository

import com.dialex.data.datasource.LocalDatabaseSource
import com.dialex.domain.model.SyncMetadata
import com.dialex.domain.repository.LocalStoreRepository
import com.dialex.model.Discussion
import com.dialex.model.Project
import kotlinx.coroutines.flow.Flow

class LocalStoreRepositoryImpl(
    private val dataSource: LocalDatabaseSource
) : LocalStoreRepository {
    override suspend fun getProjects(profileId: String): List<Project> =
        dataSource.getProjects(profileId)

    override suspend fun getProject(profileId: String, id: String): Project? =
        dataSource.getProject(profileId, id)

    override suspend fun saveProject(profileId: String, project: Project): Project =
        dataSource.saveProject(profileId, project)

    override suspend fun deleteProject(profileId: String, id: String) =
        dataSource.deleteProject(profileId, id)

    override suspend fun getDiscussions(profileId: String, projectId: String?): List<Discussion> =
        dataSource.getDiscussions(profileId, projectId)

    override suspend fun getDiscussion(profileId: String, id: String): Discussion? =
        dataSource.getDiscussion(profileId, id)

    override suspend fun saveDiscussion(profileId: String, discussion: Discussion): Discussion =
        dataSource.saveDiscussion(profileId, discussion)

    override suspend fun deleteDiscussion(profileId: String, id: String) =
        dataSource.deleteDiscussion(profileId, id)

    override fun observeDiscussions(profileId: String): Flow<List<Discussion>> =
        dataSource.observeDiscussions(profileId)

    override suspend fun getDirtyEntities(profileId: String): List<SyncMetadata> =
        dataSource.getDirtyEntities(profileId)

    override suspend fun markSynced(profileId: String, entityId: String, version: Long, updatedAt: Long) =
        dataSource.markSynced(profileId, entityId, version, updatedAt)
}
