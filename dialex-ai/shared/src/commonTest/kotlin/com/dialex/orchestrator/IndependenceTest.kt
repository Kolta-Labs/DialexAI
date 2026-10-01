package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.DebateMessage
import com.dialex.model.IndependenceConfig
import com.dialex.model.Provider
import kotlin.test.Test
import kotlin.test.assertEquals

class IndependenceTest {
    // three personas sharing ONE provider
    private fun seat(id: String, name: String) = Agent(provider = Provider.ANTHROPIC, model = "m", id = id, displayName = name)
    private val a = seat("a", "Skeptic")
    private val b = seat("b", "Optimist")
    private val c = seat("c", "Pragmatist")
    private val seats = listOf(a, b, c)

    private fun msg(s: Agent, round: Int, text: String) =
        DebateMessage(agentId = s.provider, seatId = s.id, authorDisplayName = s.label(), round = round, content = text)

    private fun Agent.label() = displayName

    @Test
    fun `blind first round hides only peers' round one answers`() {
        val views = listOf(
            msg(a, 1, "A1"), msg(b, 1, "B1"),
            DebateMessage(seatId = "system", isSystem = true, round = 1, content = "note"),
            DebateMessage(seatId = "mod", isModeratorIntervention = true, round = 1, content = "steer"),
            msg(a, 2, "A2"),
        )
        val cfg = IndependenceConfig(blindFirstRound = true)
        assertEquals(listOf("note", "steer", "A2"), applyIndependence(views, c, 1, cfg, seats).map { it.content })
        assertEquals("A1", applyIndependence(views, a, 1, cfg, seats).first().content)
        assertEquals(views.size, applyIndependence(views, c, 2, cfg, seats).size)
    }

    @Test
    fun `anonymized peers keep a stable letter and the transcript is not mutated`() {
        val views = listOf(msg(a, 1, "x"), msg(b, 1, "y"), msg(c, 1, "z"))
        val got = applyIndependence(views, c, 2, IndependenceConfig(anonymizeTranscript = true), seats)
        assertEquals(listOf("Participant A", "Participant B", "Pragmatist"), got.map { it.authorDisplayName })
        assertEquals("Skeptic", views[0].authorDisplayName)
    }

    @Test
    fun `default config changes nothing`() {
        val views = listOf(msg(a, 1, "x"))
        assertEquals(views, applyIndependence(views, b, 1, IndependenceConfig(), seats))
    }
}
