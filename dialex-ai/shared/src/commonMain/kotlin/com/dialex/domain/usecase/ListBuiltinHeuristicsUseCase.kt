package com.dialex.domain.usecase

import com.dialex.domain.model.HeuristicRule
import com.dialex.domain.repository.PersonaRepository

class ListBuiltinHeuristicsUseCase(
    private val repository: PersonaRepository
) {
    suspend operator fun invoke(): Result<List<HeuristicRule>> {
        return repository.listBuiltinHeuristics()
    }
}
