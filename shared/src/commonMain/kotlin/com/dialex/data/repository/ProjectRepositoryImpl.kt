package com.dialex.data.repository

import com.dialex.data.datasource.EngineDataSource
import com.dialex.domain.repository.ProjectRepository
import com.dialex.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ProjectRepositoryImpl(private val dataSource: EngineDataSource) : ProjectRepository {
    private val _projectsFlow = MutableStateFlow<List<Project>>(emptyList())

    override fun observeProjects(): Flow<List<Project>> = _projectsFlow.asStateFlow()

    override suspend fun getProjects(): List<Project> {
        val list = dataSource.listProjects()
        _projectsFlow.value = list
        return list
    }

    override suspend fun createProject(name: String): Project {
        val created = dataSource.createProject(name)
        _projectsFlow.value = (_projectsFlow.value + created).distinctBy { it.id }
        return created
    }

    override suspend fun updateProject(project: Project): Project {
        val updated = dataSource.updateProject(project)
        _projectsFlow.value = _projectsFlow.value.map { if (it.id == updated.id) updated else it }
        return updated
    }

    override suspend fun deleteProject(id: String) {
        dataSource.deleteProject(id)
        _projectsFlow.value = _projectsFlow.value.filter { it.id != id }
    }
}
