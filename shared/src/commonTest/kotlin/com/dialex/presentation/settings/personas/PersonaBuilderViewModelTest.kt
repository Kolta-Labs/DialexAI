package com.dialex.presentation.settings.personas

import com.dialex.domain.model.PersonaChatRequest
import com.dialex.domain.model.PersonaChatResponse
import com.dialex.domain.repository.PersonaRepository
import com.dialex.model.PredefinedPersona
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakePersonaRepository : PersonaRepository {

    val savedPersonas = mutableListOf<PredefinedPersona>()
    var chatPersonaHandler: ((PersonaChatRequest) -> PersonaChatResponse)? = null

    override suspend fun getPersonas(): List<PredefinedPersona> = savedPersonas
    override suspend fun createPersona(persona: PredefinedPersona): PredefinedPersona {
        savedPersonas.removeAll { it.id == persona.id }
        savedPersonas.add(persona)
        return persona
    }
    override suspend fun deletePersona(id: String) {
        savedPersonas.removeAll { it.id == id }
    }
    override suspend fun importPersonas(json: String): Int = 0
    override suspend fun exportPersonas(): String = "[]"
    override suspend fun chatPersona(request: PersonaChatRequest): PersonaChatResponse {
        return chatPersonaHandler?.invoke(request) ?: run {
            val p = PredefinedPersona(
                id = "ai_gen_1",
                name = "AI Security Lead",
                category = "Software Engineering",
                role = "Adversarial IAM Auditor",
                description = "Uncovers IAM flaws and zero-trust vulnerabilities.",
                ponytail = true,
                systemPrompt = "You are an AI Security Lead."
            )
            PersonaChatResponse(
                reply = "I crafted this persona for you:\n\n```json\n{\"name\": \"AI Security Lead\", \"role\": \"Adversarial IAM Auditor\", \"ponytail\": true}\n```",
                parsedPersona = p
            )
        }
    }

    override suspend fun getPersonaDna(id: String): Result<com.dialex.domain.model.PersonaDNA> =
        Result.success(com.dialex.domain.model.PersonaDNA(id = id, name = id, role = "Test Analyst"))

    override suspend fun updatePersonaDna(id: String, dna: com.dialex.domain.model.PersonaDNA): Result<com.dialex.domain.model.PersonaDNA> =
        Result.success(dna)

    override suspend fun listBuiltinHeuristics(): Result<List<com.dialex.domain.model.HeuristicRule>> =
        Result.success(emptyList())

    override suspend fun compileDnaPrompt(dna: com.dialex.domain.model.PersonaDNA): Result<String> =
        Result.success("[DNA MANDATE: ${dna.name}]")

    override suspend fun importPersonaDna(content: String, format: String): Result<com.dialex.domain.model.PersonaDNA> =
        Result.success(com.dialex.domain.model.PersonaDNA(id = "imported", name = "Imported", role = "Imported Role"))

    override suspend fun exportPersonaDna(id: String, format: String): Result<String> =
        Result.success("schemaVersion: dialex.dna/v1.0\nid: $id\n")
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersonaBuilderViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun savePersona_withOptionalFields_autoComposesPromptAndEmitsNavigateBackImmediately() = runTest {

        val repo = FakePersonaRepository()
        val viewModel = PersonaBuilderViewModel(personaRepository = repo)

        viewModel.onIntent(PersonaBuilderIntent.NameChanged("Security Lead"))
        viewModel.onIntent(PersonaBuilderIntent.RoleAndPersonaChanged("Head of Application Security"))
        viewModel.onIntent(PersonaBuilderIntent.CoreExpertiseChanged("OWASP Top 10, Zero Trust"))
        viewModel.onIntent(PersonaBuilderIntent.ToneAndVoiceChanged("Inquisitive, skeptical"))
        viewModel.onIntent(PersonaBuilderIntent.ObjectiveChanged("Identify authorization bypasses"))

        // Save persona
        viewModel.onIntent(PersonaBuilderIntent.Save)

        // Verify NavigateBack effect is emitted immediately
        val effect = viewModel.effect.first()
        assertEquals(PersonaBuilderEffect.NavigateBack, effect)

        // Verify the persona was saved in repository with auto-composed system prompt
        val saved = repo.savedPersonas.firstOrNull { it.name == "Security Lead" }
        assertTrue(saved != null, "Persona should be saved in repository")
        assertEquals("Head of Application Security", saved.roleAndPersona)
        assertEquals("OWASP Top 10, Zero Trust", saved.coreExpertise)
        assertEquals("Inquisitive, skeptical", saved.toneAndVoice)
        assertEquals("Identify authorization bypasses", saved.objective)
        assertTrue(saved.systemPrompt.contains("### ROLE & PERSONA\nHead of Application Security"))
        assertTrue(saved.systemPrompt.contains("### CORE EXPERTISE\nOWASP Top 10, Zero Trust"))
        assertTrue(saved.systemPrompt.contains("### TONE & VOICE\nInquisitive, skeptical"))
        assertTrue(saved.systemPrompt.contains("### OBJECTIVE\nIdentify authorization bypasses"))
    }

    @Test
    fun savePersona_withExplicitSystemPrompt_preservesPromptAndEmitsNavigateBackImmediately() = runTest {
        val repo = FakePersonaRepository()
        val viewModel = PersonaBuilderViewModel(personaRepository = repo)

        viewModel.onIntent(PersonaBuilderIntent.NameChanged("Concise Debater"))
        viewModel.onIntent(PersonaBuilderIntent.SystemPromptChanged("You are a concise debater. State facts directly."))

        // Save persona
        viewModel.onIntent(PersonaBuilderIntent.Save)

        val effect = viewModel.effect.first()
        assertEquals(PersonaBuilderEffect.NavigateBack, effect)

        val saved = repo.savedPersonas.firstOrNull { it.name == "Concise Debater" }
        assertTrue(saved != null)
        assertEquals("You are a concise debater. State facts directly.", saved.systemPrompt)
    }

    @Test
    fun importJson_withCleanJson_loadsDraftAndEmitsSnackbar() = runTest {
        val repo = FakePersonaRepository()
        val viewModel = PersonaBuilderViewModel(personaRepository = repo)

        val jsonInput = """
            {
              "id": "epistemic_skeptic",
              "name": "Epistemic Skeptic",
              "category": "Philosophy",
              "role": "Epistemic Auditor",
              "description": "Questions foundational axioms.",
              "systemPrompt": "Scrutinize every unproven assumption.",
              "ponytail": true
            }
        """.trimIndent()

        viewModel.onIntent(PersonaBuilderIntent.ImportJson(jsonInput))

        val state = viewModel.state.value
        assertEquals("Epistemic Skeptic", state.draft.name)
        assertEquals("Philosophy", state.draft.category)
        assertEquals("Epistemic Auditor", state.draft.role)
        assertEquals("Scrutinize every unproven assumption.", state.draft.systemPrompt)
        assertTrue(state.draft.ponytail)
        assertFalse(state.isImportDialogOpen)
        assertNull(state.importError)

        val effect = viewModel.effect.first()
        assertTrue(effect is PersonaBuilderEffect.ShowSnackbar)
        assertTrue((effect as PersonaBuilderEffect.ShowSnackbar).message.contains("Epistemic Skeptic"))
    }

    @Test
    fun importJson_withMarkdownCodeFences_cleansAndLoadsDraft() = runTest {
        val repo = FakePersonaRepository()
        val viewModel = PersonaBuilderViewModel(personaRepository = repo)

        val markdownInput = """
            Sure! Here is the persona you requested:
            ```json
            {
              "name": "Chaos Engineer",
              "category": "Software Engineering",
              "role": "Fault Injection Lead",
              "description": "Injects synthetic failures.",
              "systemPrompt": "Simulate partition and packet loss scenarios.",
              "ponytail": true
            }
            ```
            Let me know if you need changes.
        """.trimIndent()

        viewModel.onIntent(PersonaBuilderIntent.ImportJson(markdownInput))

        val state = viewModel.state.value
        assertEquals("Chaos Engineer", state.draft.name)
        assertEquals("Fault Injection Lead", state.draft.role)
        assertTrue(state.draft.ponytail)
        assertNull(state.importError)
    }

    @Test
    fun importJson_withAttributesOnly_autoComposesPrompt() = runTest {
        val repo = FakePersonaRepository()
        val viewModel = PersonaBuilderViewModel(personaRepository = repo)

        val jsonInput = """
            {
              "name": "Venture Skeptic",
              "category": "Business",
              "role": "Capital Allocator",
              "roleAndPersona": "Pragmatic late-stage investor",
              "coreExpertise": "Unit economics, churn, LTV/CAC ratios",
              "toneAndVoice": "Incisive, blunt",
              "objective": "Identify unsustainable cash burns"
            }
        """.trimIndent()

        viewModel.onIntent(PersonaBuilderIntent.ImportJson(jsonInput))

        val state = viewModel.state.value
        assertEquals("Venture Skeptic", state.draft.name)
        assertEquals("Unit economics, churn, LTV/CAC ratios", state.draft.coreExpertise)
        assertTrue(state.draft.systemPrompt.contains("### ROLE & PERSONA\nPragmatic late-stage investor"))
        assertTrue(state.draft.systemPrompt.contains("### CORE EXPERTISE\nUnit economics, churn, LTV/CAC ratios"))
        assertTrue(state.draft.systemPrompt.contains("### OBJECTIVE\nIdentify unsustainable cash burns"))
    }

    @Test
    fun importJson_withMalformedContent_setsImportError() = runTest {
        val repo = FakePersonaRepository()
        val viewModel = PersonaBuilderViewModel(personaRepository = repo)

        viewModel.onIntent(PersonaBuilderIntent.ImportJson("this is not json at all {{{"))

        val state = viewModel.state.value
        assertNotNull(state.importError)
        assertTrue(state.importError!!.contains("Failed to parse JSON") || state.importError!!.contains("valid JSON"))
    }

    @Test
    fun chatPersona_sendPromptAndApplyToEditor_loadsPersonaIntoDraft() = runTest {
        val repo = FakePersonaRepository()
        val viewModel = PersonaBuilderViewModel(personaRepository = repo)

        // User sends a message
        viewModel.onIntent(PersonaBuilderIntent.SendChatMessage("Create a penetration tester persona"))
        testScheduler.advanceUntilIdle()

        // Verify message was added and reply was received
        val stateAfterReply = viewModel.state.value
        assertEquals(2, stateAfterReply.chatMessages.size)
        assertEquals("user", stateAfterReply.chatMessages[0].role)
        assertEquals("assistant", stateAfterReply.chatMessages[1].role)

        val assistantMessage = stateAfterReply.chatMessages[1]
        assertNotNull(assistantMessage.parsedPersona)
        assertEquals("AI Security Lead", assistantMessage.parsedPersona!!.name)

        // User applies the persona from chat into the editor
        viewModel.onIntent(PersonaBuilderIntent.ApplyPersonaFromChat(assistantMessage.parsedPersona!!))
        testScheduler.advanceUntilIdle()

        val finalState = viewModel.state.value
        assertEquals("AI Security Lead", finalState.draft.name)
        assertEquals("Adversarial IAM Auditor", finalState.draft.role)
        assertTrue(finalState.draft.ponytail)

        val effect = viewModel.effect.first()
        assertTrue(effect is PersonaBuilderEffect.ShowSnackbar)
    }
}

