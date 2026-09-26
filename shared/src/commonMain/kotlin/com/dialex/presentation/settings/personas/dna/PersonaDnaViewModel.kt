package com.dialex.presentation.settings.personas.dna

import androidx.lifecycle.viewModelScope
import com.dialex.domain.model.HeuristicRule
import com.dialex.domain.model.PersonaDNA
import com.dialex.domain.usecase.CompileDnaPromptUseCase
import com.dialex.domain.usecase.ExportPersonaDnaUseCase
import com.dialex.domain.usecase.GetPersonaDnaUseCase
import com.dialex.domain.usecase.ImportPersonaDnaUseCase
import com.dialex.domain.usecase.ListBuiltinHeuristicsUseCase
import com.dialex.domain.usecase.UpdatePersonaDnaUseCase
import com.dialex.presentation.base.MviViewModel
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.launch

class PersonaDnaViewModel(
    private val getPersonaDnaUseCase: GetPersonaDnaUseCase,
    private val updatePersonaDnaUseCase: UpdatePersonaDnaUseCase,
    private val listBuiltinHeuristicsUseCase: ListBuiltinHeuristicsUseCase,
    private val compileDnaPromptUseCase: CompileDnaPromptUseCase,
    private val importPersonaDnaUseCase: ImportPersonaDnaUseCase,
    private val exportPersonaDnaUseCase: ExportPersonaDnaUseCase,
    initialDna: PersonaDNA? = null,
    private val personaId: String? = null
) : MviViewModel<PersonaDnaState, PersonaDnaIntent, PersonaDnaEffect>(
    PersonaDnaState(
        dna = initialDna ?: PersonaDnaState().dna.copy(
            id = personaId ?: "",
            name = if (personaId.isNullOrBlank()) "Custom Persona" else personaId
        )
    )
) {
    init {
        loadBuiltinHeuristics()
        if (initialDna == null && !personaId.isNullOrBlank()) {
            loadPersonaDna(personaId)
        }
    }

    private fun loadBuiltinHeuristics() {
        viewModelScope.launch {
            val result = listBuiltinHeuristicsUseCase()
            result.onSuccess { heuristics ->
                setState { copy(availableHeuristics = heuristics.toPersistentList()) }
            }
        }
    }

    private fun loadPersonaDna(id: String) {
        viewModelScope.launch {
            setState { copy(isLoading = true, error = null) }
            val result = getPersonaDnaUseCase(id)
            result.fold(
                onSuccess = { loadedDna ->
                    setState { copy(dna = loadedDna, isLoading = false) }
                },
                onFailure = { err ->
                    setState { copy(isLoading = false, error = err.message) }
                }
            )
        }
    }

    override fun onIntent(intent: PersonaDnaIntent) {
        when (intent) {
            is PersonaDnaIntent.SelectTab -> setState { copy(activeTab = intent.tab) }

            // Core Identity
            is PersonaDnaIntent.UpdateTitle -> setState {
                copy(dna = dna.copy(coreIdentity = dna.coreIdentity.copy(title = intent.title)))
            }
            is PersonaDnaIntent.UpdateBackground -> setState {
                copy(dna = dna.copy(coreIdentity = dna.coreIdentity.copy(background = intent.background)))
            }
            is PersonaDnaIntent.UpdateDomainAuthority -> setState {
                copy(dna = dna.copy(coreIdentity = dna.coreIdentity.copy(domainAuthority = intent.authority)))
            }
            is PersonaDnaIntent.AddCredential -> setState {
                if (intent.credential.isNotBlank() && !dna.coreIdentity.credentials.contains(intent.credential)) {
                    copy(dna = dna.copy(coreIdentity = dna.coreIdentity.copy(
                        credentials = dna.coreIdentity.credentials + intent.credential
                    )))
                } else this
            }
            is PersonaDnaIntent.RemoveCredential -> setState {
                copy(dna = dna.copy(coreIdentity = dna.coreIdentity.copy(
                    credentials = dna.coreIdentity.credentials.filter { it != intent.credential }
                )))
            }

            // Epistemic Bias
            is PersonaDnaIntent.UpdatePrimaryMode -> setState {
                copy(dna = dna.copy(epistemicBias = dna.epistemicBias.copy(primaryMode = intent.mode)))
            }
            is PersonaDnaIntent.UpdateTheoryVsPractice -> setState {
                copy(dna = dna.copy(epistemicBias = dna.epistemicBias.copy(theoryVsPractice = intent.value.coerceIn(0.0, 1.0))))
            }
            is PersonaDnaIntent.UpdateNoveltyVsProvenance -> setState {
                copy(dna = dna.copy(epistemicBias = dna.epistemicBias.copy(noveltyVsProvenance = intent.value.coerceIn(0.0, 1.0))))
            }
            is PersonaDnaIntent.UpdateSafetyVsVelocity -> setState {
                copy(dna = dna.copy(epistemicBias = dna.epistemicBias.copy(safetyVsVelocity = intent.value.coerceIn(0.0, 1.0))))
            }
            is PersonaDnaIntent.UpdateRigorThreshold -> setState {
                copy(dna = dna.copy(epistemicBias = dna.epistemicBias.copy(rigorThreshold = intent.value.coerceIn(0.0, 1.0))))
            }

            // Communication Vector
            is PersonaDnaIntent.UpdateTone -> setState {
                copy(dna = dna.copy(communicationVector = dna.communicationVector.copy(tone = intent.tone)))
            }
            is PersonaDnaIntent.UpdateFormalityLevel -> setState {
                copy(dna = dna.copy(communicationVector = dna.communicationVector.copy(formalityLevel = intent.level.coerceIn(1, 5))))
            }
            is PersonaDnaIntent.UpdateTargetSentenceCeiling -> setState {
                copy(dna = dna.copy(communicationVector = dna.communicationVector.copy(targetSentenceCeiling = intent.ceiling.coerceAtLeast(1))))
            }
            is PersonaDnaIntent.AddRhetoricalDevice -> setState {
                if (intent.device.isNotBlank() && !dna.communicationVector.rhetoricalDevices.contains(intent.device)) {
                    copy(dna = dna.copy(communicationVector = dna.communicationVector.copy(
                        rhetoricalDevices = dna.communicationVector.rhetoricalDevices + intent.device
                    )))
                } else this
            }
            is PersonaDnaIntent.RemoveRhetoricalDevice -> setState {
                copy(dna = dna.copy(communicationVector = dna.communicationVector.copy(
                    rhetoricalDevices = dna.communicationVector.rhetoricalDevices.filter { it != intent.device }
                )))
            }
            is PersonaDnaIntent.UpdateSyntaxPattern -> setState {
                copy(dna = dna.copy(communicationVector = dna.communicationVector.copy(syntaxPattern = intent.pattern)))
            }

            // Heuristics
            is PersonaDnaIntent.ToggleBuiltinHeuristic -> setState {
                val exists = dna.heuristicLibrary.any { it.id == intent.rule.id }
                val updated = if (exists) {
                    dna.heuristicLibrary.filter { it.id != intent.rule.id }
                } else {
                    dna.heuristicLibrary + intent.rule
                }
                copy(dna = dna.copy(heuristicLibrary = updated))
            }
            is PersonaDnaIntent.AddCustomHeuristic -> setState {
                val newRule = HeuristicRule(
                    id = intent.id.ifBlank { "heur_custom_${dna.heuristicLibrary.size + 1}" },
                    name = intent.name,
                    formulaOrMaxime = intent.formulaOrMaxime,
                    triggerCondition = intent.triggerCondition,
                    applicationDirective = intent.applicationDirective
                )
                copy(dna = dna.copy(heuristicLibrary = dna.heuristicLibrary + newRule))
            }
            is PersonaDnaIntent.RemoveHeuristic -> setState {
                copy(dna = dna.copy(heuristicLibrary = dna.heuristicLibrary.filter { it.id != intent.id }))
            }

            // Taboo Space
            is PersonaDnaIntent.AddForbiddenArgument -> setState {
                if (intent.argument.isNotBlank() && !dna.tabooSpace.forbiddenArguments.contains(intent.argument)) {
                    copy(dna = dna.copy(tabooSpace = dna.tabooSpace.copy(
                        forbiddenArguments = dna.tabooSpace.forbiddenArguments + intent.argument
                    )))
                } else this
            }
            is PersonaDnaIntent.RemoveForbiddenArgument -> setState {
                copy(dna = dna.copy(tabooSpace = dna.tabooSpace.copy(
                    forbiddenArguments = dna.tabooSpace.forbiddenArguments.filter { it != intent.argument }
                )))
            }
            is PersonaDnaIntent.AddRejectedFallacy -> setState {
                if (intent.fallacy.isNotBlank() && !dna.tabooSpace.rejectedFallacies.contains(intent.fallacy)) {
                    copy(dna = dna.copy(tabooSpace = dna.tabooSpace.copy(
                        rejectedFallacies = dna.tabooSpace.rejectedFallacies + intent.fallacy
                    )))
                } else this
            }
            is PersonaDnaIntent.RemoveRejectedFallacy -> setState {
                copy(dna = dna.copy(tabooSpace = dna.tabooSpace.copy(
                    rejectedFallacies = dna.tabooSpace.rejectedFallacies.filter { it != intent.fallacy }
                )))
            }
            is PersonaDnaIntent.AddIntolerableBuzzword -> setState {
                if (intent.buzzword.isNotBlank() && !dna.tabooSpace.intolerableBuzzwords.contains(intent.buzzword)) {
                    copy(dna = dna.copy(tabooSpace = dna.tabooSpace.copy(
                        intolerableBuzzwords = dna.tabooSpace.intolerableBuzzwords + intent.buzzword
                    )))
                } else this
            }
            is PersonaDnaIntent.RemoveIntolerableBuzzword -> setState {
                copy(dna = dna.copy(tabooSpace = dna.tabooSpace.copy(
                    intolerableBuzzwords = dna.tabooSpace.intolerableBuzzwords.filter { it != intent.buzzword }
                )))
            }
            is PersonaDnaIntent.UpdatePenaltyAction -> setState {
                copy(dna = dna.copy(tabooSpace = dna.tabooSpace.copy(penaltyAction = intent.penalty)))
            }

            // Domain Ontology
            is PersonaDnaIntent.AddMandatoryStandard -> setState {
                if (intent.standard.isNotBlank() && !dna.domainOntology.mandatoryStandards.contains(intent.standard)) {
                    copy(dna = dna.copy(domainOntology = dna.domainOntology.copy(
                        mandatoryStandards = dna.domainOntology.mandatoryStandards + intent.standard
                    )))
                } else this
            }
            is PersonaDnaIntent.RemoveMandatoryStandard -> setState {
                copy(dna = dna.copy(domainOntology = dna.domainOntology.copy(
                    mandatoryStandards = dna.domainOntology.mandatoryStandards.filter { it != intent.standard }
                )))
            }
            is PersonaDnaIntent.AddAuthoritativeRFC -> setState {
                if (intent.rfc.isNotBlank() && !dna.domainOntology.authoritativeRFCs.contains(intent.rfc)) {
                    copy(dna = dna.copy(domainOntology = dna.domainOntology.copy(
                        authoritativeRFCs = dna.domainOntology.authoritativeRFCs + intent.rfc
                    )))
                } else this
            }
            is PersonaDnaIntent.RemoveAuthoritativeRFC -> setState {
                copy(dna = dna.copy(domainOntology = dna.domainOntology.copy(
                    authoritativeRFCs = dna.domainOntology.authoritativeRFCs.filter { it != intent.rfc }
                )))
            }
            is PersonaDnaIntent.AddSpecializedLexicon -> setState {
                if (intent.term.isNotBlank() && !dna.domainOntology.specializedLexicon.contains(intent.term)) {
                    copy(dna = dna.copy(domainOntology = dna.domainOntology.copy(
                        specializedLexicon = dna.domainOntology.specializedLexicon + intent.term
                    )))
                } else this
            }
            is PersonaDnaIntent.RemoveSpecializedLexicon -> setState {
                copy(dna = dna.copy(domainOntology = dna.domainOntology.copy(
                    specializedLexicon = dna.domainOntology.specializedLexicon.filter { it != intent.term }
                )))
            }
            is PersonaDnaIntent.UpdateEnforceFormalCitations -> setState {
                copy(dna = dna.copy(domainOntology = dna.domainOntology.copy(enforceFormalCitations = intent.enforce)))
            }

            // Adversarial Posture
            is PersonaDnaIntent.UpdateCombatStance -> setState {
                copy(dna = dna.copy(adversarialPosture = dna.adversarialPosture.copy(stance = intent.stance)))
            }
            is PersonaDnaIntent.UpdateTenacityScore -> setState {
                copy(dna = dna.copy(adversarialPosture = dna.adversarialPosture.copy(tenacityScore = intent.tenacity.coerceIn(0.0, 1.0))))
            }
            is PersonaDnaIntent.UpdateCounterAttackMethod -> setState {
                copy(dna = dna.copy(adversarialPosture = dna.adversarialPosture.copy(counterAttackMethod = intent.method)))
            }
            is PersonaDnaIntent.UpdateConcedeCondition -> setState {
                copy(dna = dna.copy(adversarialPosture = dna.adversarialPosture.copy(concedeCondition = intent.condition)))
            }

            // Synthesis Preference
            is PersonaDnaIntent.UpdateSynthesisStyle -> setState {
                copy(dna = dna.copy(synthesisPreference = dna.synthesisPreference.copy(style = intent.style)))
            }
            is PersonaDnaIntent.UpdateAllowMinorityReport -> setState {
                copy(dna = dna.copy(synthesisPreference = dna.synthesisPreference.copy(allowMinorityReport = intent.allow)))
            }
            is PersonaDnaIntent.UpdateMinorityReportCriteria -> setState {
                copy(dna = dna.copy(synthesisPreference = dna.synthesisPreference.copy(minorityReportCriteria = intent.criteria)))
            }
            is PersonaDnaIntent.UpdateCompromiseCondition -> setState {
                copy(dna = dna.copy(synthesisPreference = dna.synthesisPreference.copy(compromiseCondition = intent.condition)))
            }

            // Actions
            PersonaDnaIntent.CompilePrompt -> compilePrompt()
            is PersonaDnaIntent.OpenImportExportDialog -> {
                setState {
                    copy(
                        isImportExportDialogOpen = true,
                        importExportMode = intent.mode,
                        importExportFormat = intent.format,
                        importExportError = null,
                        importExportText = if (intent.mode == ImportExportMode.EXPORT) "" else importExportText
                    )
                }
                if (intent.mode == ImportExportMode.EXPORT) {
                    onIntent(PersonaDnaIntent.ExecuteExport)
                }
            }
            PersonaDnaIntent.DismissImportExportDialog -> setState {
                copy(isImportExportDialogOpen = false, importExportError = null)
            }
            is PersonaDnaIntent.ImportExportTextChanged -> setState {
                copy(importExportText = intent.text, importExportError = null)
            }
            is PersonaDnaIntent.SetImportExportFormat -> {
                setState { copy(importExportFormat = intent.format) }
                if (state.value.importExportMode == ImportExportMode.EXPORT) {
                    onIntent(PersonaDnaIntent.ExecuteExport)
                }
            }
            PersonaDnaIntent.ExecuteExport -> exportDna()
            PersonaDnaIntent.ExecuteImport -> importDna()
            PersonaDnaIntent.ResetToDefault -> setState {
                copy(dna = PersonaDnaState().dna.copy(id = dna.id, name = dna.name, role = dna.role))
            }
            is PersonaDnaIntent.ApplyDna -> {
                setState { copy(dna = intent.dna) }
                sendEffect(PersonaDnaEffect.DnaSaved(intent.dna))
            }
        }
    }

    private fun compilePrompt() {
        viewModelScope.launch {
            setState { copy(isCompiling = true) }
            val result = compileDnaPromptUseCase(state.value.dna)
            result.fold(
                onSuccess = { compiled ->
                    setState { copy(compiledPrompt = compiled, isCompiling = false, activeTab = DnaStudioTab.COMPILED_PROMPT) }
                    sendEffect(PersonaDnaEffect.PromptCompiled(compiled))
                },
                onFailure = { err ->
                    setState { copy(isCompiling = false) }
                    sendEffect(PersonaDnaEffect.ShowSnackbar("Prompt compilation failed: ${err.message}"))
                }
            )
        }
    }

    private fun exportDna() {
        val targetId = state.value.dna.id.ifBlank { personaId ?: "the-risk-analyst" }
        viewModelScope.launch {
            val formatStr = state.value.importExportFormat.name.lowercase()
            val result = exportPersonaDnaUseCase(targetId, formatStr)
            result.fold(
                onSuccess = { content ->
                    setState { copy(importExportText = content, importExportError = null) }
                    sendEffect(PersonaDnaEffect.DnaExported(content, state.value.importExportFormat))
                },
                onFailure = { err ->
                    setState { copy(importExportError = "Export failed: ${err.message}") }
                }
            )
        }
    }

    private fun importDna() {
        val raw = state.value.importExportText.trim()
        if (raw.isEmpty()) {
            setState { copy(importExportError = "Import payload cannot be empty") }
            return
        }
        viewModelScope.launch {
            val formatStr = state.value.importExportFormat.name.lowercase()
            val result = importPersonaDnaUseCase(raw, formatStr)
            result.fold(
                onSuccess = { importedDna ->
                    setState {
                        copy(
                            dna = importedDna,
                            isImportExportDialogOpen = false,
                            importExportError = null,
                            compiledPrompt = null
                        )
                    }
                    sendEffect(PersonaDnaEffect.DnaSaved(importedDna))
                    sendEffect(PersonaDnaEffect.ShowSnackbar("Imported Persona DNA '${importedDna.name}' successfully"))
                },
                onFailure = { err ->
                    setState { copy(importExportError = "Invalid DNA: ${err.message}") }
                }
            )
        }
    }
}
