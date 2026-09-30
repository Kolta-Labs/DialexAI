package com.dialex.presentation.settings.personas.dna

import com.dialex.domain.model.AdversarialPosture
import com.dialex.domain.model.CombatStance
import com.dialex.domain.model.CommunicationVector
import com.dialex.domain.model.CoreIdentity
import com.dialex.domain.model.DomainOntology
import com.dialex.domain.model.EpistemicBias
import com.dialex.domain.model.HeuristicRule
import com.dialex.domain.model.PersonaDNA
import com.dialex.domain.model.PrimaryReasoningMode
import com.dialex.domain.model.SynthesisPreference
import com.dialex.domain.model.SynthesisStyle
import com.dialex.domain.model.TabooSpace
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class DnaStudioTab(val title: String, val icon: String) {
    CORE_IDENTITY("Identity & Domain", "🏛️"),
    EPISTEMIC_BIAS("Epistemic Bias & Vector", "⚖️"),
    HEURISTICS_TABOOS("Heuristics & Taboos", "📐"),
    POSTURE_SYNTHESIS("Adversarial & Synthesis", "⚔️"),
    COMPILED_PROMPT("DNA Mandate Compiler", "⚡")
}

enum class DnaFormat {
    YAML,
    JSON
}

enum class ImportExportMode {
    IMPORT,
    EXPORT
}

