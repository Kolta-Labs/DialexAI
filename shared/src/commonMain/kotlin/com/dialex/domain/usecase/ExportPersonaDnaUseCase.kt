package com.dialex.domain.usecase

import com.dialex.domain.repository.PersonaRepository

class ExportPersonaDnaUseCase(
    private val repository: PersonaRepository
) {
    suspend operator fun invoke(personaId: String, format: String = "yaml"): Result<String> {
        val trimmed = personaId.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Persona ID cannot be empty"))
        return repository.exportPersonaDna(trimmed, format)
    }
}
