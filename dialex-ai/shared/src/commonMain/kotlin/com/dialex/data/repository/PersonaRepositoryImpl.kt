package com.dialex.data.repository

import com.dialex.data.datasource.EngineDataSource
import com.dialex.domain.repository.PersonaRepository
import com.dialex.model.PredefinedPersona

class PersonaRepositoryImpl(private val dataSource: EngineDataSource) : PersonaRepository {
    override suspend fun getPersonas(): List<PredefinedPersona> = dataSource.listPersonas()

    override suspend fun createPersona(persona: PredefinedPersona): PredefinedPersona =
        dataSource.createPersona(persona)

    override suspend fun deletePersona(id: String) = dataSource.deletePersona(id)

    override suspend fun importPersonas(json: String): Int = dataSource.importPersonas(json)

    override suspend fun exportPersonas(): String = dataSource.exportPersonas()

    override suspend fun chatPersona(
        request: com.dialex.domain.model.PersonaChatRequest
    ): com.dialex.domain.model.PersonaChatResponse = dataSource.chatPersona(request)

    override suspend fun getPersonaDna(id: String): Result<com.dialex.domain.model.PersonaDNA> = runCatching {
        dataSource.getPersonaDna(id)
    }

    override suspend fun updatePersonaDna(id: String, dna: com.dialex.domain.model.PersonaDNA): Result<com.dialex.domain.model.PersonaDNA> = runCatching {
        dataSource.updatePersonaDna(id, dna)
    }

    override suspend fun listBuiltinHeuristics(): Result<List<com.dialex.domain.model.HeuristicRule>> = runCatching {
        dataSource.listBuiltinHeuristics()
    }

    override suspend fun compileDnaPrompt(dna: com.dialex.domain.model.PersonaDNA): Result<String> = runCatching {
        dataSource.compileDnaPrompt(dna)
    }

    override suspend fun importPersonaDna(content: String, format: String): Result<com.dialex.domain.model.PersonaDNA> = runCatching {
        dataSource.importPersonaDna(content, format)
    }

    override suspend fun exportPersonaDna(id: String, format: String): Result<String> = runCatching {
        dataSource.exportPersonaDna(id, format)
    }
}

