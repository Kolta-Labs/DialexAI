package com.dialex.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** AppStore round-trips every save/load through exactly this encode/decode pair — a break
 * here (a field that doesn't survive the trip, a wrong default) is the kind of bug that
 * would otherwise only surface as a real user's state.json failing to load. Covers the
 * whole tree: projects, discussions (with a full transcript and every optional seat),
 * settings — not just one corner of the model like the rename-compat test does. */
class AppStateSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun a_fully_populated_app_state_survives_encode_then_decode() {
        val config = DebateConfig(
            topic = "Is a hot dog a sandwich?",
            commonContext = "context",
            commonInfo = "info",
            primary = Agent(Provider.ANTHROPIC, "claude-x", cliCommand = "claude --print", caveman = true),
            secondary = Agent(Provider.GEMINI, "gemini-x"),
            tertiary = Agent(Provider.CUSTOM, "", displayName = "Aider"),
            roundMode = RoundMode.UNLIMITED,
            maxRounds = 7,
        )
        val discussion = Discussion(
            id = "d1",
            projectId = "p1",
            name = "Discussion One",
            config = config,
            status = DiscussionStatus.PAUSED,
            transcript = listOf(
                DebateMessage(Provider.ANTHROPIC, round = 1, content = "opening"),
                DebateMessage(Provider.GEMINI, round = 1, content = "reply", tokensIn = 100, tokensOut = 50),
                DebateMessage(Provider.CUSTOM, round = 1, content = "boom", isError = true),
            ),
            conclusion = null,
            handoffPrompt = "handoff text",
        )
        val original = AppState(
            projects = listOf(Project("p1", "Project One")),
            discussions = listOf(discussion),
            apiKeys = ApiKeys(anthropic = "sk-ant-x", openai = "sk-oa-x", gemini = "sk-g-x"),
            cliCommands = CliCommands(custom = "aider --message"),
            compactionModel = "claude-haiku-4-5-20251001",
            tokenBudget = 50_000,
        )

        val roundTripped = json.decodeFromString<AppState>(json.encodeToString(original))

        assertEquals(original, roundTripped)
    }
}
