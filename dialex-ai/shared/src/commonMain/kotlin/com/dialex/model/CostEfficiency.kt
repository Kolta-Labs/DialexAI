package com.dialex.model

import kotlinx.serialization.Serializable

@Serializable
enum class CompactionStrategy {
    /** Sliding window: last N turns verbatim + single rolling summary of older turns. */
    SLIDING_WINDOW_SUMMARY,
    /** Structured Blackboard: maintains distinct Facts, Consensus, and Open Disputes state. */
    STRUCTURED_BLACKBOARD,
    /** Hybrid: Blackboard + last K immediate turns verbatim (Recommended). */
    HYBRID_BLACKBOARD_RECENT
}

@Serializable
data class CostEfficiencyConfig(
    val enabled: Boolean = true,
    /** Dynamic compaction triggers when cumulative uncompacted history exceeds this token threshold. */
    val triggerTokenThreshold: Int = 6_000,
    /** Number of recent verbatim turns to preserve when compacting (default: 3). */
    val keepRecentVerbatimTurns: Int = 3,
    /** Per-turn output token ceiling enforced on model API calls. */
    val maxOutputTokensPerTurn: Int = 600,
    /** Compaction architecture strategy. */
    val strategy: CompactionStrategy = CompactionStrategy.SLIDING_WINDOW_SUMMARY,
    /** Dedicated lightweight model used strictly for compaction/summarization. */
    val backgroundSummaryModel: String = "claude-haiku-4-5-20251001",
    /** Hard ceiling on total run spend in tokens (0 = unlimited). */
    val runTokenBudget: Int = 250_000,
    /** Dollar cost ceiling in USD (e.g. $2.00). 0.0 = unlimited. */
    val maxDollarSpendBudget: Double = 2.00,
    /** Enable provider-specific prompt caching headers and payload structuring. */
    val enablePromptCaching: Boolean = true
)

object PricingTable {
    data class ModelPricing(
        val inputPerMillion: Double,
        val outputPerMillion: Double,
        val cachedInputPerMillion: Double
    )

    val rates: Map<String, ModelPricing> = mapOf(
        "claude-sonnet-5" to ModelPricing(3.00, 15.00, 0.30),
        "claude-3-7-sonnet" to ModelPricing(3.00, 15.00, 0.30),
        "claude-3-5-sonnet" to ModelPricing(3.00, 15.00, 0.30),
        "claude-3-5-sonnet-20241022" to ModelPricing(3.00, 15.00, 0.30),
        "claude-3-5-haiku" to ModelPricing(0.80, 4.00, 0.08),
        "claude-haiku-4-5-20251001" to ModelPricing(0.80, 4.00, 0.08),
        "claude-3-opus" to ModelPricing(15.00, 75.00, 1.50),
        "gpt-5.6-sol" to ModelPricing(2.50, 10.00, 1.25),
        "gpt-4o" to ModelPricing(2.50, 10.00, 1.25),
        "gpt-4o-mini" to ModelPricing(0.15, 0.60, 0.075),
        "o3-mini" to ModelPricing(1.10, 4.40, 0.55),
        "o1" to ModelPricing(15.00, 60.00, 7.50),
        "gemini-3.7-flash" to ModelPricing(0.075, 0.30, 0.01875),
        "gemini-2.0-flash" to ModelPricing(0.10, 0.40, 0.025),
        "gemini-2.0-pro-exp" to ModelPricing(1.25, 5.00, 0.3125),
        "gemini-1.5-flash" to ModelPricing(0.075, 0.30, 0.01875),
        "gemini-1.5-pro" to ModelPricing(1.25, 5.00, 0.3125),
        "grok-4-fast" to ModelPricing(0.50, 2.00, 0.25),
        "grok-2-latest" to ModelPricing(2.00, 10.00, 1.00),
        "grok-beta" to ModelPricing(5.00, 15.00, 2.50),
        "deepseek-chat" to ModelPricing(0.14, 0.28, 0.014),
        "deepseek-reasoner" to ModelPricing(0.55, 2.19, 0.14),
        "deepseek-coder" to ModelPricing(0.14, 0.28, 0.014),
        "mistral-medium-latest" to ModelPricing(0.40, 1.20, 0.20),
        "mistral-large-latest" to ModelPricing(2.00, 6.00, 1.00),
        "codestral-latest" to ModelPricing(0.30, 0.90, 0.15),
        "llama3.2" to ModelPricing(0.0, 0.0, 0.0),
        "llama3.3" to ModelPricing(0.0, 0.0, 0.0),
    )

    private val defaultRate = ModelPricing(1.00, 4.00, 0.25)

    fun resolveRate(model: String, provider: Provider? = null): ModelPricing {
        val direct = rates[model]
        if (direct != null) return direct
        val matchedKey = rates.keys.firstOrNull { model.contains(it, ignoreCase = true) }
        if (matchedKey != null) return rates[matchedKey]!!

        return when (provider) {
            Provider.OLLAMA, Provider.CUSTOM -> ModelPricing(0.0, 0.0, 0.0)
            Provider.GEMINI -> ModelPricing(0.10, 0.40, 0.025)
            Provider.DEEPSEEK -> ModelPricing(0.14, 0.28, 0.014)
            Provider.OPENAI -> ModelPricing(2.50, 10.00, 1.25)
            Provider.ANTHROPIC -> ModelPricing(3.00, 15.00, 0.30)
            Provider.GROK -> ModelPricing(2.00, 10.00, 1.00)
            Provider.MISTRAL -> ModelPricing(0.40, 1.20, 0.20)
            null -> defaultRate
        }
    }

    fun calculateSpend(
        model: String,
        tokensIn: Long,
        tokensOut: Long,
        tokensCached: Long = 0L,
        provider: Provider? = null
    ): Double {
        val rate = resolveRate(model, provider)
        val uncachedIn = (tokensIn - tokensCached).coerceAtLeast(0L)
        val inputCost = (uncachedIn * rate.inputPerMillion) / 1_000_000.0
        val cachedInputCost = (tokensCached * rate.cachedInputPerMillion) / 1_000_000.0
        val outputCost = (tokensOut * rate.outputPerMillion) / 1_000_000.0
        return inputCost + cachedInputCost + outputCost
    }

    fun calculateDiscussionSpend(
        messages: List<DebateMessage>,
        agents: List<Agent>
    ): Double {
        val agentModelMap = agents.associate { it.id to it.model }
        val providerModelMap = agents.associate { it.provider to it.model }

        return messages.sumOf { msg ->
            if (msg.isError || msg.isSystem) return@sumOf 0.0
            val inTokens = msg.tokensIn?.toLong() ?: 0L
            val outTokens = msg.tokensOut?.toLong() ?: 0L
            val cachedTokens = msg.tokensCached?.toLong() ?: 0L
            val model = agentModelMap[msg.seatId] ?: providerModelMap[msg.provider] ?: "claude-haiku-4-5-20251001"
            calculateSpend(model, inTokens, outTokens, cachedTokens, msg.provider)
        }
    }
}