data class PersonaDnaState(
    val dna: PersonaDNA = PersonaDNA(
        id = "",
        name = "",
        role = "Epistemic Analyst",
        category = "Systems Architecture",
        coreIdentity = CoreIdentity(
            title = "Principal Epistemic Analyst",
            background = "Deep systems engineering & formal analysis",
            domainAuthority = "Distributed systems, fault tolerance, reliability engineering",
            credentials = listOf("Formal Methods", "Fault Tolerance", "RFC Author")
        ),
        epistemicBias = EpistemicBias(
            primaryMode = PrimaryReasoningMode.FIRST_PRINCIPLES,
            theoryVsPractice = 0.6,
            noveltyVsProvenance = 0.8,
            safetyVsVelocity = 0.85,
            rigorThreshold = 0.9
        ),
        communicationVector = CommunicationVector(
            tone = "CONCISE_INCISIVE",
            formalityLevel = 4,
            targetSentenceCeiling = 4,
            rhetoricalDevices = listOf("reductio-ad-absurdum", "inversion"),
            syntaxPattern = "structured-bulleted"
        ),
        heuristicLibrary = emptyList(),
        tabooSpace = TabooSpace(
            forbiddenArguments = listOf("hand-waving", "appeal-to-popularity"),
            rejectedFallacies = listOf("sunk-cost", "false-dichotomy"),
            intolerableBuzzwords = listOf("synergy", "paradigm-shift", "turnkey"),
            penaltyAction = "IMMEDIATE_REFUTATION"
        ),
        domainOntology = DomainOntology(
            mandatoryStandards = listOf("RFC 793", "CAP Theorem", "PACELC"),
            authoritativeRFCs = listOf("RFC-1122", "RFC-7540"),
            specializedLexicon = listOf("failure-domain", "split-brain", "backpressure"),
            enforceFormalCitations = true
        ),
        adversarialPosture = AdversarialPosture(
            stance = CombatStance.SOCRATIC_INVERTER,
            tenacityScore = 0.8,
            counterAttackMethod = "IDENTIFY_HIDDEN_AXIOM_AND_DISPROVE",
            concedeCondition = "RIGOROUS_EMPIRICAL_OR_FORMAL_PROOF"
        ),
        synthesisPreference = SynthesisPreference(
            style = SynthesisStyle.CONDITIONAL_COMPROMISE,
            allowMinorityReport = true,
            minorityReportCriteria = "UNMITIGATED_CATASTROPHIC_FAILURE_MODE",
            compromiseCondition = "BOUNDED_RISK_ENVELOPE"
        )
    ),
    val availableHeuristics: ImmutableList<HeuristicRule> = persistentListOf(),
    val compiledPrompt: String? = null,
    val isCompiling: Boolean = false,
    val activeTab: DnaStudioTab = DnaStudioTab.CORE_IDENTITY,
    val isImportExportDialogOpen: Boolean = false,
    val importExportMode: ImportExportMode = ImportExportMode.EXPORT,
    val importExportFormat: DnaFormat = DnaFormat.YAML,
    val importExportText: String = "",
    val importExportError: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

sealed interface PersonaDnaIntent {
    data class SelectTab(val tab: DnaStudioTab) : PersonaDnaIntent

    // Core Identity
    data class UpdateTitle(val title: String) : PersonaDnaIntent
    data class UpdateBackground(val background: String) : PersonaDnaIntent
    data class UpdateDomainAuthority(val authority: String) : PersonaDnaIntent
    data class AddCredential(val credential: String) : PersonaDnaIntent
    data class RemoveCredential(val credential: String) : PersonaDnaIntent

    // Epistemic Bias
    data class UpdatePrimaryMode(val mode: PrimaryReasoningMode) : PersonaDnaIntent
    data class UpdateTheoryVsPractice(val value: Double) : PersonaDnaIntent
    data class UpdateNoveltyVsProvenance(val value: Double) : PersonaDnaIntent
    data class UpdateSafetyVsVelocity(val value: Double) : PersonaDnaIntent
    data class UpdateRigorThreshold(val value: Double) : PersonaDnaIntent

    // Communication Vector
    data class UpdateTone(val tone: String) : PersonaDnaIntent
    data class UpdateFormalityLevel(val level: Int) : PersonaDnaIntent
    data class UpdateTargetSentenceCeiling(val ceiling: Int) : PersonaDnaIntent
    data class AddRhetoricalDevice(val device: String) : PersonaDnaIntent
    data class RemoveRhetoricalDevice(val device: String) : PersonaDnaIntent
    data class UpdateSyntaxPattern(val pattern: String) : PersonaDnaIntent

    // Heuristics
    data class ToggleBuiltinHeuristic(val rule: HeuristicRule) : PersonaDnaIntent
    data class AddCustomHeuristic(val id: String, val name: String, val formulaOrMaxime: String, val triggerCondition: String, val applicationDirective: String) : PersonaDnaIntent
    data class RemoveHeuristic(val id: String) : PersonaDnaIntent

    // Taboo Space
    data class AddForbiddenArgument(val argument: String) : PersonaDnaIntent
    data class RemoveForbiddenArgument(val argument: String) : PersonaDnaIntent
    data class AddRejectedFallacy(val fallacy: String) : PersonaDnaIntent
    data class RemoveRejectedFallacy(val fallacy: String) : PersonaDnaIntent
    data class AddIntolerableBuzzword(val buzzword: String) : PersonaDnaIntent
    data class RemoveIntolerableBuzzword(val buzzword: String) : PersonaDnaIntent
    data class UpdatePenaltyAction(val penalty: String) : PersonaDnaIntent

    // Domain Ontology
    data class AddMandatoryStandard(val standard: String) : PersonaDnaIntent
    data class RemoveMandatoryStandard(val standard: String) : PersonaDnaIntent
    data class AddAuthoritativeRFC(val rfc: String) : PersonaDnaIntent
    data class RemoveAuthoritativeRFC(val rfc: String) : PersonaDnaIntent
    data class AddSpecializedLexicon(val term: String) : PersonaDnaIntent
    data class RemoveSpecializedLexicon(val term: String) : PersonaDnaIntent
    data class UpdateEnforceFormalCitations(val enforce: Boolean) : PersonaDnaIntent

    // Adversarial Posture
    data class UpdateCombatStance(val stance: CombatStance) : PersonaDnaIntent
    data class UpdateTenacityScore(val tenacity: Double) : PersonaDnaIntent
    data class UpdateCounterAttackMethod(val method: String) : PersonaDnaIntent
    data class UpdateConcedeCondition(val condition: String) : PersonaDnaIntent

    // Synthesis Preference
    data class UpdateSynthesisStyle(val style: SynthesisStyle) : PersonaDnaIntent
    data class UpdateAllowMinorityReport(val allow: Boolean) : PersonaDnaIntent
    data class UpdateMinorityReportCriteria(val criteria: String) : PersonaDnaIntent
    data class UpdateCompromiseCondition(val condition: String) : PersonaDnaIntent

    // Actions
    data object CompilePrompt : PersonaDnaIntent
    data class OpenImportExportDialog(val mode: ImportExportMode, val format: DnaFormat = DnaFormat.YAML) : PersonaDnaIntent
    data object DismissImportExportDialog : PersonaDnaIntent
    data class ImportExportTextChanged(val text: String) : PersonaDnaIntent
    data class SetImportExportFormat(val format: DnaFormat) : PersonaDnaIntent
    data object ExecuteImport : PersonaDnaIntent
    data object ExecuteExport : PersonaDnaIntent
    data object ResetToDefault : PersonaDnaIntent
    data class ApplyDna(val dna: PersonaDNA) : PersonaDnaIntent
}

sealed interface PersonaDnaEffect {
    data class ShowSnackbar(val message: String) : PersonaDnaEffect
    data class PromptCompiled(val prompt: String) : PersonaDnaEffect
    data class DnaExported(val content: String, val format: DnaFormat) : PersonaDnaEffect
    data class DnaSaved(val dna: PersonaDNA) : PersonaDnaEffect
}
