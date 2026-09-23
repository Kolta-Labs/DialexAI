package com.dialex.data.repository

import com.dialex.domain.repository.PersonaRepository
import com.dialex.model.PredefinedPersona
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * In-memory / file-backed persona repository for standalone tools and testing.
 * Allows managing, importing, exporting, and saving personas without a running backend engine.
 */
class LocalFilePersonaRepository(
    initialPersonas: List<PredefinedPersona> = emptyList(),
    private val onPersist: ((List<PredefinedPersona>) -> Unit)? = null,
) : PersonaRepository {
    private val personas = initialPersonas.toMutableList()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun getPersonas(): List<PredefinedPersona> = synchronized(personas) {
        personas.toList()
    }

    override suspend fun createPersona(persona: PredefinedPersona): PredefinedPersona = synchronized(personas) {
        val existingIndex = personas.indexOfFirst { it.id == persona.id }
        if (existingIndex >= 0) {
            personas[existingIndex] = persona
        } else {
            personas.add(persona)
        }
        onPersist?.invoke(personas.toList())
        persona
    }

    override suspend fun deletePersona(id: String): Unit = synchronized(personas) {
        personas.removeAll { it.id == id }
        onPersist?.invoke(personas.toList())
    }

    override suspend fun importPersonas(json: String): Int = synchronized(personas) {
        val imported = this.json.decodeFromString<List<PredefinedPersona>>(json)
        var count = 0
        for (item in imported) {
            val idx = personas.indexOfFirst { it.id == item.id }
            if (idx >= 0) {
                personas[idx] = item
            } else {
                personas.add(item)
            }
            count++
        }
        onPersist?.invoke(personas.toList())
        count
    }

    override suspend fun exportPersonas(): String = synchronized(personas) {
        json.encodeToString(personas.toList())
    }

    override suspend fun chatPersona(
        request: com.dialex.domain.model.PersonaChatRequest
    ): com.dialex.domain.model.PersonaChatResponse {
        val lastUserMsg = request.messages.lastOrNull { it.role.equals("user", ignoreCase = true) }?.content.orEmpty()
        val cleaned = lastUserMsg.trim()
        val genName = cleaned.take(40).ifBlank { "Custom Architect" }
            .replace(Regex("[^a-zA-Z0-9 ]"), "").trim().split(" ")
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        val draft = request.currentDraft
        val generated = PredefinedPersona(
            id = "custom_" + kotlin.random.Random.nextInt(10000, 99999),
            name = if (draft != null && draft.name.isNotBlank()) draft.name else genName,
            category = if (draft != null && draft.category.isNotBlank()) draft.category else "Software Engineering",
            role = if (draft != null && draft.role.isNotBlank()) draft.role else "Specialist Advisor",
            description = "AI persona created for dialectical debate: $cleaned",
            roleAndPersona = "Expert practitioner focused on rigorous analysis of: $cleaned",
            coreExpertise = "First-principles reasoning, trade-off analysis, systematic verification",
            toneAndVoice = "Objective, incisive, and structured",
            objective = "Challenge hidden assumptions and evaluate alternatives",
            caveman = cleaned.contains("caveman", ignoreCase = true) || (draft?.caveman == true),
            ponytail = cleaned.contains("ponytail", ignoreCase = true) || (draft?.ponytail == true),
            systemPrompt = com.dialex.model.buildComposedSystemPrompt(
                roleAndPersona = "Expert practitioner focused on rigorous analysis of: $cleaned",
                coreExpertise = "First-principles reasoning, trade-off analysis, systematic verification",
                toneAndVoice = "Objective, incisive, and structured",
                objective = "Challenge hidden assumptions and evaluate alternatives"
            )
        )
        val jsonStr = json.encodeToString(generated)
        val reply = "Here is a drafted persona based on your request:\n\n```json\n$jsonStr\n```\n\nYou can click **Load into Editor** below to apply it to your form!"
        return com.dialex.domain.model.PersonaChatResponse(
            reply = reply,
            parsedPersona = generated
        )
    }
}

