package com.dialex.data.repository

import com.dialex.data.datasource.TemplateLocalDataSource
import com.dialex.domain.repository.TemplateRepository
import com.dialex.model.CouncilTemplate
import com.dialex.presentation.setup.CouncilTemplates

class TemplateRepositoryImpl(
    private val localDataSource: TemplateLocalDataSource
) : TemplateRepository {

    override suspend fun getTemplates(): List<CouncilTemplate> {
        val builtIn = CouncilTemplates.all.map { it.copy(isCustom = false) }
        val custom = localDataSource.getCustomTemplates()
        return builtIn + custom
    }

    override suspend fun saveTemplate(template: CouncilTemplate): CouncilTemplate {
        return localDataSource.saveCustomTemplate(template)
    }

    override suspend fun deleteTemplate(id: String) {
        localDataSource.deleteCustomTemplate(id)
    }
}
