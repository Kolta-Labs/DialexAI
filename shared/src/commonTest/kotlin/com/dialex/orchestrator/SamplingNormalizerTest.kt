package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Provider
import com.dialex.model.SamplingConfig
import com.dialex.model.TemperatureSchedule
import com.dialex.runner.AgentReply
import com.dialex.runner.AgentRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SamplingNormalizerTest {

    @Test
    fun test_scheduled_temperature_cools_across_rounds() {
        val config = SamplingConfig(
            schedule = TemperatureSchedule.LINEAR_COOLING,
            startTemperature = 0.85,
            floorTemperature = 0.35
        )

        // Over 5 rounds: Round 1 = 0.85, Round 3 = 0.60, Round 5 = 0.35
        val (tempR1, _) = SamplingNormalizer.calculateScheduledSampling(config, round = 1, maxRounds = 5)
        assertEquals(0.85, tempR1, 0.001)

        val (tempR3, _) = SamplingNormalizer.calculateScheduledSampling(config, round = 3, maxRounds = 5)
        assertEquals(0.60, tempR3, 0.001)

        val (tempR5, _) = SamplingNormalizer.calculateScheduledSampling(config, round = 5, maxRounds = 5)
        assertEquals(0.35, tempR5, 0.001)
    }

    @Test
    fun test_conclusion_always_receives_low_temperature() {
        val config = SamplingConfig(
            temperature = 0.95,
            schedule = TemperatureSchedule.STATIC,
            conclusionTemperature = 0.15
        )

        val (tempConclusion, _) = SamplingNormalizer.calculateScheduledSampling(config, round = 5, maxRounds = 5, isConclusion = true)
        assertEquals(0.15, tempConclusion, 0.001)
    }

    @Test
    fun test_provider_parameter_normalization_clamping() {
        val highTempConfig = SamplingConfig(
            schedule = TemperatureSchedule.STATIC,
            temperature = 1.6
        )

        val anthropicNorm = SamplingNormalizer.normalize(highTempConfig, Provider.ANTHROPIC, round = 1, maxRounds = 5)
        assertEquals(1.0, anthropicNorm.temperature, "Anthropic temperature must be clamped at 1.0")

        val openAiNorm = SamplingNormalizer.normalize(highTempConfig, Provider.OPENAI, round = 1, maxRounds = 5)
        assertEquals(1.6, openAiNorm.temperature, "OpenAI temperature allows up to 2.0")
        assertNotNull(openAiNorm.frequencyPenalty)
        assertNotNull(openAiNorm.presencePenalty)
    }
}
