package com.dialex.orchestrator

import com.dialex.model.Provider
import com.dialex.model.SamplingConfig
import com.dialex.model.TemperatureSchedule

data class NormalizedSampling(
    val temperature: Double,
    val topP: Double? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val styleDirective: String? = null
)

object SamplingNormalizer {

    fun calculateScheduledSampling(
        config: SamplingConfig,
        round: Int,
        maxRounds: Int,
        isConclusion: Boolean = false
    ): Pair<Double, Pair<Double, Double>> {
        if (isConclusion) {
            return Pair(config.conclusionTemperature, Pair(0.10, 0.0))
        }

        val totalRounds = if (maxRounds <= 0) 10 else maxRounds

        return when (config.schedule) {
            TemperatureSchedule.STATIC -> {
                Pair(config.temperature, Pair(config.frequencyPenalty, config.presencePenalty))
            }
            TemperatureSchedule.LINEAR_COOLING -> {
                val fraction = if (totalRounds <= 1) 0.0 else ((round - 1.0) / (totalRounds - 1.0)).coerceIn(0.0, 1.0)
                val temp = config.startTemperature - (fraction * (config.startTemperature - config.floorTemperature))
                val freq = (config.frequencyPenalty + (fraction * 0.20)).coerceAtMost(1.0)
                val pres = (config.presencePenalty * (1.0 - fraction)).coerceAtLeast(0.0)
                Pair(temp, Pair(freq, pres))
            }
            TemperatureSchedule.THREE_STAGE_DELIBERATION -> {
                when {
                    round <= 1 -> Pair(config.startTemperature, Pair(0.10, 0.30))
                    round >= totalRounds -> Pair(config.floorTemperature, Pair(0.20, 0.0))
                    else -> Pair(0.60, Pair(0.35, 0.15))
                }
            }
            TemperatureSchedule.ADAPTIVE_CONSENSUS_COOLED -> {
                val fraction = if (totalRounds <= 1) 0.0 else ((round - 1.0) / (totalRounds - 1.0)).coerceIn(0.0, 1.0)
                val temp = (config.startTemperature - (fraction * fraction * (config.startTemperature - config.floorTemperature))).coerceIn(config.floorTemperature, config.startTemperature)
                Pair(temp, Pair(config.frequencyPenalty, config.presencePenalty))
            }
        }
    }

    fun normalize(
        config: SamplingConfig,
        provider: Provider,
        round: Int,
        maxRounds: Int,
        isConclusion: Boolean = false,
        temperatureBump: Double = 0.0,
        frequencyPenaltyBump: Double = 0.0
    ): NormalizedSampling {
        val (scheduledTemp, penalties) = calculateScheduledSampling(config, round, maxRounds, isConclusion)
        val (baseFreq, basePres) = penalties

        val rawTemp = scheduledTemp + temperatureBump
        val rawFreq = baseFreq + frequencyPenaltyBump
        val rawPres = basePres

        return when (provider) {
            Provider.ANTHROPIC -> {
                val clampedTemp = rawTemp.coerceIn(0.0, 1.0)
                val clampedTopP = config.topP?.coerceIn(0.0, 1.0)
                val directive = if (rawFreq > 0.20) {
                    "[STYLE DIRECTIVE: LEXICAL VARIATION]\n" +
                        "Avoid repeating key phrases, structural templates, or rhetorical devices from earlier turns. " +
                        "Introduce distinct framing, fresh vocabulary, and novel arguments."
                } else null
                NormalizedSampling(
                    temperature = clampedTemp,
                    topP = clampedTopP,
                    frequencyPenalty = null,
                    presencePenalty = null,
                    styleDirective = directive
                )
            }
            Provider.OPENAI, Provider.GROK, Provider.DEEPSEEK, Provider.MISTRAL -> {
                val clampedTemp = rawTemp.coerceIn(0.0, 2.0)
                val clampedTopP = config.topP?.coerceIn(0.0, 1.0)
                val clampedFreq = rawFreq.coerceIn(-2.0, 2.0)
                val clampedPres = rawPres.coerceIn(-2.0, 2.0)
                NormalizedSampling(
                    temperature = clampedTemp,
                    topP = clampedTopP,
                    frequencyPenalty = clampedFreq,
                    presencePenalty = clampedPres
                )
            }
            Provider.GEMINI -> {
                val clampedTemp = rawTemp.coerceIn(0.0, 2.0)
                val clampedTopP = config.topP?.coerceIn(0.0, 1.0)
                NormalizedSampling(
                    temperature = clampedTemp,
                    topP = clampedTopP,
                    frequencyPenalty = null,
                    presencePenalty = null
                )
            }
            Provider.OLLAMA -> {
                val clampedTemp = rawTemp.coerceIn(0.0, 2.0)
                val clampedTopP = config.topP?.coerceIn(0.0, 1.0)
                val clampedFreq = rawFreq.coerceIn(-2.0, 2.0)
                val clampedPres = rawPres.coerceIn(-2.0, 2.0)
                NormalizedSampling(
                    temperature = clampedTemp,
                    topP = clampedTopP,
                    frequencyPenalty = clampedFreq,
                    presencePenalty = clampedPres
                )
            }
            Provider.CUSTOM -> {
                NormalizedSampling(temperature = rawTemp.coerceIn(0.0, 2.0))
            }
        }
    }
}
