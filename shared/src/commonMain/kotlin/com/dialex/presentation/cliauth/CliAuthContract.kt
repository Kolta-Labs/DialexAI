package com.dialex.presentation.cliauth

import com.dialex.service.CliToolDescriptor
import com.dialex.service.cliauth.CliAuthJourneyMode
import com.dialex.service.cliauth.CliAuthStatus
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * MVI Contract for In-App CLI Authentication.
 */
object CliAuthContract {

    data class State(
        val tool: CliToolDescriptor,
        val journeyMode: CliAuthJourneyMode = CliAuthJourneyMode.GUIDED,
        val status: CliAuthStatus = CliAuthStatus.Idle,
        val oauthUrl: String? = null,
        val authCodeInput: String = "",
        val terminalLines: ImmutableList<String> = persistentListOf(),
        val terminalInputText: String = "",
        val errorMessage: String? = null,
        val isSubmitting: Boolean = false
    )

    sealed interface Intent {
        data class ChangeJourneyMode(val mode: CliAuthJourneyMode) : Intent
        data class UpdateAuthCodeInput(val code: String) : Intent
        data object SubmitAuthCode : Intent
        data class UpdateTerminalInput(val input: String) : Intent
        data object SubmitTerminalInput : Intent
        data object CancelSession : Intent
        data object RestartSession : Intent
        data object OpenOAuthInBrowser : Intent
    }

    sealed interface Effect {
        data class OpenUrl(val url: String) : Effect
        data class SessionCompleted(val isSuccess: Boolean) : Effect
        data class ShowSnackbar(val message: String) : Effect
    }
}
