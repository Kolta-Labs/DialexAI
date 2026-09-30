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

    override suspend fun getPersonaDna(id: String): Result<com.dialex.domain.model.PersonaDNA> = synchronized(personas) {
        val p = personas.find { it.id == id }
        if (p?.dna != null) {
            Result.success(p.dna)
        } else if (p != null) {
            Result.success(
                com.dialex.domain.model.PersonaDNA(
                    id = p.id,
                    name = p.name,
                    role = p.role,
                    category = p.category,
                    icon = p.icon,
                    coreIdentity = com.dialex.domain.model.CoreIdentity(title = p.role, background = p.roleAndPersona, domainAuthority = p.coreExpertise),
                    communicationVector = com.dialex.domain.model.CommunicationVector(tone = p.toneAndVoice),
                    rawCustomPrompt = p.systemPrompt
                )
            )
        } else {
            Result.failure(NoSuchElementException("Persona not found: $id"))
        }
    }

    override suspend fun updatePersonaDna(id: String, dna: com.dialex.domain.model.PersonaDNA): Result<com.dialex.domain.model.PersonaDNA> = synchronized(personas) {
        val idx = personas.indexOfFirst { it.id == id }
        if (idx >= 0) {
            personas[idx] = personas[idx].copy(dna = dna)
            onPersist?.invoke(personas.toList())
            Result.success(dna)
        } else {
            val created = PredefinedPersona(
                id = id,
                name = dna.name,
                role = dna.role,
                category = dna.category,
                icon = dna.icon,
                dna = dna
            )
            personas.add(created)
            onPersist?.invoke(personas.toList())
            Result.success(dna)
        }
    }

    override suspend fun listBuiltinHeuristics(): Result<List<com.dialex.domain.model.HeuristicRule>> {
        return Result.success(
            listOf(
                com.dialex.domain.model.HeuristicRule("heur_gall", "Gall's Law", "A complex system that works is invariably found to have evolved from a simple system that worked.", "", "Challenge full rewrites; demand incremental paths."),
                com.dialex.domain.model.HeuristicRule("heur_conway", "Conway's Law", "Organizations design systems that mirror their own communication structures.", "", "Flag team alignment mismatches."),
                com.dialex.domain.model.HeuristicRule("heur_chesterton", "Chesterton's Fence", "Do not remove a constraint until you understand why it was put there.", "", "Protect legacy constraints."),
                com.dialex.domain.model.HeuristicRule("heur_cap", "CAP & PACELC Theorem", "Choose between Consistency and Availability under partition.", "", "Expose partition trade-offs.")
            )
        )
    }

    override suspend fun compileDnaPrompt(dna: com.dialex.domain.model.PersonaDNA): Result<String> {
        val prompt = "### [COGNITIVE DNA MANDATE: ${dna.name.uppercase()}]\n• Role: ${dna.role}\n• Epistemic Bias: ${dna.epistemicBias.primaryMode}"
        return Result.success(prompt)
    }

    override suspend fun importPersonaDna(content: String, format: String): Result<com.dialex.domain.model.PersonaDNA> = runCatching {
        json.decodeFromString<com.dialex.domain.model.PersonaDNA>(content)
    }

    override suspend fun exportPersonaDna(id: String, format: String): Result<String> = synchronized(personas) {
        val p = personas.find { it.id == id }
        val dna = p?.dna ?: com.dialex.domain.model.PersonaDNA(id = id, name = p?.name ?: "Unknown", role = p?.role ?: "Expert")
        runCatching { json.encodeToString(dna) }
    }
}

