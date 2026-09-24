package com.dialex.domain.usecase

import com.dialex.domain.model.ProblemDecomposition
import com.dialex.domain.repository.DecompositionRepository

class DecomposeProblemUseCase(
    private val repository: DecompositionRepository
) {
    suspend operator fun invoke(
        topic: String,
        context: String = "",
        model: String? = null,
        provider: String? = null
    ): ProblemDecomposition {
        val trimmedTopic = topic.trim()
        require(trimmedTopic.isNotEmpty()) { "Topic cannot be empty" }
        return repository.decomposeProblem(trimmedTopic, context, model, provider)
    }
}
