package com.dialex.domain.repository

import com.dialex.domain.model.ProblemDecomposition

interface DecompositionRepository {
    suspend fun decomposeProblem(
        topic: String,
        context: String = "",
        model: String? = null,
        provider: String? = null
    ): ProblemDecomposition
}
