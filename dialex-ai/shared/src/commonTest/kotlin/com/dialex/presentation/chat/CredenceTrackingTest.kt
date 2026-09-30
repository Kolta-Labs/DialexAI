package com.dialex.presentation.chat

import com.dialex.domain.model.CredenceHypothesis
import com.dialex.domain.model.CredenceLedger
import com.dialex.domain.model.EpistemicTippingPoint
import com.dialex.domain.model.PersonaCredencePoint
import com.dialex.domain.model.RoundCredenceSnapshot
import com.dialex.domain.repository.CredenceRepository
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.DiscussionUsage
import com.dialex.domain.usecase.RecalculateCredenceUseCase
import com.dialex.export.toExecutiveMemorandumHtml
import com.dialex.export.toMarkdown
import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Provider
import com.dialex.model.defaultModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CredenceTrackingTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeDiscussionRepository(
        var currentDiscussion: Discussion
    ) : DiscussionRepository {
        override suspend fun getDiscussions(projectId: String?): List<Discussion> = listOf(currentDiscussion)
        override suspend fun getDiscussion(id: String): Discussion = currentDiscussion
        override suspend fun createDiscussion(projectId: String, name: String, config: DebateConfig): Discussion = currentDiscussion
        override suspend fun updateDiscussion(discussion: Discussion): Discussion {
            currentDiscussion = discussion
            return discussion
        }
        override suspend fun deleteDiscussion(id: String) {}
        override suspend fun duplicateDiscussion(id: String): Discussion = currentDiscussion
        override suspend fun startDiscussion(id: String) {}
        override suspend fun pauseDiscussion(id: String) {}
        override suspend fun stopDiscussion(id: String) {}
        override suspend fun resumeDiscussion(id: String) {}
        override fun streamDiscussion(id: String): Flow<Discussion> = emptyFlow()
        override suspend fun generateHandoffPrompt(id: String): String = ""
        override suspend fun generateDeliverableFormat(discussionId: String, format: com.dialex.model.DeliverableFormat): String = ""
        override suspend fun generateDiscussionTitle(id: String): String = "Generated Title"
        override suspend fun getUsage(id: String): DiscussionUsage = DiscussionUsage(0, 0, 0.0)
    }

    private class FakeCredenceRepository(
        var ledgerToReturn: CredenceLedger
    ) : CredenceRepository {
        override suspend fun getCredenceLedger(discussionId: String): CredenceLedger = ledgerToReturn
        override suspend fun recalculateCredence(discussionId: String): CredenceLedger = ledgerToReturn
    }

    private fun sampleDiscussion(): Discussion {
        val agent = Agent(
            id = "agent-1",
            provider = Provider.ANTHROPIC,
            model = Provider.ANTHROPIC.defaultModel(),
            displayName = "Claude"
        )
        val config = DebateConfig(
            topic = "Monolith vs Microservices for Dialex",
            primary = agent
        )
        return Discussion(
            id = "disc-100",
            projectId = "proj-1",
            name = "Architecture Debate",
            config = config,
            status = DiscussionStatus.PAUSED,
            credenceLedger = sampleLedger()
        )
    }

    private fun sampleLedger(): CredenceLedger {
        val h1 = CredenceHypothesis(id = "H1", index = 1, label = "Monolith", description = "Adopt Modular Monolith architecture")
        val h2 = CredenceHypothesis(id = "H2", index = 2, label = "Microservices", description = "Adopt Microservices architecture")

        val snap0 = RoundCredenceSnapshot(
            roundIndex = 0,
            aggregatedCredence = mapOf("H1" to 0.50, "H2" to 0.50),
            entropy = 1.00,
            dominantHypothesis = "H1",
            personaCredences = emptyList()
        )

        val tipping = EpistemicTippingPoint(
            roundIndex = 1,
            evidenceSnippet = "Operational complexity benchmarks prove microservices incur 3x overhead.",
            likelihoodRatio = 3.54,
            affectedHypothesis = "H1",
            shiftDelta = 0.28
        )

        val snap1 = RoundCredenceSnapshot(
            roundIndex = 1,
            aggregatedCredence = mapOf("H1" to 0.78, "H2" to 0.22),
            entropy = 0.75,
            dominantHypothesis = "H1",
            personaCredences = listOf(
                PersonaCredencePoint(
                    personaId = "agent-1",
                    personaName = "Claude",
                    hypothesisCredence = mapOf("H1" to 0.78, "H2" to 0.22),
                    coreRationale = "Operational complexity favors modular monolith"
                )
            ),
            tippingPoints = listOf(tipping)
        )

        return CredenceLedger(
            discussionId = "disc-100",
            topic = "Monolith vs Microservices for Dialex",
            hypotheses = listOf(h1, h2),
            snapshots = listOf(snap0, snap1),
            finalEntropy = 0.75,
            status = "CONVERGED"
        )
    }

    @Test
    fun testSerializationDeserialization() {
        val ledger = sampleLedger()
        val encoded = json.encodeToString(ledger)
        val decoded = json.decodeFromString<CredenceLedger>(encoded)

        assertEquals(2, decoded.hypotheses.size)
        assertEquals(2, decoded.snapshots.size)
        assertEquals(1, decoded.snapshots[1].tippingPoints.size)
        assertEquals("H1", decoded.snapshots[1].dominantHypothesis)
        assertEquals(0.78, decoded.snapshots[1].aggregatedCredence["H1"])
        assertEquals(3.54, decoded.snapshots[1].tippingPoints[0].likelihoodRatio)
    }

    @Test
    fun testChatViewModelCredenceStateAndIntents() = runTest {
        val discussion = sampleDiscussion()
        val repo = FakeDiscussionRepository(discussion)
        val credenceRepo = FakeCredenceRepository(sampleLedger())
        val recalculateUseCase = RecalculateCredenceUseCase(credenceRepo)

        val vm = ChatViewModel(
            discussionRepository = repo,
            discussionId = discussion.id,
            tokenBudget = 100_000,
            recalculateCredenceUseCase = recalculateUseCase
        )
        advanceUntilIdle()

        // Verify initial state loaded credence ledger
        val state = vm.state.value
        assertNotNull(state.credenceLedger)
        assertEquals(2, state.credenceLedger?.hypotheses?.size)
        assertFalse(state.isCredenceDrawerOpen)

        // Toggle drawer
        vm.onIntent(ChatIntent.ToggleCredenceDrawer)
        assertTrue(vm.state.value.isCredenceDrawerOpen)

        // Close drawer
        vm.onIntent(ChatIntent.SetCredenceDrawerOpen(false))
        assertFalse(vm.state.value.isCredenceDrawerOpen)

        // Select round
        vm.onIntent(ChatIntent.SelectCredenceRound(1))
        assertEquals(1, vm.state.value.selectedCredenceRound)

        // Recalculate credence
        vm.onIntent(ChatIntent.RecalculateCredence)
        advanceUntilIdle()
        assertFalse(vm.state.value.isRecalculatingCredence)
        assertNotNull(vm.state.value.credenceLedger)
    }

    @Test
    fun testMarkdownExportContainsCredenceMatrix() {
        val discussion = sampleDiscussion()
        val markdown = discussion.toMarkdown()

        assertTrue(markdown.contains("## Bayesian Epistemic Credence Matrix"))
        assertTrue(markdown.contains("Final Epistemic Shannon Entropy: **0.75 bits**"))
        assertTrue(markdown.contains("| **Monolith**: Adopt Modular Monolith architecture 👑 (Dominant) | 50% | 78% | +28% |"))
        assertTrue(markdown.contains("### Epistemic Tipping Points"))
        assertTrue(markdown.contains("Likelihood Ratio Λ = 3.5"))
    }

    @Test
    fun testExecutiveMemorandumContainsCredenceMatrix() {
        val discussion = sampleDiscussion()
        val html = discussion.toExecutiveMemorandumHtml("Platform Project")

        assertTrue(html.contains("Bayesian Epistemic Credence Matrix"))
        assertTrue(html.contains("Terminal Shannon Entropy: <strong>0.75 bits</strong>"))
        assertTrue(html.contains("Monolith</strong>: Adopt Modular Monolith architecture"))
        assertTrue(html.contains("Dominant</span>"))
        assertTrue(html.contains("Epistemic Tipping Points Detected"))
        assertTrue(html.contains("Likelihood Ratio &Lambda; = 3.5"))
    }
}
