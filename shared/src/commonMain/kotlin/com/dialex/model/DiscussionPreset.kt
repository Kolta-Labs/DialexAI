package com.dialex.model

import kotlinx.serialization.Serializable

@Serializable
enum class PresetArchetype {
    QUICK_TAKE,
    EXECUTIVE_DECISION,
    DEEP_RESEARCH,
    RED_TEAM_STRESS_TEST,
    CUSTOM;

    val displayName: String
        get() = when (this) {
            QUICK_TAKE -> "Quick Take"
            EXECUTIVE_DECISION -> "Executive Decision"
            DEEP_RESEARCH -> "Deep Research"
            RED_TEAM_STRESS_TEST -> "Red-Team Stress Test"
            CUSTOM -> "Custom"
        }

    val iconName: String
        get() = when (this) {
            QUICK_TAKE -> "bolt"
            EXECUTIVE_DECISION -> "work"
            DEEP_RESEARCH -> "science"
            RED_TEAM_STRESS_TEST -> "sports_mma"
            CUSTOM -> "tune"
        }

    val subtitle: String
        get() = when (this) {
            QUICK_TAKE -> "Casual & fast (~30s, ~$0.02)"
            EXECUTIVE_DECISION -> "Strategic & trade-off matrix (~1.5 min, ~$0.15)"
            DEEP_RESEARCH -> "Academic & rigorous (~4 min, ~$0.55)"
            RED_TEAM_STRESS_TEST -> "Adversarial & devil's advocate (~2.5 min, ~$0.30)"
            CUSTOM -> "Custom engine parameters"
        }
}

@Serializable
data class DiscussionPreset(
    val id: String,
    val archetype: PresetArchetype,
    val displayName: String,
    val description: String,
    val iconName: String,
    val estimatedCostLabel: String,
    val estimatedTimeLabel: String,
    val config: DebateConfig
)

object DiscussionPresets {

    fun defaultAgents(): List<Agent> = listOf(
        Agent(
            provider = Provider.ANTHROPIC,
            model = Provider.ANTHROPIC.defaultModel(),
            displayName = "Claude"
        ),
        Agent(
            provider = Provider.OPENAI,
            model = Provider.OPENAI.defaultModel(),
            displayName = "ChatGPT"
        ),
        Agent(
            provider = Provider.GEMINI,
            model = Provider.GEMINI.defaultModel(),
            displayName = "Gemini"
        )
    )

