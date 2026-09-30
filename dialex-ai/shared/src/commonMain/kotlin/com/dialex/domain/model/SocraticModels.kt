package com.dialex.domain.model

import com.dialex.model.AttachedFile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable

@Serializable
enum class DiscussionMode {
    COUNCIL,
    SOCRATIC_INTERVIEW
}

@Serializable
enum class SocraticStance(val displayName: String, val subtitle: String) {
    RUTHLESS_ELENCHUS("Classic Elenchus", "Contradiction hunting & falsification"),
    MAIEUTIC_ARCHITECT("Maieutic Architecture", "Midwife of latent invariants & boundaries"),
    FIRST_PRINCIPLES("Radical First Principles", "Axiomatic deconstruction down to physics/math"),
    ADVERSARIAL_RED_TEAM("Adversarial Red-Team", "Zero-trust malicious saboteur"),
    APORIA_BOUNDARY_PUSHER("Aporia & Extreme Scale", "Asymptotic pressure at 100x load")
}

@Serializable
enum class SocraticStage(val stepNumber: Int, val label: String) {
    HYPOTHESIS_EXTRACTION(1, "1. Hypothesis Extraction"),
    ASSUMPTION_SURFACING(2, "2. Assumption Surfacing"),
    ELENCHUS_STRESS_TESTING(3, "3. Elenchus Stress-Testing"),
    APORIA_RECONCILIATION(4, "4. Aporia Reconciliation"),
    MAIEUTIC_HARDENING(5, "5. Maieutic Hardening")
}

@Serializable
enum class LedgerItemType {
    HARDENED,
    CONCEDED,
    UNDER_SIEGE
}

@Serializable
data class SocraticLedgerItem(
    val id: String,
    val type: LedgerItemType,
    val statement: String,
    val turn: Int,
    val rationale: String? = null
)

@Serializable
data class SocraticConfig(
    val interviewerPersonaId: String = "",
    val interviewerName: String = "",
    val stance: SocraticStance = SocraticStance.RUTHLESS_ELENCHUS,
    val stage: SocraticStage = SocraticStage.HYPOTHESIS_EXTRACTION,
    val parentDiscussionId: String? = null,
    val parentMessageId: String? = null
)

@Serializable
data class SocraticDigest(
    val initialHypothesis: String,
    val defendedInvariants: List<String> = emptyList(),
    val exposedBlindSpots: List<String> = emptyList(),
    val hardenedThesis: String,
    val residualTensions: List<String> = emptyList(),
    val generatedAtMs: Long = 0L
) {
    val immutableDefended: ImmutableList<String> get() = defendedInvariants.toImmutableList()
    val immutableBlindSpots: ImmutableList<String> get() = exposedBlindSpots.toImmutableList()
    val immutableTensions: ImmutableList<String> get() = residualTensions.toImmutableList()
}

@Serializable
data class SocraticTurnRequest(
    val message: String,
    val topic: String,
    val stance: SocraticStance,
    val stage: SocraticStage,
    val context: String = "",
    val attachedFiles: List<AttachedFile> = emptyList()
)

@Serializable
data class SocraticTurnResponse(
    val probeQuestion: String,
    val newStage: SocraticStage,
    val ledgerUpdates: List<SocraticLedgerItem> = emptyList(),
    val discoveredBlindSpots: List<String> = emptyList(),
    val defendedInvariants: List<String> = emptyList()
) {
    val immutableLedgerUpdates: ImmutableList<SocraticLedgerItem> get() = ledgerUpdates.toImmutableList()
}

@Serializable
data class SocraticElevateRequest(
    val projectId: String,
    val digest: SocraticDigest,
    val parentDiscussionId: String
)

@Serializable
data class ElevateResult(
    val newDiscussionId: String,
    val projectId: String,
    val topic: String
)
