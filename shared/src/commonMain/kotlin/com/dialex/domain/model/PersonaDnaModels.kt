package com.dialex.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class PrimaryReasoningMode {
    FIRST_PRINCIPLES,
    EMPIRICAL_STATISTICAL,
    HISTORICAL_ANALOGY,
    PRAGMATIC_ENGINEERING,
    FORMAL_LOGICAL
}

@Serializable
enum class CombatStance {
    UNYIELDING_DOGMATIC,
    COUNTER_ATTACKING,
    SOCRATIC_INVERTER,
    ANALYTICAL_DECONSTRUCTOR,
    PRAGMATIC_ACCOMMODATOR
}

@Serializable
enum class SynthesisStyle {
    SEEK_SYNTHESIS,
    HOLD_MINORITY_REPORT,
    CONDITIONAL_COMPROMISE
}

@Serializable
data class HeuristicRule(
    val id: String,
    val name: String,
    val formulaOrMaxime: String,
    val triggerCondition: String = "",
    val applicationDirective: String = ""
)

@Serializable
data class TabooSpace(
    val forbiddenArguments: List<String> = emptyList(),
    val rejectedFallacies: List<String> = emptyList(),
    val intolerableBuzzwords: List<String> = emptyList(),
    val penaltyAction: String = ""
)

@Serializable
data class CoreIdentity(
    val title: String = "",
    val background: String = "",
    val domainAuthority: String = "",
    val credentials: List<String> = emptyList()
)

@Serializable
data class EpistemicBias(
    val primaryMode: PrimaryReasoningMode = PrimaryReasoningMode.PRAGMATIC_ENGINEERING,
    val theoryVsPractice: Double = 0.5,
    val noveltyVsProvenance: Double = 0.5,
    val safetyVsVelocity: Double = 0.5,
    val rigorThreshold: Double = 0.8
)

@Serializable
data class CommunicationVector(
    val tone: String = "CONCISE_BLUNT",
    val formalityLevel: Int = 3,
    val targetSentenceCeiling: Int = 4,
    val rhetoricalDevices: List<String> = emptyList(),
    val syntaxPattern: String = ""
)

@Serializable
data class DomainOntology(
    val mandatoryStandards: List<String> = emptyList(),
    val authoritativeRFCs: List<String> = emptyList(),
    val specializedLexicon: List<String> = emptyList(),
    val enforceFormalCitations: Boolean = false
)

@Serializable
data class AdversarialPosture(
    val stance: CombatStance = CombatStance.COUNTER_ATTACKING,
    val tenacityScore: Double = 0.8,
    val counterAttackMethod: String = "",
    val concedeCondition: String = ""
)

@Serializable
data class SynthesisPreference(
    val style: SynthesisStyle = SynthesisStyle.CONDITIONAL_COMPROMISE,
    val allowMinorityReport: Boolean = true,
    val minorityReportCriteria: String = "",
    val compromiseCondition: String = ""
)

@Serializable
data class PersonaDNA(
    val schemaVersion: String = "dialex.dna/v1.0",
    val id: String,
    val name: String,
    val role: String,
    val category: String = "Systems Architecture",
    val icon: String = "code",
    val coreIdentity: CoreIdentity = CoreIdentity(),
    val epistemicBias: EpistemicBias = EpistemicBias(),
    val communicationVector: CommunicationVector = CommunicationVector(),
    val heuristicLibrary: List<HeuristicRule> = emptyList(),
    val tabooSpace: TabooSpace = TabooSpace(),
    val domainOntology: DomainOntology = DomainOntology(),
    val adversarialPosture: AdversarialPosture = AdversarialPosture(),
    val synthesisPreference: SynthesisPreference = SynthesisPreference(),
    val rawCustomPrompt: String = ""
)

@Serializable
data class CompileDnaRequest(
    val dna: PersonaDNA
)

@Serializable
data class CompileDnaResponse(
    val compiledPrompt: String
)