    fun createConfig(
        archetype: PresetArchetype,
        topic: String = "",
        agents: List<Agent> = defaultAgents()
    ): DebateConfig {
        val primary = agents.firstOrNull() ?: Agent(provider = Provider.ANTHROPIC, model = Provider.ANTHROPIC.defaultModel())
        val secondary = agents.getOrNull(1)
        val tertiary = agents.getOrNull(2)
        val quaternary = agents.getOrNull(3)
        val quinary = agents.getOrNull(4)
        val senary = agents.getOrNull(5)

        return when (archetype) {
            PresetArchetype.QUICK_TAKE -> DebateConfig(
                topic = topic,
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                quaternary = quaternary,
                quinary = quinary,
                senary = senary,
                roundMode = RoundMode.FIXED,
                maxRounds = 2,
                depth = DepthConfig(
                    mode = DepthMode.CASUAL,
                    targetWordCountPerTurn = 150,
                    allowMathFormulas = false,
                    allowAcademicJargon = false,
                    targetFleschReadingEase = 75,
                    recommendedRounds = 2
                ),
                consensus = ConsensusConfig(
                    mode = ConsensusMode.SIMPLE_MAJORITY,
                    consensusThreshold = 0.51,
                    minRoundsBeforeExit = 1,
                    allowMidRoundTermination = true
                ),
                sampling = SamplingConfig(
                    temperature = 0.70,
                    schedule = TemperatureSchedule.STATIC,
                    startTemperature = 0.70,
                    floorTemperature = 0.70
                ),
                moderation = ModerationConfig(
                    style = ModerationStyle.PASSIVE_WRAPUP_ONLY,
                    persona = ModeratorPersona.EXECUTIVE_ARBITER,
                    strictness = 2
                ),
                antiLoop = AntiLoopConfig(
                    enabled = true,
                    maxSimilarityThreshold = 0.65
                ),
                costEfficiency = CostEfficiencyConfig(
                    enabled = true,
                    triggerTokenThreshold = 4_000,
                    maxOutputTokensPerTurn = 350
                )
            )

            PresetArchetype.EXECUTIVE_DECISION -> DebateConfig(
                topic = topic,
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                quaternary = quaternary,
                quinary = quinary,
                senary = senary,
                roundMode = RoundMode.FIXED,
                maxRounds = 3,
                depth = DepthConfig(
                    mode = DepthMode.EXECUTIVE,
                    targetWordCountPerTurn = 300,
                    allowMathFormulas = false,
                    allowAcademicJargon = false,
                    targetFleschReadingEase = 55,
                    recommendedRounds = 3
                ),
                consensus = ConsensusConfig(
                    mode = ConsensusMode.SUPERMAJORITY,
                    consensusThreshold = 0.66,
                    minRoundsBeforeExit = 2,
                    allowMidRoundTermination = true
                ),
                sampling = SamplingConfig(
                    schedule = TemperatureSchedule.LINEAR_COOLING,
                    startTemperature = 0.80,
                    floorTemperature = 0.40,
                    temperature = 0.80
                ),
                moderation = ModerationConfig(
                    style = ModerationStyle.PERIODIC_CHECKPOINT,
                    persona = ModeratorPersona.EXECUTIVE_ARBITER,
                    checkpointFrequencyRounds = 2,
                    strictness = 3
                ),
                antiLoop = AntiLoopConfig(
                    enabled = true,
                    maxSimilarityThreshold = 0.55
                ),
                costEfficiency = CostEfficiencyConfig(
                    enabled = true,
                    triggerTokenThreshold = 6_000,
                    maxOutputTokensPerTurn = 600
                )
            )

            PresetArchetype.DEEP_RESEARCH -> DebateConfig(
                topic = topic,
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                quaternary = quaternary,
                quinary = quinary,
                senary = senary,
                roundMode = RoundMode.FIXED,
                maxRounds = 5,
                depth = DepthConfig(
                    mode = DepthMode.ACADEMIC,
                    targetWordCountPerTurn = 700,
                    allowMathFormulas = true,
                    allowAcademicJargon = true,
                    targetFleschReadingEase = 25,
                    recommendedRounds = 5
                ),
                consensus = ConsensusConfig(
                    mode = ConsensusMode.UNANIMOUS,
                    consensusThreshold = 1.0,
                    minRoundsBeforeExit = 2,
                    allowMidRoundTermination = false
                ),
                sampling = SamplingConfig(
                    schedule = TemperatureSchedule.THREE_STAGE_DELIBERATION,
                    startTemperature = 0.85,
                    floorTemperature = 0.35,
                    temperature = 0.80
                ),
                moderation = ModerationConfig(
                    style = ModerationStyle.DYNAMIC_ACTIVE_STEERAGE,
                    persona = ModeratorPersona.SOCRATIC_PROBE,
                    strictness = 3
                ),
                antiLoop = AntiLoopConfig(
                    enabled = true,
                    maxSimilarityThreshold = 0.65
                ),
                costEfficiency = CostEfficiencyConfig(
                    enabled = true,
                    triggerTokenThreshold = 8_000,
                    maxOutputTokensPerTurn = 1000
                )
            )

            PresetArchetype.RED_TEAM_STRESS_TEST -> DebateConfig(
                topic = topic,
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                quaternary = quaternary,
                quinary = quinary,
                senary = senary,
                roundMode = RoundMode.FIXED,
                maxRounds = 4,
                depth = DepthConfig(
                    mode = DepthMode.EXECUTIVE,
                    targetWordCountPerTurn = 350,
                    allowMathFormulas = false,
                    allowAcademicJargon = false,
                    targetFleschReadingEase = 55,
                    recommendedRounds = 4
                ),
                consensus = ConsensusConfig(
                    mode = ConsensusMode.DISABLED,
                    consensusThreshold = 1.0,
                    minRoundsBeforeExit = 4,
                    allowMidRoundTermination = false
                ),
                sampling = SamplingConfig(
                    schedule = TemperatureSchedule.STATIC,
                    temperature = 0.90,
                    startTemperature = 0.90,
                    floorTemperature = 0.90
                ),
                moderation = ModerationConfig(
                    style = ModerationStyle.STRICT_ARBITRATION,
                    persona = ModeratorPersona.DEVILS_ADVOCATE_CHAIR,
                    strictness = 4
                ),
                antiLoop = AntiLoopConfig(
                    enabled = true,
                    maxSimilarityThreshold = 0.50
                ),
                costEfficiency = CostEfficiencyConfig(
                    enabled = true,
                    triggerTokenThreshold = 6_000,
                    maxOutputTokensPerTurn = 700
                )
            )

            PresetArchetype.CUSTOM -> DebateConfig(
                topic = topic,
                primary = primary,
                secondary = secondary,
                tertiary = tertiary,
                quaternary = quaternary,
                quinary = quinary,
                senary = senary
            )
        }
    }

