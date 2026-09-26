package com.dialex.domain.usecase

import com.dialex.domain.model.PersonaDNA
import com.dialex.domain.repository.PersonaRepository

class ImportPersonaDnaUseCase(
    private val repository: PersonaRepository
) {
    suspend operator fun invoke(content: String, format: String = "yaml"): Result<PersonaDNA> {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("Import content cannot be empty"))
        return repository.importPersonaDna(trimmed, format)
    }
}
