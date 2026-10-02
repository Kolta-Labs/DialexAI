package com.dialex.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OneKeyCouncilTest {
    private val base = DebateConfig(
        topic = "Move billing to Rust",
        commonContext = "Team of 6",
        primary = Agent(provider = Provider.ANTHROPIC, model = "claude-x"),
    )

    @Test
    fun `every mode is one provider with unique seats and the safeguards on`() {
        for (mode in OneKeyMode.entries) {
            val cfg = OneKeyCouncil.apply(base, mode)
            val seats = cfg.agents
            assertEquals(4, seats.size, mode.name)
            assertTrue(seats.all { it.provider == Provider.ANTHROPIC && it.model == "claude-x" }, mode.name)
            assertEquals(seats.size, seats.map { it.id }.toSet().size, "${mode.name}: seat ids must be unique")
            assertTrue(seats.all { "do not agree just to be agreeable" in it.systemPrompt }, mode.name)
            assertTrue(cfg.independence.blindFirstRound && cfg.independence.anonymizeTranscript, mode.name)
            assertEquals(mode.rounds, cfg.maxRounds)
        }
    }

    @Test
    fun `the user's topic and context survive`() {
        val cfg = OneKeyCouncil.apply(base, OneKeyMode.PREMORTEM)
        assertEquals("Move billing to Rust", cfg.topic)
        assertTrue(cfg.commonContext.startsWith("Team of 6"))
        assertTrue("FAILED" in cfg.commonContext)
    }

    @Test
    fun `only tenth man disables early consensus and forbids its dissenter to concede`() {
        assertEquals(ConsensusMode.DISABLED, OneKeyCouncil.apply(base, OneKeyMode.TENTH_MAN).consensus.mode)
        assertEquals(base.consensus.mode, OneKeyCouncil.apply(base, OneKeyMode.RED_TEAM).consensus.mode)
        val dissenter = OneKeyCouncil.apply(base, OneKeyMode.TENTH_MAN).agents.first { it.displayName == "Tenth Man" }
        assertTrue("never open a reply with AGREED" in dissenter.systemPrompt)
    }

    @Test
    fun `seats keep working when applied twice`() {
        val once = OneKeyCouncil.apply(base, OneKeyMode.RED_TEAM)
        val twice = OneKeyCouncil.apply(once, OneKeyMode.TENTH_MAN)
        assertEquals(4, twice.agents.size)
        assertEquals(Provider.ANTHROPIC, twice.primary.provider)
    }
}