    /**
     * Applies an archetype calibration to an existing config while preserving:
     * - topic, commonContext, commonInfo
     * - existing agent roster (including Ponytail settings)
     * - attached files
     */
    fun applyArchetype(existing: DebateConfig, archetype: PresetArchetype): DebateConfig {
        if (archetype == PresetArchetype.CUSTOM) return existing
        val templated = createConfig(archetype, existing.topic, existing.agents)
        return existing.copy(
            roundMode = templated.roundMode,
            maxRounds = templated.maxRounds,
            depth = templated.depth,
            consensus = templated.consensus,
            sampling = templated.sampling,
            moderation = templated.moderation,
            antiLoop = templated.antiLoop,
            costEfficiency = templated.costEfficiency
        )
    }

    /**
     * Detects which archetype the current configuration matches, or returns CUSTOM if modified.
     */
    fun detectArchetype(config: DebateConfig): PresetArchetype {
        val candidates = listOf(
            PresetArchetype.QUICK_TAKE,
            PresetArchetype.EXECUTIVE_DECISION,
            PresetArchetype.DEEP_RESEARCH,
            PresetArchetype.RED_TEAM_STRESS_TEST
        )

        for (archetype in candidates) {
            val ref = createConfig(archetype, config.topic, config.agents)
            if (config.depth.mode == ref.depth.mode &&
                config.maxRounds == ref.maxRounds &&
                config.consensus.mode == ref.consensus.mode &&
                config.sampling.schedule == ref.sampling.schedule &&
                kotlin.math.abs(config.sampling.temperature - ref.sampling.temperature) < 0.05 &&
                config.moderation.style == ref.moderation.style &&
                kotlin.math.abs(config.antiLoop.maxSimilarityThreshold - ref.antiLoop.maxSimilarityThreshold) < 0.02 &&
                config.costEfficiency.triggerTokenThreshold == ref.costEfficiency.triggerTokenThreshold
            ) {
                return archetype
            }
        }
        return PresetArchetype.CUSTOM
    }

    val all: List<DiscussionPreset> by lazy {
        listOf(
            DiscussionPreset(
                id = "preset_quick_take",
                archetype = PresetArchetype.QUICK_TAKE,
                displayName = "Quick Take",
                description = "Casual, plain-English deliberation for quick everyday questions.",
                iconName = "bolt",
                estimatedCostLabel = "~$0.02",
                estimatedTimeLabel = "~30s",
                config = createConfig(PresetArchetype.QUICK_TAKE)
            ),
            DiscussionPreset(
                id = "preset_executive_decision",
                archetype = PresetArchetype.EXECUTIVE_DECISION,
                displayName = "Executive Decision",
                description = "Balanced strategic debate producing a clear trade-off matrix and consensus recommendation.",
                iconName = "work",
                estimatedCostLabel = "~$0.15",
                estimatedTimeLabel = "~1.5 min",
                config = createConfig(PresetArchetype.EXECUTIVE_DECISION)
            ),
            DiscussionPreset(
                id = "preset_deep_research",
                archetype = PresetArchetype.DEEP_RESEARCH,
                displayName = "Deep Research",
                description = "Rigorous academic examination from first-principles with dense arguments and equations.",
                iconName = "science",
                estimatedCostLabel = "~$0.55",
                estimatedTimeLabel = "~4 min",
                config = createConfig(PresetArchetype.DEEP_RESEARCH)
            ),
            DiscussionPreset(
                id = "preset_red_team",
                archetype = PresetArchetype.RED_TEAM_STRESS_TEST,
                displayName = "Red-Team Stress Test",
                description = "Adversarial challenge with high creativity, strict moderation, and zero premature consensus.",
                iconName = "sports_mma",
                estimatedCostLabel = "~$0.30",
                estimatedTimeLabel = "~2.5 min",
                config = createConfig(PresetArchetype.RED_TEAM_STRESS_TEST)
            )
        )
    }
}
