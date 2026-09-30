package com.dialex.presentation.arena

import com.dialex.domain.model.ArmResult
import com.dialex.domain.model.ArmType
import com.dialex.domain.model.BenchmarkCase
import com.dialex.domain.model.BenchmarkRun
import com.dialex.domain.model.BenchmarkSummary
import com.dialex.domain.model.JudgeEvaluation
import com.dialex.domain.model.MetricDimension
import com.dialex.domain.model.MetricScore
import com.dialex.domain.model.RunBenchmarkRequest
import com.dialex.domain.repository.BenchmarkRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeBenchmarkRepository : BenchmarkRepository {
    val cases = mutableListOf<BenchmarkCase>()
    val runs = mutableListOf<BenchmarkRun>()
    var summary = BenchmarkSummary(
        totalRuns = 0,
        councilWins = 0,
        soloWins = 0,
        ties = 0,
        councilWinRate = 0.0,
        meanDeltaQ = 1.85,
        pValue = 0.042,
        isStatSignificant = true
    )

    override suspend fun listBenchmarkCases(): Result<List<BenchmarkCase>> {
        return Result.success(cases.toList())
    }

    override suspend fun createBenchmarkCase(case: BenchmarkCase): Result<BenchmarkCase> {
        cases.add(case)
        return Result.success(case)
    }

    override suspend fun runBenchmark(request: RunBenchmarkRequest): Result<BenchmarkRun> {
        val run = BenchmarkRun(
            id = "run-test-1",
            caseId = request.caseId,
            caseTitle = "Test Dilemma",
            timestamp = 1727318400000L,
            soloResult = ArmResult(ArmType.SOLO_BASELINE, "Solo Model", "Solo deliverable"),
            councilResult = ArmResult(ArmType.COUNCIL, "Council 3 Agents", "Council deliverable"),
            evaluations = listOf(
                JudgeEvaluation(
                    passNumber = 1,
                    order = "AB",
                    soloScores = listOf(MetricScore(MetricDimension.FACTUALITY, 7.0)),
                    councilScores = listOf(MetricScore(MetricDimension.FACTUALITY, 9.0)),
                    overallVerdict = "Council wins due to superior rigor"
                )
            ),
            soloTotalScore = 7.0,
            councilTotalScore = 9.0,
            deltaQ = 2.0,
            winner = "COUNCIL"
        )
        runs.add(0, run)
        summary = summary.copy(
            totalRuns = runs.size,
            councilWins = summary.councilWins + 1,
            councilWinRate = 100.0
        )
        return Result.success(run)
    }

    override suspend fun listBenchmarkRuns(): Result<List<BenchmarkRun>> {
        return Result.success(runs.toList())
    }

    override suspend fun getBenchmarkRun(id: String): Result<BenchmarkRun> {
        val found = runs.find { it.id == id }
        return if (found != null) Result.success(found) else Result.failure(NoSuchElementException("Run not found"))
    }

    override suspend fun getBenchmarkSummary(): Result<BenchmarkSummary> {
        return Result.success(summary)
    }

    override suspend fun exportBenchmarks(format: String): Result<String> {
        return Result.success("# DialexBench Benchmark Report in $format\nTotal Runs: ${runs.size}")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BenchmarkViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeBenchmarkRepository
    private lateinit var viewModel: BenchmarkViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeBenchmarkRepository()
        fakeRepository.cases.add(
            BenchmarkCase(
                id = "DB01",
                title = "Event-Driven vs CQRS",
                domain = "Distributed Systems",
                dilemma = "High-throughput financial ledger scenario",
                isBundled = true
            )
        )
        fakeRepository.cases.add(
            BenchmarkCase(
                id = "DB02",
                title = "Zero-Trust Mesh Migration",
                domain = "Security",
                dilemma = "Zero-trust service mesh Dilemma",
                isBundled = true
            )
        )
        viewModel = BenchmarkViewModel(fakeRepository)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load populates cases and selects first case`() = runTest(testDispatcher) {
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(2, state.cases.size)
        assertNotNull(state.selectedCase)
        assertEquals("DB01", state.selectedCase?.id)
        assertNotNull(state.summary)
        assertTrue(state.summary?.isStatSignificant == true)
    }

    @Test
    fun `SelectCase changes current selected case`() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.onIntent(BenchmarkIntent.SelectCase("DB02"))
        advanceUntilIdle()

        assertEquals("DB02", viewModel.state.value.selectedCase?.id)
    }

    @Test
    fun `StartBenchmarkRun triggers execution and appends active run to list`() = runTest(testDispatcher) {
        advanceUntilIdle()

        viewModel.onIntent(BenchmarkIntent.StartBenchmarkRun(caseId = "DB01", rounds = 2))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isRunning)
        assertNotNull(state.activeRun)
        assertEquals("run-test-1", state.activeRun?.id)
        assertEquals("COUNCIL", state.activeRun?.winner)
        assertEquals(1, state.historicalRuns.size)
        assertEquals(1, state.summary?.councilWins)
    }

    @Test
    fun `CreateCustomCase adds dilemma and updates selection`() = runTest(testDispatcher) {
        advanceUntilIdle()

        val custom = BenchmarkCase(
            id = "custom-test-99",
            title = "GraphQL Federation vs REST Mesh",
            domain = "API Architecture",
            dilemma = "API gateway architectural dilemma",
            isBundled = false
        )
        viewModel.onIntent(BenchmarkIntent.CreateCustomCase(custom))
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(3, state.cases.size)
        assertTrue(state.cases.any { it.id == "custom-test-99" })
    }
}
