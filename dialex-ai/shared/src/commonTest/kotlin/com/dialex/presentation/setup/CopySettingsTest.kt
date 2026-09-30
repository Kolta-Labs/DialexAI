package com.dialex.presentation.setup

import com.dialex.domain.repository.DiscussionRepository
import com.dialex.domain.repository.DiscussionUsage
import com.dialex.domain.repository.ProjectRepository
import com.dialex.domain.repository.SettingsRepository
import com.dialex.domain.repository.TemplateRepository
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CopySettingsTest {

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
        val sampleSourceDiscussion = Discussion(
            id = "source-1",
            projectId = "proj-1",
            name = "Existing High-Stakes Council",
            config = DebateConfig(
                topic = "Original Source Topic Should Never Be Copied",
                primary = Agent(
                    provider = Provider.ANTHROPIC,
                    model = "claude-3-7-sonnet",
                    displayName = "Chief Strategist"
                ),
                secondary = Agent(
                    provider = Provider.GEMINI,
                    model = "gemini-2.5-pro",
                    displayName = "Tech Architect",
                    ponytail = true
                ),
                tertiary = Agent(
                    provider = Provider.GROK,
                    model = "grok-3",
                    displayName = "Devil's Advocate"
                ),
                roundMode = RoundMode.FIXED,
                maxRounds = 6,
                depth = DepthConfig(mode = DepthMode.ACADEMIC, targetWordCountPerTurn = 600),
                consensus = ConsensusConfig(mode = ConsensusMode.UNANIMOUS),
                sampling = SamplingConfig(schedule = TemperatureSchedule.LINEAR_COOLING, temperature = 0.85)
            ),
            attachedFolders = listOf(
                FolderScope(path = "/workspace/project-alpha", isReadOnly = true)
            ),
            status = DiscussionStatus.DONE
        )

        val sampleProject = Project(
            id = "proj-1",
            name = "General Project",
            sharedContext = "Project Context",
            sharedInstructions = "Project Instructions"
        )
    }

    private class FakeDiscussionRepository(
        var discussions: List<Discussion>
    ) : DiscussionRepository {
        override suspend fun getDiscussions(projectId: String?): List<Discussion> = discussions
        override suspend fun getDiscussion(id: String): Discussion =
            discussions.firstOrNull { it.id == id } ?: throw NoSuchElementException("Not found: $id")
        override suspend fun createDiscussion(projectId: String, name: String, config: DebateConfig): Discussion =
            Discussion(id = "new-id", projectId = projectId, name = name, config = config, status = DiscussionStatus.DRAFT)
        override suspend fun updateDiscussion(discussion: Discussion): Discussion = discussion
        override suspend fun deleteDiscussion(id: String) {}
        override suspend fun duplicateDiscussion(id: String): Discussion = sampleSourceDiscussion
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

    private class FakeProjectRepository(
        var projects: List<Project>
    ) : ProjectRepository {
        override fun observeProjects(): Flow<List<Project>> = emptyFlow()
        override suspend fun getProjects(): List<Project> = projects
        override suspend fun createProject(name: String): Project = Project("p-${projects.size + 1}", name)
        override suspend fun updateProject(project: Project): Project = project
        override suspend fun deleteProject(id: String) {}
    }

    private class FakeSettingsRepository : SettingsRepository {
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

    private class FakeTemplateRepository : TemplateRepository {
        override suspend fun getTemplates(): List<CouncilTemplate> = emptyList()
        override suspend fun saveTemplate(template: CouncilTemplate): CouncilTemplate = template
        override suspend fun deleteTemplate(id: String) {}
    }

    @Test
    fun test_copy_settings_on_init_copies_all_settings_except_topic() = runTest(testDispatcher) {
        val discRepo = FakeDiscussionRepository(listOf(sampleSourceDiscussion))
        val projRepo = FakeProjectRepository(listOf(sampleProject))
        val settingsRepo = FakeSettingsRepository()
        val templateRepo = FakeTemplateRepository()

        val viewModel = SetupViewModel(
            projectRepository = projRepo,
            discussionRepository = discRepo,
            settingsRepository = settingsRepo,
            templateRepository = templateRepo,
            discussionId = null,
            initialProjectId = "proj-1",
            copyFromDiscussionId = "source-1",
            supportsCli = false
        )

        advanceUntilIdle()

        val state = viewModel.state.value
        // Must immediately land in ConfigForm ready to type topic
        assertEquals(SetupStep.ConfigForm, state.step)

        val disc = state.discussion
        assertTrue(disc != null, "Discussion should not be null")

        // TOPIC MUST NOT BE COPIED
        assertEquals("", disc.config.topic, "Topic must be empty, not copied from source!")
        assertNotEquals(sampleSourceDiscussion.config.topic, disc.config.topic)

        assertEquals(3, disc.config.agents.size)
        assertEquals("claude-3-7-sonnet", disc.config.primary.model)
        assertEquals("gemini-2.5-pro", disc.config.secondary?.model)
        assertEquals(true, disc.config.secondary?.ponytail)
        assertEquals("grok-3", disc.config.tertiary?.model)
        assertEquals(6, disc.config.maxRounds)
        assertEquals(DepthMode.ACADEMIC, disc.config.depth.mode)
        assertEquals(ConsensusMode.UNANIMOUS, disc.config.consensus.mode)
        assertEquals(TemperatureSchedule.LINEAR_COOLING, disc.config.sampling.schedule)

        // Workspace folders copied
        assertEquals(1, disc.attachedFolders.size)
        assertEquals("/workspace/project-alpha", disc.attachedFolders.first().path)
    }

    @Test
    fun test_copy_settings_intent_preserves_user_typed_topic() = runTest(testDispatcher) {
        val discRepo = FakeDiscussionRepository(listOf(sampleSourceDiscussion))
        val projRepo = FakeProjectRepository(listOf(sampleProject))
        val settingsRepo = FakeSettingsRepository()
        val templateRepo = FakeTemplateRepository()

        val viewModel = SetupViewModel(
            projectRepository = projRepo,
            discussionRepository = discRepo,
            settingsRepository = settingsRepo,
            templateRepository = templateRepo,
            discussionId = null,
            initialProjectId = "proj-1",
            copyFromDiscussionId = null,
            supportsCli = false
        )

        advanceUntilIdle()

        // User is initially on FrontPage
        assertEquals(SetupStep.FrontPage, viewModel.state.value.step)

        // User sets a custom topic
        val currentDisc = viewModel.state.value.discussion!!
        val userTopic = "Should we adopt Rust or Kotlin for the backend?"
        viewModel.onIntent(SetupIntent.ConfigChanged(currentDisc.config.copy(topic = userTopic)))
        advanceUntilIdle()

        assertEquals(userTopic, viewModel.state.value.discussion?.config?.topic)

        // User decides to copy settings from source-1
        viewModel.onIntent(SetupIntent.CopySettingsFrom("source-1"))
        advanceUntilIdle()

        val updatedState = viewModel.state.value
        assertEquals(SetupStep.ConfigForm, updatedState.step)

        val updatedDisc = updatedState.discussion!!
        // The user's typed topic MUST be preserved, not overwritten by source!
        assertEquals(userTopic, updatedDisc.config.topic)
        assertNotEquals(sampleSourceDiscussion.config.topic, updatedDisc.config.topic)

        assertEquals(3, updatedDisc.config.agents.size)
        assertEquals("claude-3-7-sonnet", updatedDisc.config.primary.model)
        assertEquals(true, updatedDisc.config.secondary?.ponytail)
        assertEquals(6, updatedDisc.config.maxRounds)
        assertEquals(DepthMode.ACADEMIC, updatedDisc.config.depth.mode)
    }
}
