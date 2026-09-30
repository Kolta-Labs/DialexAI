package com.dialex.model

import kotlinx.serialization.Serializable

@Serializable
enum class TemperatureSchedule {
    /** Fixed temperature throughout all rounds. */
    STATIC,
    /** Linearly decreases temperature from startTemp down to endTemp across rounds. */
    LINEAR_COOLING,
    /** Drops temperature sharply as consensus threshold increases. */
    ADAPTIVE_CONSENSUS_COOLED,
    /** High temperature in Round 1 (discovery), low in middle rounds, ultra-low in final round. */
    THREE_STAGE_DELIBERATION
}

@Serializable
data class SamplingConfig(
    /** Base sampling temperature (0.0 to 1.5). Default: 0.7. */
    val temperature: Double = 0.7,
    /** Nucleus sampling probability mass (0.0 to 1.0). Null = provider default. */
    val topP: Double? = null,
    /** Penalizes new tokens based on their existing frequency in text so far (0.0 to 2.0). */
    val frequencyPenalty: Double = 0.2,
    /** Penalizes new tokens based on whether they appear in the text so far (0.0 to 2.0). */
    val presencePenalty: Double = 0.15,
    /** Temperature scheduling strategy across deliberation rounds. */
    val schedule: TemperatureSchedule = TemperatureSchedule.THREE_STAGE_DELIBERATION,
    /** Starting temperature for cooling schedules (Round 1). */
    val startTemperature: Double = 0.85,
    /** Floor temperature for cooling schedules (Final Round). */
    val floorTemperature: Double = 0.35,
    /** Dedicated ultra-low temperature used exclusively for executive conclusions and syntheses. */
    val conclusionTemperature: Double = 0.15
)
