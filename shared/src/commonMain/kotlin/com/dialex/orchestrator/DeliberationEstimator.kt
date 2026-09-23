package com.dialex.orchestrator

import com.dialex.model.ConsensusMode
import com.dialex.model.DebateConfig
import com.dialex.model.PricingTable
import com.dialex.model.RoundMode
import kotlin.math.roundToInt

object DeliberationEstimator {

    data class RunEstimate(
        val estimatedCostDollars: Double,
        val estimatedDurationSeconds: Int,
        val estimatedTotalTokens: Long,
        val costTier: CostTier
    ) {
        val costFormatted: String
            get() {
                val rounded = (estimatedCostDollars * 100.0).roundToInt() / 100.0
                return if (rounded < 0.01 && estimatedCostDollars > 0.0) {
                    "<$0.01 USD"
                } else {
                    "~$${(rounded * 100).roundToInt() / 100.0} USD"
                }
            }

        val durationFormatted: String
            get() = when {
                estimatedDurationSeconds < 60 -> "~${estimatedDurationSeconds}s"
                else -> {
                    val mins = estimatedDurationSeconds / 60
                    val secs = estimatedDurationSeconds % 60
                    if (secs == 0) "~${mins}m" else "~${mins}m ${secs}s"
                }
            }

        val tokensFormatted: String
            get() = when {
                estimatedTotalTokens >= 1_000_000 -> "~${(estimatedTotalTokens / 100_000) / 10.0}M tokens"
                estimatedTotalTokens >= 1_000 -> "~${(estimatedTotalTokens / 1_000)}k tokens"
                else -> "~$estimatedTotalTokens tokens"
            }

        val tierLabel: String
            get() = when (costTier) {
                CostTier.LOW -> "🟢 Low"
                CostTier.MODERATE -> "🟡 Moderate"
                CostTier.HIGH -> "🔴 High"
            }

        val badgeSummary: String
            get() = "💰 Est. Cost: $costFormatted ($tierLabel)   ⏱️ Est. Time: $durationFormatted   📊 $tokensFormatted"
    }

    enum class CostTier {
        LOW,
        MODERATE,
        HIGH
    }

    fun estimate(config: DebateConfig): RunEstimate {
        val agents = if (config.agents.isNotEmpty()) config.agents else listOf(config.primary)
        val agentCount = agents.size.coerceAtLeast(1)
        val rounds = if (config.roundMode == RoundMode.FIXED) config.maxRounds.coerceAtLeast(1) else 4
        val wordsPerTurn = config.depth.targetWordCountPerTurn.coerceAtLeast(50)
        val tokensOutPerTurn = (wordsPerTurn * 1.33).toInt().coerceAtLeast(50)

        // Account for early consensus reduction
        val expectedRounds = if (config.consensus.mode != ConsensusMode.DISABLED) {
            (rounds * 0.75).coerceAtLeast(config.consensus.minRoundsBeforeExit.toDouble())
        } else {
            rounds.toDouble()
        }

        val totalTurns = (agentCount * expectedRounds).toInt().coerceAtLeast(1)
        val totalOutTokens = (totalTurns * tokensOutPerTurn).toLong()

        // Bounded by Task-03 compaction trigger
        val compactionTrigger = config.costEfficiency.triggerTokenThreshold.coerceIn(2000, 16000)
        val averageInTokensPerTurn = (compactionTrigger * 0.75).toInt().coerceIn(1500, 6000)

        val totalInTokens = (totalTurns.toLong() * averageInTokensPerTurn)
        val totalTokens = totalInTokens + totalOutTokens

        // Blend pricing across assigned models
        val inPerAgent = totalInTokens / agentCount
        val outPerAgent = totalOutTokens / agentCount

        var blendedCost = 0.0
        for (agent in agents) {
            blendedCost += PricingTable.calculateSpend(
                model = agent.model,
                tokensIn = inPerAgent,
                tokensOut = outPerAgent,
                tokensCached = (inPerAgent * 0.25).toLong(),
                provider = agent.provider
            )
        }

        val estimatedSeconds = (totalTurns * 3.5).toInt().coerceAtLeast(15)

        val tier = when {
            blendedCost < 0.10 -> CostTier.LOW
            blendedCost < 0.50 -> CostTier.MODERATE
            else -> CostTier.HIGH
        }

        return RunEstimate(
            estimatedCostDollars = blendedCost,
            estimatedDurationSeconds = estimatedSeconds,
            estimatedTotalTokens = totalTokens,
            costTier = tier
        )
    }
}
