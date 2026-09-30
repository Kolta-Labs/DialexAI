package com.dialex.domain.usecase

import com.dialex.domain.model.BenchmarkCase
import com.dialex.domain.repository.BenchmarkRepository

class ListBenchmarkCasesUseCase(
    private val repository: BenchmarkRepository
) {
    suspend operator fun invoke(): Result<List<BenchmarkCase>> {
        return repository.listBenchmarkCases()
    }
}
