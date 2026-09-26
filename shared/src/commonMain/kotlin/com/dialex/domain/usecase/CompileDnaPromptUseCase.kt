package com.dialex.domain.usecase

import com.dialex.domain.model.PersonaDNA
import com.dialex.domain.repository.PersonaRepository

class CompileDnaPromptUseCase(
    private val repository: PersonaRepository
) {
    suspend operator fun invoke(dna: PersonaDNA): Result<String> {
        return repository.compileDnaPrompt(dna)
    }
}
