package com.dialex.presentation.setup

import com.dialex.domain.model.DecompositionPerspective
import com.dialex.domain.model.ProblemAxis
import com.dialex.domain.model.ProblemDecomposition
import com.dialex.domain.repository.DecompositionRepository
import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.DiscussionUsage
import com.dialex.domain.repository.ProjectRepository
import com.dialex.domain.repository.SettingsRepository
import com.dialex.domain.repository.TemplateRepository
import com.dialex.domain.usecase.DecomposeProblemUseCase
import com.dialex.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProblemDecompositionTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    companion object {
        val sampleProject = Project(
            id = "proj-1",
            name = "General Project",
            sharedContext = "Project Context",
            sharedInstructions = "Project Instructions"
        )

        val sampleDiscussion = Discussion(
            id = "d1",
            projectId = "proj-1",
            name = "Test Discussion",
            config = DebateConfig(
                topic = "Initial Topic",
                primary = Agent(
                    provider = Provider.ANTHROPIC,
                    model = "claude-3-7-sonnet",
                    displayName = "Chief Strategist"
                ),
                secondary = Agent(
                    provider = Provider.GEMINI,
                    model = "gemini-2.5-pro",
                    displayName = "Tech Architect"
                )
            ),
            status = DiscussionStatus.DRAFT
        )
    }

    private class FakeDecompositionRepo : DecompositionRepository {
        var callCount = 0
        override suspend fun decomposeProblem(
            topic: String,
            context: String,
            model: String?,
            provider: String?
        ): ProblemDecomposition {
            callCount++
            return ProblemDecomposition(
                topic = topic,
                perspectiveA = DecompositionPerspective(
                    id = "tech",
                    name = "Technical Architecture",
                    lensDescription = "Evaluates formal correctness and latency.",
                    axes = listOf(
                        ProblemAxis("axis_a1", "State Consistency vs Latency", "Strict transactions required.", "Eventual consistency for <20ms.", listOf("Acceptable SLA?"), 0.9),
                        ProblemAxis("axis_a2", "Blast Radius Isolation", "Isolate cellular failure domains.", "Avoid multi-hop RPC overhead.", listOf("Cascade risk?"), 0.85)
                    )
                ),
                perspectiveB = DecompositionPerspective(
                    id = "strat",
                    name = "Product Strategy",
                    lensDescription = "Evaluates developer velocity and costs.",
                    axes = listOf(
                        ProblemAxis("axis_b1", "Hiring & Cognitive Load", "Use battle-tested stacks.", "Adopt 10x esoteric tech.", listOf("Ramp up curve?"), 0.85),
                        ProblemAxis("axis_b2", "Time to Market vs Tech Debt", "Ship MVP now.", "Architect for 5 years.", listOf("Cost of delay?"), 0.75)
                    )
                )
            )
        }
    }

    private class FakeProjectRepo(var projects: List<Project>) : ProjectRepository {
        override fun observeProjects(): Flow<List<Project>> = emptyFlow()
        override suspend fun getProjects(): List<Project> = projects
        override suspend fun createProject(name: String): Project = Project("p-${projects.size + 1}", name)
        override suspend fun updateProject(project: Project): Project = project
        override suspend fun deleteProject(id: String) {}
    }

    private class FakeDiscussionRepo(var discussions: List<Discussion>) : DiscussionRepository {
        override suspend fun getDiscussions(projectId: String?): List<Discussion> = discussions
        override suspend fun getDiscussion(id: String): Discussion =
            discussions.firstOrNull { it.id == id } ?: throw NoSuchElementException("Not found: $id")
        override suspend fun createDiscussion(projectId: String, name: String, config: DebateConfig): Discussion =
            Discussion(id = "new-id", projectId = projectId, name = name, config = config, status = DiscussionStatus.DRAFT)
        override suspend fun updateDiscussion(discussion: Discussion): Discussion = discussion
        override suspend fun deleteDiscussion(id: String) {}
        override suspend fun duplicateDiscussion(id: String): Discussion = sampleDiscussion
        override suspend fun startDiscussion(id: String) {}
        override suspend fun pauseDiscussion(id: String) {}
        override suspend fun stopDiscussion(id: String) {}
        override suspend fun resumeDiscussion(id: String) {}
        override fun streamDiscussion(id: String): Flow<Discussion> = emptyFlow()
        override suspend fun generateHandoffPrompt(id: String): String = ""
        override suspend fun generateDeliverableFormat(discussionId: String, format: DeliverableFormat): String = ""
        override suspend fun generateDiscussionTitle(id: String): String = ""
        override suspend fun getUsage(id: String): DiscussionUsage = DiscussionUsage(0, 0, 0.0)
    }

    private class FakeSettingsRepo : SettingsRepository {
        override suspend fun getSettings(): AppState = AppState(apiKeys = ApiKeys(ollama = ""))
        override suspend fun updateApiKeys(keys: ApiKeys) {}
        override suspend fun updateCliCommands(commands: CliCommands) {}
        override suspend fun updateCompactionModel(model: String) {}
        override suspend fun updateCompactionSettings(settings: CompactionSettings) {}
        override suspend fun updateTokenBudget(budget: Int) {}
        override suspend fun updateMasterInstructions(instructions: String) {}
        override suspend fun updateDebatePolicy(policy: DebatePolicy) {}
        override suspend fun updateAgentDefaults(defaults: ProviderAgentDefaults) {}
        override suspend fun getAvailableModels(): Map<String, List<String>> = emptyMap()
        override suspend fun getCliStatus(): Map<String, Boolean> = emptyMap()
        override suspend fun getCliLogins(): Map<String, Boolean> = emptyMap()
    }

    private class FakeTemplateRepo : TemplateRepository {
        override suspend fun getTemplates(): List<CouncilTemplate> = emptyList()
        override suspend fun saveTemplate(template: CouncilTemplate): CouncilTemplate = template
        override suspend fun deleteTemplate(id: String) {}
    }

    @Test
    fun testProblemDecompositionLifecycle() = runTest(testDispatcher) {
        val decompRepo = FakeDecompositionRepo()
        val useCase = DecomposeProblemUseCase(decompRepo)

        val vm = SetupViewModel(
            projectRepository = FakeProjectRepo(listOf(sampleProject)),
            discussionRepository = FakeDiscussionRepo(listOf(sampleDiscussion)),
            settingsRepository = FakeSettingsRepo(),
            templateRepository = FakeTemplateRepo(),
            decompositionUseCase = useCase,
            discussionId = "d1",
            initialProjectId = "proj-1",
            supportsCli = true
        )

        advanceUntilIdle()

        // Verify discussion loaded
        val initialDisc = vm.state.value.discussion
        assertNotNull(initialDisc, "Discussion must be loaded from repository")

        // Set topic on the discussion
        val updatedDisc = initialDisc.copy(
            config = initialDisc.config.copy(topic = "Should we rewrite the transaction core in Rust?")
        )
        vm.onIntent(SetupIntent.DiscussionChanged(updatedDisc))
        advanceUntilIdle()

        // 1. Request decomposition
        vm.onIntent(SetupIntent.RequestProblemDecomposition)
        advanceUntilIdle()

        assertEquals(1, decompRepo.callCount)
        assertFalse(vm.state.value.isDecomposing)
        assertTrue(vm.state.value.showDecompositionSheet)
        assertNotNull(vm.state.value.activeDecomposition)
        assertEquals(4, vm.state.value.selectedAxisIds.size) // All 4 selected by default

        // 2. Toggle axis
        vm.onIntent(SetupIntent.ToggleAxisSelection("axis_a1"))
        assertEquals(3, vm.state.value.selectedAxisIds.size)
        assertFalse(vm.state.value.selectedAxisIds.contains("axis_a1"))

        // 3. Select all Perspective B
        vm.onIntent(SetupIntent.SelectAllPerspectiveB)
        assertEquals(2, vm.state.value.selectedAxisIds.size)
        assertTrue(vm.state.value.selectedAxisIds.contains("axis_b1"))
        assertTrue(vm.state.value.selectedAxisIds.contains("axis_b2"))

        // 4. Apply to debate agenda
        vm.onIntent(SetupIntent.ApplyDecompositionToAgenda)
        advanceUntilIdle()

        assertFalse(vm.state.value.showDecompositionSheet)
        val finalContext = vm.state.value.discussion?.config?.commonContext
        assertNotNull(finalContext)
        assertTrue(finalContext.contains("Structured Debate Agenda: Orthogonal Problem Axes"))
        assertTrue(finalContext.contains("Hiring & Cognitive Load"))
        assertTrue(finalContext.contains("Time to Market vs Tech Debt"))
    }
}
