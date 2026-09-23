package com.dialex.data.datasource

import com.dialex.data.db.SqlDatabase
import com.dialex.model.CouncilTemplate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TemplateLocalDataSource(private val db: SqlDatabase) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun getCustomTemplates(): List<CouncilTemplate> {
        val sql = """
            SELECT template_json
            FROM council_templates
            WHERE is_deleted = 0
            ORDER BY created_at DESC
        """.trimIndent()

        return db.query(sql) { cursor ->
            val jsonStr = cursor.getString(0) ?: return@query null
            runCatching { json.decodeFromString<CouncilTemplate>(jsonStr) }.getOrNull()
        }.filterNotNull()
    }

    fun saveCustomTemplate(template: CouncilTemplate): CouncilTemplate {
        val now = System.currentTimeMillis()
        val templateToSave = template.copy(
            isCustom = true,
            createdAt = if (template.createdAt <= 0L) now else template.createdAt
        )
        val jsonStr = json.encodeToString(templateToSave)

        val sql = """
            INSERT OR REPLACE INTO council_templates (
                id, title, subtitle, template_json, created_at, updated_at, is_deleted
            ) VALUES (
                ?, ?, ?, ?,
                COALESCE((SELECT created_at FROM council_templates WHERE id = ?), ?),
                ?, 0
            )
        """.trimIndent()

        db.exec(
            sql,
            listOf(
                templateToSave.id,
                templateToSave.title,
                templateToSave.subtitle,
                jsonStr,
                templateToSave.id,
                now,
                now
            )
        )
        return templateToSave
    }

    fun deleteCustomTemplate(id: String) {
        val now = System.currentTimeMillis()
        val sql = "UPDATE council_templates SET is_deleted = 1, updated_at = ? WHERE id = ?"
        db.exec(sql, listOf(now, id))
    }
}
