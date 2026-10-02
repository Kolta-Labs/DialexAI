package com.dialex.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** DebateConfig.primary/secondary/tertiary used to be named claude/gemini/chatgpt — the
 * @SerialName overrides keep the old wire format readable so discussions saved before the
 * rename (any provider config, seats were fixed to those names back then) still load. */
class DebateConfigSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun old_field_names_still_decode_into_the_renamed_seats() {
        val old = """
            {"topic":"t","claude":{"provider":"ANTHROPIC","model":"claude-x"},
             "gemini":{"provider":"GEMINI","model":"gemini-x"}}
        """.trimIndent()

        val config = json.decodeFromString<DebateConfig>(old)

        assertEquals(Provider.ANTHROPIC, config.primary.provider)
        assertEquals(Provider.GEMINI, config.secondary?.provider)
        assertNull(config.tertiary)
    }

    @Test
    fun depth_and_moderation_roundtrip_serialization() {
        val config = DebateConfig(
            topic = "Testing serialization",
            primary = Agent(Provider.ANTHROPIC, "claude-sonnet-5"),
            depth = DepthConfig(
                mode = DepthMode.CASUAL,
                targetWordCountPerTurn = 150,
                allowMathFormulas = false
            ),
            moderation = ModerationConfig(
                persona = ModeratorPersona.DELIBERATION_CHAIR,
                enabled = true
            )
        )

        val encoded = json.encodeToString(DebateConfig.serializer(), config)
        val decoded = json.decodeFromString<DebateConfig>(encoded)

        assertEquals(DepthMode.CASUAL, decoded.depth.mode)
        assertEquals(150, decoded.depth.targetWordCountPerTurn)
        assertEquals(false, decoded.depth.allowMathFormulas)

        assertEquals(ModeratorPersona.DELIBERATION_CHAIR, decoded.moderation.persona)
        assertEquals(true, decoded.moderation.enabled)
    }
}
