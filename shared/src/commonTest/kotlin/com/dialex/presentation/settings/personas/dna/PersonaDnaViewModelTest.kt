package com.dialex.presentation.settings.personas.dna

import com.dialex.domain.model.CombatStance
import com.dialex.domain.model.HeuristicRule
import com.dialex.domain.model.PersonaDNA
import com.dialex.domain.model.PrimaryReasoningMode
import com.dialex.domain.model.SynthesisStyle
import com.dialex.domain.repository.PersonaRepository
import com.dialex.domain.usecase.CompileDnaPromptUseCase
import com.dialex.domain.usecase.ExportPersonaDnaUseCase
import com.dialex.domain.usecase.GetPersonaDnaUseCase
import com.dialex.domain.usecase.ImportPersonaDnaUseCase
import com.dialex.domain.usecase.ListBuiltinHeuristicsUseCase
import com.dialex.domain.usecase.UpdatePersonaDnaUseCase
import com.dialex.model.PredefinedPersona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FakeDnaPersonaRepository : PersonaRepository {
    val sampleHeuristic = HeuristicRule(
        id = "heur_gall",
        name = "Gall's Law",
        formulaOrMaxime = "A complex system that works is invariably found to have evolved from a simple system that worked.",
        triggerCondition = "System Architecture",
        applicationDirective = "Start with a working simple system."
    )

    override suspend fun getPersonas(): List<PredefinedPersona> = emptyList()
    override suspend fun createPersona(persona: PredefinedPersona): PredefinedPersona = persona
    override suspend fun deletePersona(id: String) {}
    override suspend fun importPersonas(json: String): Int = 0
    override suspend fun exportPersonas(): String = "[]"
    override suspend fun chatPersona(request: com.dialex.domain.model.PersonaChatRequest): com.dialex.domain.model.PersonaChatResponse =
        com.dialex.domain.model.PersonaChatResponse("OK", null)

    override suspend fun getPersonaDna(id: String): Result<PersonaDNA> =
        Result.success(PersonaDNA(id = id, name = "Loaded Persona", role = "Lead Architect"))

    override suspend fun updatePersonaDna(id: String, dna: PersonaDNA): Result<PersonaDNA> =
        Result.success(dna)

    override suspend fun listBuiltinHeuristics(): Result<List<HeuristicRule>> =
        Result.success(listOf(sampleHeuristic))

    override suspend fun compileDnaPrompt(dna: PersonaDNA): Result<String> =
        Result.success("[COGNITIVE DNA MANDATE: ${dna.name}]")

    override suspend fun importPersonaDna(content: String, format: String): Result<PersonaDNA> =
        Result.success(PersonaDNA(id = "imported_id", name = "Imported Persona", role = "Pragmatist"))

    override suspend fun exportPersonaDna(id: String, format: String): Result<String> =
        Result.success("schemaVersion: dialex.dna/v1.0\nid: $id\n")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersonaDnaViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repo: FakeDnaPersonaRepository
    private lateinit var viewModel: PersonaDnaViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repo = FakeDnaPersonaRepository()
        viewModel = PersonaDnaViewModel(
            getPersonaDnaUseCase = GetPersonaDnaUseCase(repo),
            updatePersonaDnaUseCase = UpdatePersonaDnaUseCase(repo),
            listBuiltinHeuristicsUseCase = ListBuiltinHeuristicsUseCase(repo),
            compileDnaPromptUseCase = CompileDnaPromptUseCase(repo),
            importPersonaDnaUseCase = ImportPersonaDnaUseCase(repo),
            exportPersonaDnaUseCase = ExportPersonaDnaUseCase(repo),
            personaId = "the-risk-analyst"
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialization_loadsBuiltinHeuristicsAndDna() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals("Loaded Persona", state.dna.name)
        assertEquals(1, state.availableHeuristics.size)
        assertEquals("heur_gall", state.availableHeuristics.first().id)
    }

    @Test
    fun updateEpistemicBias_updatesStateCorrectly() = runTest {
        viewModel.onIntent(PersonaDnaIntent.UpdateRigorThreshold(0.95))
        viewModel.onIntent(PersonaDnaIntent.UpdatePrimaryMode(PrimaryReasoningMode.EMPIRICAL_STATISTICAL))
        viewModel.onIntent(PersonaDnaIntent.UpdateTheoryVsPractice(0.85))

        val state = viewModel.state.value
        assertEquals(0.95, state.dna.epistemicBias.rigorThreshold, 0.001)
        assertEquals(PrimaryReasoningMode.EMPIRICAL_STATISTICAL, state.dna.epistemicBias.primaryMode)
        assertEquals(0.85, state.dna.epistemicBias.theoryVsPractice, 0.001)
    }

    @Test
    fun toggleBuiltinHeuristic_addsAndRemovesHeuristics() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val heuristic = repo.sampleHeuristic

        // Toggle on
        viewModel.onIntent(PersonaDnaIntent.ToggleBuiltinHeuristic(heuristic))
        assertTrue(viewModel.state.value.dna.heuristicLibrary.any { it.id == heuristic.id })

        // Toggle off
        viewModel.onIntent(PersonaDnaIntent.ToggleBuiltinHeuristic(heuristic))
        assertTrue(viewModel.state.value.dna.heuristicLibrary.none { it.id == heuristic.id })
    }

    @Test
    fun tabooSpace_addAndRemoveConstraints() = runTest {
        viewModel.onIntent(PersonaDnaIntent.AddForbiddenArgument("hand-waving"))
        assertTrue(viewModel.state.value.dna.tabooSpace.forbiddenArguments.contains("hand-waving"))

        viewModel.onIntent(PersonaDnaIntent.RemoveForbiddenArgument("hand-waving"))
        assertTrue(!viewModel.state.value.dna.tabooSpace.forbiddenArguments.contains("hand-waving"))
    }

    @Test
    fun compilePrompt_triggersCompilerAndEmitsEffect() = runTest {
        viewModel.onIntent(PersonaDnaIntent.CompilePrompt)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertNotNull(state.compiledPrompt)
        assertTrue(state.compiledPrompt!!.contains("COGNITIVE DNA MANDATE"))
        assertEquals(DnaStudioTab.COMPILED_PROMPT, state.activeTab)

        val effect = viewModel.effect.first()
        assertTrue(effect is PersonaDnaEffect.PromptCompiled)
    }

    @Test
    fun importDna_updatesDnaAndEmitsSavedEffect() = runTest {
        viewModel.onIntent(PersonaDnaIntent.OpenImportExportDialog(ImportExportMode.IMPORT, DnaFormat.YAML))
        viewModel.onIntent(PersonaDnaIntent.ImportExportTextChanged("schemaVersion: dialex.dna/v1.0\nname: Imported Persona\n"))
        viewModel.onIntent(PersonaDnaIntent.ExecuteImport)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Imported Persona", viewModel.state.value.dna.name)
        val effect = viewModel.effect.first()
        assertTrue(effect is PersonaDnaEffect.DnaSaved)
    }

    @Test
    fun adversarialPosture_and_synthesisPreferences_updateState() = runTest {
        viewModel.onIntent(PersonaDnaIntent.UpdateCombatStance(CombatStance.UNYIELDING_DOGMATIC))
        viewModel.onIntent(PersonaDnaIntent.UpdateTenacityScore(0.99))
        viewModel.onIntent(PersonaDnaIntent.UpdateSynthesisStyle(SynthesisStyle.HOLD_MINORITY_REPORT))

        val state = viewModel.state.value
        assertEquals(CombatStance.UNYIELDING_DOGMATIC, state.dna.adversarialPosture.stance)
        assertEquals(0.99, state.dna.adversarialPosture.tenacityScore, 0.001)
        assertEquals(SynthesisStyle.HOLD_MINORITY_REPORT, state.dna.synthesisPreference.style)
    }
}
