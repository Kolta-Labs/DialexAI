package com.dialex.domain.repository

import com.dialex.domain.model.HeuristicRule
import com.dialex.domain.model.PersonaChatRequest
import com.dialex.domain.model.PersonaChatResponse
import com.dialex.domain.model.PersonaDNA
import com.dialex.model.PredefinedPersona

/** Domain-layer contract for persona CRUD. Implemented in data layer; used by UseCases. */
interface PersonaRepository {
    suspend fun getPersonas(): List<PredefinedPersona>
    suspend fun createPersona(persona: PredefinedPersona): PredefinedPersona
    suspend fun deletePersona(id: String)
    /** Bulk-import from JSON string (exported by [exportPersonas]). Returns imported count. */
    suspend fun importPersonas(json: String): Int
    /** Exports all non-system personas as a JSON string. */
    suspend fun exportPersonas(): String
    /** Interactively chats with an agent model to generate or refine personas. */
    suspend fun chatPersona(request: PersonaChatRequest): PersonaChatResponse

    /** 8-Layer Persona DNA Operations */
    suspend fun getPersonaDna(id: String): Result<PersonaDNA>
    suspend fun updatePersonaDna(id: String, dna: PersonaDNA): Result<PersonaDNA>
    suspend fun listBuiltinHeuristics(): Result<List<HeuristicRule>>
    suspend fun compileDnaPrompt(dna: PersonaDNA): Result<String>
    suspend fun importPersonaDna(content: String, format: String = "yaml"): Result<PersonaDNA>
    suspend fun exportPersonaDna(id: String, format: String = "yaml"): Result<String>
}

