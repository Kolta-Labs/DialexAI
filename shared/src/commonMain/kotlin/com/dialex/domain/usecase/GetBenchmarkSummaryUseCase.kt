package com.dialex.domain.usecase

import com.dialex.domain.model.BenchmarkSummary
import com.dialex.domain.repository.BenchmarkRepository

class GetBenchmarkSummaryUseCase(
    private val repository: BenchmarkRepository
) {
    suspend operator fun invoke(): Result<BenchmarkSummary> {
        return repository.getBenchmarkSummary()
    }
}
