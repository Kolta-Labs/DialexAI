package com.dialex.domain.model

import com.dialex.model.Agent
import kotlinx.serialization.Serializable

@Serializable
enum class MetricDimension {
    FACTUALITY,
    BLIND_SPOTS,
    TRADE_OFFS,
    ACTIONABILITY
}

@Serializable
enum class ArmType {
    SOLO_BASELINE,
    COUNCIL
}

@Serializable
data class BenchmarkCase(
    val id: String,
    val title: String,
    val domain: String,
    val dilemma: String,
    val constraints: List<String> = emptyList(),
    val groundTruthTraps: List<String> = emptyList(),
    val requiredTradeOffAxes: List<String> = emptyList(),
    val mandatoryBoundaryConditions: List<String> = emptyList(),
    val isBundled: Boolean = false
)

@Serializable
data class ArmResult(
    val armType: ArmType,
    val modelOrCouncil: String,
    val deliverable: String,
    val tokensUsed: Int = 0,
    val durationMs: Long = 0L,
    val estimatedCostUSD: Double = 0.0
)

@Serializable
data class MetricScore(
    val dimension: MetricDimension,
    val score: Double, // 0.0 - 10.0
    val critique: String = ""
)

@Serializable
data class JudgeEvaluation(
    val passNumber: Int,
    val order: String = "",
    val soloScores: List<MetricScore> = emptyList(),
    val councilScores: List<MetricScore> = emptyList(),
    val overallVerdict: String = "",
    val detailedCritique: String = ""
)

@Serializable
data class BenchmarkRun(
    val id: String,
    val caseId: String,
    val caseTitle: String,
    val timestamp: Long = 0L,
    val soloResult: ArmResult,
    val councilResult: ArmResult,
    val judgeModel: String = "",
    val evaluations: List<JudgeEvaluation> = emptyList(),
    val soloTotalScore: Double = 0.0,
    val councilTotalScore: Double = 0.0,
    val deltaQ: Double = 0.0,
    val winner: String = "TIE" // "COUNCIL", "SOLO", "TIE"
)

@Serializable
data class BenchmarkSummary(
    val totalRuns: Int = 0,
    val councilWins: Int = 0,
    val soloWins: Int = 0,
    val ties: Int = 0,
    val councilWinRate: Double = 0.0,
    val meanDeltaQ: Double = 0.0,
    val pValue: Double = 1.0,
    val isStatSignificant: Boolean = false,
    val avgFactualityDelta: Double = 0.0,
    val avgBlindSpotDelta: Double = 0.0,
    val avgTradeOffDelta: Double = 0.0,
    val avgActionDelta: Double = 0.0
)

@Serializable
data class RunBenchmarkRequest(
    val caseId: String,
    val soloAgent: Agent? = null,
    val councilAgents: List<Agent> = emptyList(),
    val judgeAgent: Agent? = null,
    val rounds: Int = 2
)
