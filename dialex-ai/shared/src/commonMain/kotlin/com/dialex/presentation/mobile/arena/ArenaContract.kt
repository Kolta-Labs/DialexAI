package com.dialex.presentation.mobile.arena

import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

object ArenaContract {
    data class State(
        val discussion: Discussion? = null,
        val activeSpeakerIndex: Int = 0,
        val isSpeaking: Boolean = false,
        val consensusScore: Float = 0.0f,
        val currentRound: Int = 1,
        val maxRounds: Int = 3,
        val activeRoundTurns: ImmutableList<DebateMessage> = persistentListOf(),
        val selectedTurnIndex: Int = 0,
        val isAudioPlaying: Boolean = false,
        val audioPlaybackSpeed: Float = 1.0f,
        val isInterjectionDialogOpen: Boolean = false,
        val isMemoSheetOpen: Boolean = false,
        val error: String? = null
    )

    sealed interface Intent {
        data class SelectTurn(val index: Int) : Intent
        data object ToggleAudioPlayback : Intent
        data class SetPlaybackSpeed(val speed: Float) : Intent
        data object PauseDebate : Intent
        data object ResumeDebate : Intent
        data object HardStopDebate : Intent
        data class SubmitInterjection(val instruction: String) : Intent
        data object OpenExecutiveMemo : Intent
        data object CloseExecutiveMemo : Intent
        data object OpenInterjectionDialog : Intent
        data object CloseInterjectionDialog : Intent
    }

    sealed interface Effect {
        data class TriggerHapticPulse(val strong: Boolean = false) : Effect
        data class ShowSnackbar(val message: String) : Effect
    }
}
