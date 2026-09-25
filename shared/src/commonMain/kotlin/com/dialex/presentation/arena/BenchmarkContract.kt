package com.dialex.presentation.arena

import com.dialex.domain.model.BenchmarkCase
import com.dialex.domain.model.BenchmarkRun
import com.dialex.domain.model.BenchmarkSummary
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class BenchmarkState(
    val cases: ImmutableList<BenchmarkCase> = persistentListOf(),
    val selectedCase: BenchmarkCase? = null,
    val historicalRuns: ImmutableList<BenchmarkRun> = persistentListOf(),
    val summary: BenchmarkSummary? = null,
    val activeRun: BenchmarkRun? = null,
    val isRunning: Boolean = false,
    val runProgress: Float = 0f,
    val activePhase: String = "",
    val error: String? = null
)

sealed interface BenchmarkIntent {
    data object LoadInitialData : BenchmarkIntent
    data class SelectCase(val caseId: String) : BenchmarkIntent
    data class CreateCustomCase(val newCase: BenchmarkCase) : BenchmarkIntent
    data class StartBenchmarkRun(val caseId: String, val rounds: Int = 2) : BenchmarkIntent
    data class SelectRun(val runId: String) : BenchmarkIntent
    data class ExportResults(val format: String = "markdown") : BenchmarkIntent
    data object DismissError : BenchmarkIntent
}

sealed interface BenchmarkEffect {
    data class ShowSnackbar(val message: String) : BenchmarkEffect
    data class ExportDownloaded(val content: String, val format: String) : BenchmarkEffect
}
