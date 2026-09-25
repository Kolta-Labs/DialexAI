package com.dialex.presentation.arena

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dialex.domain.model.BenchmarkCase
import com.dialex.domain.model.RunBenchmarkRequest
import com.dialex.domain.repository.BenchmarkRepository
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BenchmarkViewModel(
    private val repository: BenchmarkRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BenchmarkState())
    val state: StateFlow<BenchmarkState> = _state.asStateFlow()

    private val _effect = Channel<BenchmarkEffect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        onIntent(BenchmarkIntent.LoadInitialData)
    }

    fun onIntent(intent: BenchmarkIntent) {
        when (intent) {
            is BenchmarkIntent.LoadInitialData -> loadInitialData()
            is BenchmarkIntent.SelectCase -> selectCase(intent.caseId)
            is BenchmarkIntent.CreateCustomCase -> createCustomCase(intent.newCase)
            is BenchmarkIntent.StartBenchmarkRun -> startBenchmarkRun(intent.caseId, intent.rounds)
            is BenchmarkIntent.SelectRun -> selectRun(intent.runId)
            is BenchmarkIntent.ExportResults -> exportResults(intent.format)
            is BenchmarkIntent.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            val casesResult = repository.listBenchmarkCases()
            val runsResult = repository.listBenchmarkRuns()
            val summaryResult = repository.getBenchmarkSummary()

            _state.update { current ->
                val cases = casesResult.getOrDefault(emptyList()).toImmutableList()
                val runs = runsResult.getOrDefault(emptyList()).toImmutableList()
                val summary = summaryResult.getOrNull()
                val selectedCase = current.selectedCase ?: cases.firstOrNull()
                val activeRun = current.activeRun ?: runs.firstOrNull()

                current.copy(
                    cases = cases,
                    selectedCase = selectedCase,
                    historicalRuns = runs,
                    summary = summary,
                    activeRun = activeRun
                )
            }
        }
    }

    private fun selectCase(caseId: String) {
        _state.update { current ->
            val found = current.cases.firstOrNull { it.id == caseId }
            current.copy(selectedCase = found)
        }
    }

    private fun createCustomCase(newCase: BenchmarkCase) {
        viewModelScope.launch {
            repository.createBenchmarkCase(newCase).fold(
                onSuccess = { created ->
                    _effect.send(BenchmarkEffect.ShowSnackbar("Created benchmark case: ${created.title}"))
                    loadInitialData()
                },
                onFailure = { error ->
                    _state.update { it.copy(error = error.message ?: "Failed to create case") }
                }
            )
        }
    }

    private fun startBenchmarkRun(caseId: String, rounds: Int) {
        if (_state.value.isRunning) return

        _state.update {
            it.copy(
                isRunning = true,
                runProgress = 0.1f,
                activePhase = "Executing Dual-Arm Deliberation (Solo vs Council)...",
                error = null
            )
        }

        viewModelScope.launch {
            repository.runBenchmark(RunBenchmarkRequest(caseId = caseId, rounds = rounds)).fold(
                onSuccess = { run ->
                    _state.update { current ->
                        val updatedRuns = (listOf(run) + current.historicalRuns).toImmutableList()
                        current.copy(
                            isRunning = false,
                            runProgress = 1.0f,
                            activePhase = "Completed",
                            activeRun = run,
                            historicalRuns = updatedRuns
                        )
                    }
                    // Refresh summary stats
                    repository.getBenchmarkSummary().onSuccess { summary ->
                        _state.update { it.copy(summary = summary) }
                    }
                    _effect.send(BenchmarkEffect.ShowSnackbar("Benchmark finished: Winner is ${run.winner} (Delta Q: ${run.deltaQ})"))
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isRunning = false,
                            error = error.message ?: "Benchmark evaluation failed"
                        )
                    }
                }
            )
        }
    }

    private fun selectRun(runId: String) {
        _state.update { current ->
            val found = current.historicalRuns.firstOrNull { it.id == runId }
            current.copy(activeRun = found)
        }
    }

    private fun exportResults(format: String) {
        viewModelScope.launch {
            repository.exportBenchmarks(format).fold(
                onSuccess = { content ->
                    _effect.send(BenchmarkEffect.ExportDownloaded(content, format))
                },
                onFailure = { error ->
                    _state.update { it.copy(error = error.message ?: "Export failed") }
                }
            )
        }
    }
}
