package com.dialex.domain.usecase

import com.dialex.domain.model.BenchmarkCase
import com.dialex.domain.repository.BenchmarkRepository

class CreateBenchmarkCaseUseCase(
    private val repository: BenchmarkRepository
) {
    suspend operator fun invoke(case: BenchmarkCase): Result<BenchmarkCase> {
        require(case.title.isNotBlank()) { "Title cannot be blank" }
        require(case.dilemma.isNotBlank()) { "Dilemma cannot be blank" }
        return repository.createBenchmarkCase(case)
    }
}
