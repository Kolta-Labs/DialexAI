package com.dialex.domain.repository

import com.dialex.model.Project
import kotlinx.coroutines.flow.Flow

/** Domain-layer contract for project operations. Implemented in data layer; used by UseCases. */
interface ProjectRepository {
    fun observeProjects(): Flow<List<Project>>
    suspend fun getProjects(): List<Project>
    suspend fun createProject(name: String): Project
    suspend fun updateProject(project: Project): Project
    suspend fun deleteProject(id: String)
}
