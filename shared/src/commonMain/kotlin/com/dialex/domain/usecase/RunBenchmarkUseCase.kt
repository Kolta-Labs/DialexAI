package com.dialex.domain.usecase

import com.dialex.domain.model.BenchmarkRun
import com.dialex.domain.model.RunBenchmarkRequest
import com.dialex.domain.repository.BenchmarkRepository

class RunBenchmarkUseCase(
    private val repository: BenchmarkRepository
) {
    suspend operator fun invoke(request: RunBenchmarkRequest): Result<BenchmarkRun> {
        require(request.caseId.isNotBlank()) { "Case ID cannot be blank" }
        return repository.runBenchmark(request)
    }
}
