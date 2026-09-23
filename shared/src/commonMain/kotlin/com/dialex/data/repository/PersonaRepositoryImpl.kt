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
}

