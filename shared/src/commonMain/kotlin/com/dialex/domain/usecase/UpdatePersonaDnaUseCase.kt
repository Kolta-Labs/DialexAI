package com.dialex.domain.usecase

import com.dialex.domain.model.PersonaDNA
import com.dialex.domain.repository.PersonaRepository

class UpdatePersonaDnaUseCase(
    private val repository: PersonaRepository
) {
    suspend operator fun invoke(personaId: String, dna: PersonaDNA): Result<PersonaDNA> {
        val trimmed = personaId.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Persona ID cannot be empty"))
        return repository.updatePersonaDna(trimmed, dna)
    }
}
