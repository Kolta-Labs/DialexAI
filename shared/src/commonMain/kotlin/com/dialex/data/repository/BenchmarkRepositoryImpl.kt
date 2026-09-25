package com.dialex.data.repository

import com.dialex.data.datasource.EngineDataSource
import com.dialex.domain.model.BenchmarkCase
import com.dialex.domain.model.BenchmarkRun
import com.dialex.domain.model.BenchmarkSummary
import com.dialex.domain.model.RunBenchmarkRequest
import com.dialex.domain.repository.BenchmarkRepository

class BenchmarkRepositoryImpl(
    private val dataSource: EngineDataSource
) : BenchmarkRepository {

    override suspend fun listBenchmarkCases(): Result<List<BenchmarkCase>> = runCatching {
        dataSource.listBenchmarkCases()
    }

    override suspend fun createBenchmarkCase(case: BenchmarkCase): Result<BenchmarkCase> = runCatching {
        dataSource.createBenchmarkCase(case)
    }

    override suspend fun runBenchmark(request: RunBenchmarkRequest): Result<BenchmarkRun> = runCatching {
        dataSource.runBenchmark(request)
    }

    override suspend fun listBenchmarkRuns(): Result<List<BenchmarkRun>> = runCatching {
        dataSource.listBenchmarkRuns()
    }

    override suspend fun getBenchmarkRun(id: String): Result<BenchmarkRun> = runCatching {
        dataSource.getBenchmarkRun(id)
    }

    override suspend fun getBenchmarkSummary(): Result<BenchmarkSummary> = runCatching {
        dataSource.getBenchmarkSummary()
    }

    override suspend fun exportBenchmarks(format: String): Result<String> = runCatching {
        dataSource.exportBenchmarks(format)
    }
}
