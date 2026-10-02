package com.dialex.presentation.setup

import com.dialex.model.Agent
import com.dialex.model.ConsensusMode
import com.dialex.model.DebateConfig
import com.dialex.model.DepthMode
import com.dialex.model.DiscussionPresets
import com.dialex.model.PresetArchetype
import com.dialex.model.Provider
import com.dialex.model.defaultModel
import com.dialex.orchestrator.DeliberationEstimator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CognitiveSettingsAndPresetsTest {

    private fun sampleDebateConfig(topic: String = "Test Topic"): DebateConfig {
        return DebateConfig(
            topic = topic,
            primary = Agent(
                provider = Provider.ANTHROPIC,
                model = Provider.ANTHROPIC.defaultModel(),
                displayName = "Claude"
            ),
            secondary = Agent(
                provider = Provider.GEMINI,
                model = Provider.GEMINI.defaultModel(),
                displayName = "Gemini"
            )
        )
    }

    @Test
    fun testPresetArchetypesGenerateValidConfigs() {
        val baseConfig = sampleDebateConfig()

        // 1. Quick Take
        val quickTake = DiscussionPresets.applyArchetype(baseConfig, PresetArchetype.QUICK_TAKE)
        assertEquals(2, quickTake.maxRounds)
        assertEquals(DepthMode.CASUAL, quickTake.depth.mode)
        assertEquals(ConsensusMode.SIMPLE_MAJORITY, quickTake.consensus.mode)
        assertEquals(PresetArchetype.QUICK_TAKE, DiscussionPresets.detectArchetype(quickTake))

        // 2. Executive Decision
        val exec = DiscussionPresets.applyArchetype(baseConfig, PresetArchetype.EXECUTIVE_DECISION)
        assertEquals(3, exec.maxRounds)
        assertEquals(DepthMode.EXECUTIVE, exec.depth.mode)
        assertEquals(ConsensusMode.SUPERMAJORITY, exec.consensus.mode)
        assertEquals(PresetArchetype.EXECUTIVE_DECISION, DiscussionPresets.detectArchetype(exec))

        // 3. Deep Research
        val deep = DiscussionPresets.applyArchetype(baseConfig, PresetArchetype.DEEP_RESEARCH)
        assertEquals(5, deep.maxRounds)
        assertEquals(DepthMode.ACADEMIC, deep.depth.mode)
        assertEquals(ConsensusMode.UNANIMOUS, deep.consensus.mode)
        assertEquals(PresetArchetype.DEEP_RESEARCH, DiscussionPresets.detectArchetype(deep))

        // 4. Red-Team Stress-Test
        val redTeam = DiscussionPresets.applyArchetype(baseConfig, PresetArchetype.RED_TEAM_STRESS_TEST)
        assertEquals(4, redTeam.maxRounds)
        assertEquals(ConsensusMode.DISABLED, redTeam.consensus.mode)
        assertEquals(PresetArchetype.RED_TEAM_STRESS_TEST, DiscussionPresets.detectArchetype(redTeam))
    }

    @Test
    fun testDeliberationEstimatorMath() {
        val casualConfig = DebateConfig(
            topic = "Quick question",
            primary = Agent(provider = Provider.GEMINI, model = "gemini-2.0-flash"),
            secondary = Agent(provider = Provider.ANTHROPIC, model = "claude-3-5-haiku"),
            maxRounds = 2
        )
        val casualEstimate = DeliberationEstimator.estimate(casualConfig)
        assertTrue(casualEstimate.estimatedCostDollars < 0.05, "Casual run cost should be under $0.05, got ${casualEstimate.estimatedCostDollars}")
        assertEquals(DeliberationEstimator.CostTier.LOW, casualEstimate.costTier)
        assertTrue(casualEstimate.estimatedDurationSeconds in 5..45)

        val heavyConfig = DebateConfig(
            topic = "Complex architecture debate",
            primary = Agent(provider = Provider.ANTHROPIC, model = "claude-3-opus"),
            secondary = Agent(provider = Provider.OPENAI, model = "gpt-4o"),
            tertiary = Agent(provider = Provider.ANTHROPIC, model = "claude-3-opus"),
            maxRounds = 8
        )
        val heavyEstimate = DeliberationEstimator.estimate(heavyConfig)
        assertTrue(
            heavyEstimate.estimatedCostDollars > 0.15,
            "Heavy run cost should be substantial, got ${heavyEstimate.estimatedCostDollars}"
        )
        assertTrue(heavyEstimate.estimatedDurationSeconds > 30)
    }

    @Test
    fun testCustomConfigPreservesUserOverrides() {
        val baseConfig = sampleDebateConfig()
        val quickTake = DiscussionPresets.applyArchetype(baseConfig, PresetArchetype.QUICK_TAKE)
        assertEquals(PresetArchetype.QUICK_TAKE, DiscussionPresets.detectArchetype(quickTake))

        // Modify a single parameter manually in Level 3 drawer
        val modified = quickTake.copy(maxRounds = quickTake.maxRounds + 1)
        assertEquals(PresetArchetype.CUSTOM, DiscussionPresets.detectArchetype(modified))
    }

    @Test
    fun testPonytailModePreservedWithPresets() {
        val initialConfig = DebateConfig(
            topic = "Should we adopt Kotlin Multiplatform?",
            primary = Agent(
                provider = Provider.ANTHROPIC,
                model = Provider.ANTHROPIC.defaultModel(),
                ponytail = false
            ),
            secondary = Agent(
                provider = Provider.OPENAI,
                model = Provider.OPENAI.defaultModel(),
                ponytail = true
            )
        )

        // Apply archetype preset
        val updatedConfig = DiscussionPresets.applyArchetype(initialConfig, PresetArchetype.RED_TEAM_STRESS_TEST)

        // Verify agents and their special modes (Ponytail) are retained
        assertEquals(2, updatedConfig.agents.size)
        assertEquals(false, updatedConfig.agents[0].ponytail)
        assertTrue(updatedConfig.agents[1].ponytail)
    }
}
