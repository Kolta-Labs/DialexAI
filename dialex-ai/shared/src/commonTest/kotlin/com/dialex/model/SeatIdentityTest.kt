package com.dialex.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeatIdentityTest {
    private val skeptic = Agent(provider = Provider.ANTHROPIC, model = "m", id = "seat_a")
    private val optimist = Agent(provider = Provider.ANTHROPIC, model = "m", id = "seat_b")

    @Test
    fun `two personas on one provider are different speakers`() {
        val msg = DebateMessage(agentId = Provider.ANTHROPIC, seatId = skeptic.id, authorDisplayName = "Skeptic", content = "no")
        assertTrue(msg.isFrom(skeptic))
        assertFalse(msg.isFrom(optimist))
        assertEquals("Skeptic", msg.speaker())
    }

    @Test
    fun `old messages without a seat id still match by provider`() {
        val legacy = DebateMessage(agentId = Provider.ANTHROPIC, content = "hi")
        assertTrue(legacy.isFrom(skeptic))
        assertEquals("ANTHROPIC", legacy.speaker())
    }

    @Test
    fun `system and observer messages are never an agent's own turn`() {
        assertFalse(DebateMessage(agentId = Provider.ANTHROPIC, seatId = "system", content = "note").isFrom(skeptic))
    }
}
