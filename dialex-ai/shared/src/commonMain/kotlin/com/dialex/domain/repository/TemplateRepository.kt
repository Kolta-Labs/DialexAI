package com.dialex.domain.repository

import com.dialex.model.CouncilTemplate

/**
 * Domain-layer repository contract for one-click council templates / archetypes.
 * Manages both built-in system archetypes and user-saved custom archetypes.
 */
interface TemplateRepository {
    suspend fun getTemplates(): List<CouncilTemplate>
    suspend fun saveTemplate(template: CouncilTemplate): CouncilTemplate
    suspend fun deleteTemplate(id: String)
}
