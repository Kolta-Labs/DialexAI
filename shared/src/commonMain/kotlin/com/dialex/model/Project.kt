package com.dialex.model

import com.dialex.domain.model.DiscussionMode
import com.dialex.domain.model.RoundEvidence
import com.dialex.domain.model.SocraticConfig
import com.dialex.domain.model.SocraticDigest
import com.dialex.domain.model.SocraticLedgerItem
import com.dialex.domain.model.TensionPair
import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceScope(
    val folders: List<FolderScope> = emptyList(),
    val commandApproval: CommandApprovalMode = CommandApprovalMode.REQUIRE_APPROVAL,
    val autoApproveSafeCommands: Boolean = true,
)

@Serializable
data class FolderScope(
    val path: String,
    val isReadOnly: Boolean = false,
    val isTrusted: Boolean = true,
    val trustMode: WorkspaceTrustMode = WorkspaceTrustMode.TRUSTED,
    val description: String = "",
)

@Serializable
enum class CommandApprovalMode {
    REQUIRE_APPROVAL,   // Modal/Card prompt for terminal execution
    AUTO_APPROVE_SAFE,  // Read-only/Status commands auto-run
    ALWAYS_ALLOW        // Autonomous execution
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    /** Shared background context inherited by all discussions in this project as a frozen
     * snapshot at the time the discussion is created. */
    val sharedContext: String = "",
    /** Shared instructions inherited by all discussions as a frozen snapshot. */
    val sharedInstructions: String = "",
    /** Default consensus tolerance for new discussions in this project (1.0 = unanimous). */
    val defaultConsensus: Double = 1.0,
    /** Scoped folders & terminal approval policy for agentic codebase interaction. */
    val workspaceScope: WorkspaceScope = WorkspaceScope(),
    /** Project-level debate policy override (null = inherits from global AppState). */
    val debatePolicy: DebatePolicy? = null,
    /** Granular tool and execution permissions for this project. */
    val permissions: PermissionConfig = PermissionConfig(),
)

@Serializable
enum class DiscussionStatus {
    DRAFT,
    RUNNING,
    PAUSED,
    /** Completed normally through max rounds or early consensus. */
    COMPLETED,
    /** Completed with a valid conclusion synthesized after a non-fatal turn dropout. */
    COMPLETED_WITH_WARNING,
    /** Failed completely (only if Round 1 failed and zero content exists). */
    FAILED,
    /** Backwards-compatible alias for COMPLETED. */
    DONE,
    /** Backwards-compatible alias for FAILED. */
    ERROR;

    val isCompleted: Boolean
        get() = this == COMPLETED || this == COMPLETED_WITH_WARNING || this == DONE

    val isFailed: Boolean
        get() = this == FAILED || this == ERROR
}

/** One debate, scoped to a project. Config is empty (agents = []) while status == DRAFT. */
@Serializable
data class Discussion(
    val id: String,
    val projectId: String,
    val name: String,
    val config: DebateConfig,
    val status: DiscussionStatus = DiscussionStatus.DRAFT,
    val transcript: List<DebateMessage> = emptyList(),
    val conclusion: String? = null,
    /** Colloquial discussion summary/recap, distinct from structured deliverable. */
    val summary: String? = null,
    /** Structured decision/action deliverable per DeliverableFormat. */
    val deliverable: String? = null,
    // The "Generate AI handoff prompt" result — persisted (not just in-memory UI state) so
    // it survives navigating away/back or an app restart, and always renders as the last
    // bubble instead of disappearing.
    val handoffPrompt: String? = null,
    /** Attached file references for discussions that have uploaded supporting documents. */
    val attachedFiles: List<AttachedFile> = emptyList(),
    /** Attached workspace folders for codebase / repository context. */
    val attachedFolders: List<FolderScope> = emptyList(),
    /** Cumulative token usage snapshot from the engine (refreshed after each turn). */
    val totalTokensUsed: Long = 0L,
    /** Persisted artifacts saved for this discussion (deliverables, summaries, transcripts). */
    val artifacts: List<DiscussionArtifact> = emptyList(),
    /** Artifact banner IDs dismissed by the user so they are never shown again in this discussion. */
    val dismissedArtifactIds: List<String> = emptyList(),
    /** Dialectic tension pairs identified across rounds. */
    val tensionPairs: List<TensionPair> = emptyList(),
    /** Round-aware dynamic evidence retrieved from the knowledge graph and attached documents. */
    val retrievedEvidence: List<RoundEvidence> = emptyList(),
    /** Creation timestamp in epoch milliseconds. */
    val createdAt: Long = 0L,
    /** Last updated timestamp in epoch milliseconds. */
    val updatedAt: Long = 0L,
    /** Operating mode: COUNCIL (multi-agent) vs SOCRATIC_INTERVIEW (1-on-1). */
    val mode: DiscussionMode = DiscussionMode.COUNCIL,
    /** Configuration and active stage for Socratic sessions. */
    val socraticConfig: SocraticConfig? = null,
    /** Crystallized architectural digest produced at the end of a Socratic interview. */
    val socraticDigest: SocraticDigest? = null,
    /** Live Epistemic Ledger tracking validated invariants, conceded axioms, and active questions. */
    val socraticLedger: List<SocraticLedgerItem> = emptyList(),
    /** Emergency synthesis warning or non-fatal issue description. */
    val warning: String? = null,
    /** True when early consensus termination was triggered. */
    val isConsensusReached: Boolean = false,
    /** Reason for early exit (e.g. "CONSENSUS", "TOKEN_BUDGET", etc.). */
    val earlyExitReason: String? = null,
    /** Bayesian Credence Ledger tracking quantitative probability trajectories and Shannon entropy. */
    val credenceLedger: com.dialex.domain.model.CredenceLedger? = null,
)

