package com.dialex.domain.repository

import com.dialex.domain.model.BenchmarkCase
import com.dialex.domain.model.BenchmarkRun
import com.dialex.domain.model.BenchmarkSummary
import com.dialex.domain.model.RunBenchmarkRequest

interface BenchmarkRepository {
    suspend fun listBenchmarkCases(): Result<List<BenchmarkCase>>
    suspend fun createBenchmarkCase(case: BenchmarkCase): Result<BenchmarkCase>
    suspend fun runBenchmark(request: RunBenchmarkRequest): Result<BenchmarkRun>
    suspend fun listBenchmarkRuns(): Result<List<BenchmarkRun>>
    suspend fun getBenchmarkRun(id: String): Result<BenchmarkRun>
    suspend fun getBenchmarkSummary(): Result<BenchmarkSummary>
    suspend fun exportBenchmarks(format: String = "markdown"): Result<String>
}
