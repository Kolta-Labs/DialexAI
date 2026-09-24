package com.dialex.presentation.chat

import com.dialex.model.Discussion
import com.dialex.model.DeliverableFormat
import com.dialex.model.Provider
import io.github.koltalabs.kolt.utils.state.AsyncState
import com.dialex.presentation.setup.TokenWarningLevel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

// ──────────────────────────────────────────────────────────────────────────────
// Chat Screen Contract — State / Intent / Effect
// ──────────────────────────────────────────────────────────────────────────────

data class ChatState(
    val discussion: Discussion? = null,
    /** Progressive token warning level, resolved by ViewModel — composable never does math. */
    val tokenWarningLevel: TokenWarningLevel = TokenWarningLevel.None,
    /** Human-readable token progress label, e.g. "145,000 / 200,000 tokens (72%)". */
    val tokenProgressLabel: String = "",
    /** Whether a Pause request has been queued (discussion still RUNNING, waiting for turn end). */
    val pauseRequested: Boolean = false,
    /** Next speaker while RUNNING — drives the typing-indicator bubble. */
    val nextSpeakerProvider: Provider? = null,
    val handoffState: HandoffState = HandoffState.Idle,
    /** Per-agent token usage breakdown for the usage modal. */
    val usageBreakdown: ImmutableList<AgentUsage> = persistentListOf(),
    val showUsageModal: Boolean = false,
    /** Whether an action like Resume/Pause/Stop/Restart is in-flight (disables duplicate clicks). */
    val isActionInProgress: Boolean = false,
    /** Whether the Artifacts Repository dialog/drawer is visible. */
    val artifactsModalOpen: Boolean = false,
    /** In-flight deliverable format generation for the pill progress state. */
    val generatingDeliverableFormat: DeliverableFormat? = null,
    /** Folders that were removed or invalid on disk, blocking further discussion turns until user acts. */
    val invalidFolders: ImmutableList<Pair<com.dialex.model.FolderScope, String>> = persistentListOf(),
    /** In-flight user comment held in queue while discussion is running (fired on next turn or via Interrupt). */
    val queuedUserComment: String? = null,
    /** Cumulative financial spend in USD calculated from exact provider token pricing. */
    val totalSpendUsd: Double = 0.0,
    /** Percentage of prompt input tokens resolved via prompt caching (0-100%). */
    val cachedTokensPercent: Int = 0,
    val loadAsync: AsyncState<Unit> = AsyncState.Loading,
    /** True while the discussion is RUNNING but both the SSE stream and the fallback
     *  poll are failing — the engine is unreachable. Clears automatically once a poll
     *  or stream update succeeds again. */
    val engineConnectionLost: Boolean = false,
)

/** One row in the token usage modal — one entry per agent seat. */
data class AgentUsage(
    val agentName: String,
    val provider: Provider,
    val tokensIn: Long,
    val tokensOut: Long,
    val estimatedCostUsd: Double,
)

/** Tracks the state of an in-flight "Generate AI handoff prompt" request. */
sealed interface HandoffState {
    data object Idle : HandoffState
    data object Loading : HandoffState
    data class Failed(val message: String) : HandoffState
}

sealed interface ChatIntent {
    data object Pause : ChatIntent
    data object HardStop : ChatIntent
    data object Resume : ChatIntent
    data class RestartDebate(val additionalRounds: Int = 3, val unlimited: Boolean = false) : ChatIntent
    data class SendUserComment(val text: String) : ChatIntent
    data object InterruptAndSendHeldComment : ChatIntent
    data object CancelHeldComment : ChatIntent
    data class SaveArtifacts(val artifacts: List<com.dialex.model.DiscussionArtifact>) : ChatIntent
    data object ToggleArtifactsModal : ChatIntent
    data object EditSetup : ChatIntent
    data object ExportMarkdown : ChatIntent
    data object GenerateHandoffPrompt : ChatIntent
    data object DuplicateConfig : ChatIntent
    data object ShowUsageModal : ChatIntent
    data object DismissUsageModal : ChatIntent
    data object ScrollToBottom : ChatIntent
    data class ApproveCommand(val commandId: String) : ChatIntent
    data class RejectCommand(val commandId: String) : ChatIntent
    data class DeleteArtifact(val artifactId: String) : ChatIntent
    data class DismissArtifactBanner(val artifactId: String) : ChatIntent
    data class GenerateAlternativeDeliverable(val format: DeliverableFormat) : ChatIntent
    data class RenameDiscussion(val newName: String) : ChatIntent
    data object GenerateSummaryTitle : ChatIntent
    data class RemoveInvalidFolder(val path: String) : ChatIntent
    data object RetryFolderValidation : ChatIntent
}

sealed interface ChatEffect {
    data object NavigateBack : ChatEffect
    data class NavigateToSetup(val discussionId: String) : ChatEffect
    data class ExportMarkdown(val markdown: String, val suggestedFileName: String) : ChatEffect
    data class ShowSnackbar(val message: String) : ChatEffect
    data object ScrollToBottom : ChatEffect
}