/** A generated artifact saved for this discussion (deliverable, summary, or transcript). */
@Serializable
data class DiscussionArtifact(
    val id: String,
    val name: String,
    /** "DELIVERABLE", "SUMMARY", or "TRANSCRIPT". */
    val type: String,
    val format: String = "md",
    val content: String,
    val timestamp: String = "",
    val timestampMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val round: Int? = null,
)

/** A file that has been attached to a discussion or project for AI context injection. */
@Serializable
data class AttachedFile(
    val id: String,
    val name: String,
    /** Human-readable size label, e.g. "12 KB". */
    val sizeLabel: String = "",
    /** Raw text content of the file, stored for AI context injection. */
    val content: String = "",
    /** Target field or scope where this file is attached: "topic", "common_context", "common_info", or "agent_<provider>". */
    val scope: String = "topic",
    /** Estimated token count for display and budgeting. */
    val estimatedTokens: Int = 0,
    /** MIME type detected at upload time. */
    val mimeType: String = "text/plain",
) {
    fun tokenEstimateLabel(): String =
        if (estimatedTokens > 0) {
            val tokenStr = com.dialex.util.AttachmentTokenOptimizer.formatTokenEstimate(estimatedTokens)
            if (sizeLabel.isNotBlank()) "$sizeLabel · $tokenStr" else tokenStr
        } else sizeLabel
}

/** Compaction strategy controls how long debates are summarised to fit within token limits. */
@Serializable
data class CompactionSettings(
    /** Prefer the CLI compaction tool over API when both are available. */
    val preferCli: Boolean = false,
    /** How many turns must accumulate before compaction runs (default: 20). */
    val compactionThreshold: Int = 20,
    /** Model used for compaction summaries (cheap/fast model). */
    val primaryModel: String = "claude-haiku-4-5-20251001",
    /** Fallback strategy when the primary compaction model fails: "api", "cli", or "none". */
    val fallbackStrategy: String = "api",
    /** Custom CLI command for compaction (e.g. "claude --print --model claude-haiku"). */
    val customCliCommand: String = "",
)

/** Everything persisted to disk. */
@Serializable
data class AppState(
    val projects: List<Project> = emptyList(),
    val discussions: List<Discussion> = emptyList(),
    val apiKeys: ApiKeys = ApiKeys(),
    val cliCommands: CliCommands = CliCommands(),
    // Model used only for transcript-compaction summaries (see DebateOrchestrator) — a
    // mechanical task, so a cheap/fast model regardless of what the user picked in the
    // debate itself. Applies in both API and CLI mode. Superseded by CompactionSettings
    // when that is non-null, but kept for backward-compat with older persisted state.
    val compactionModel: String = "claude-haiku-4-5-20251001",
    // Hard ceiling on total tokens (in+out) a single discussion run will spend before it
    // stops itself and asks to be raised — a runaway 3-agent, many-round debate can rack up
    // real cost otherwise. 0 or less disables it. Only API-mode turns report usage, so this
    // can't see CLI-mode token spend.
    val tokenBudget: Int = 1_000_000,
    /** Full compaction configuration — overrides the legacy compactionModel field when present. */
    val compactionSettings: CompactionSettings = CompactionSettings(),
    /** Global master instructions template applied to every new discussion unless overridden
     * at the project or discussion level. */
    val masterInstructions: String = "",
    /** All user-created and built-in personas available in the Persona Picker. */
    val personas: List<PredefinedPersona> = emptyList(),
    /** Global debate defaults (Autopilot, Shared Memory, Moderation, Deliverable, Auto-Save). */
    val debatePolicy: DebatePolicy = DebatePolicy(),
    /** Global default agent sampling parameters per provider. */
    val agentDefaults: ProviderAgentDefaults = ProviderAgentDefaults(),
)
