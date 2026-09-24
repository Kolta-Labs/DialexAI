package com.dialex.data.repository

import com.dialex.data.datasource.EngineDataSource
import com.dialex.domain.model.ProblemDecomposition
import com.dialex.domain.repository.DecompositionRepository

class DecompositionRepositoryImpl(
    private val dataSource: EngineDataSource
) : DecompositionRepository {

    override suspend fun decomposeProblem(
        topic: String,
        context: String,
        model: String?,
        provider: String?
    ): ProblemDecomposition {
        return dataSource.decomposeProblem(topic, context, model, provider)
    }
}
