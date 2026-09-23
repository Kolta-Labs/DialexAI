package com.dialex.presentation.cliauth

import androidx.lifecycle.viewModelScope
import com.dialex.presentation.base.MviViewModel
import com.dialex.service.CliToolDescriptor
import com.dialex.service.cliauth.CliAuthJourneyMode
import com.dialex.service.cliauth.CliAuthSessionService
import com.dialex.service.cliauth.CliAuthStatus
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

class CliAuthViewModel(
    val tool: CliToolDescriptor,
    private val sessionService: CliAuthSessionService = CliAuthSessionService()
) : MviViewModel<CliAuthContract.State, CliAuthContract.Intent, CliAuthContract.Effect>(
    CliAuthContract.State(
        tool = tool,
        journeyMode = CliAuthJourneyMode.GUIDED,
        status = CliAuthStatus.Starting,
        terminalLines = persistentListOf("Initializing login for ${tool.name}...")
    )
) {

    init {
        startLoginSession()
        observeServiceStreams()
    }

    private fun startLoginSession() {
        sessionService.setJourneyMode(state.value.journeyMode)
        val rawCmd = tool.loginCommand.trim()
        val normalizedCmd = when {
            tool.provider == com.dialex.model.Provider.ANTHROPIC && (rawCmd == "claude" || !rawCmd.contains("auth")) -> "claude auth login"
            else -> rawCmd
        }
        val sanitizedCmd = normalizedCmd
            .replace(Regex("\\s+(--print|-p|--prompt)($|\\s+)"), " ")
            .trim()
        sessionService.startSession(sanitizedCmd, viewModelScope)
    }

    private fun observeServiceStreams() {
        viewModelScope.launch {
            sessionService.guidedStrategy.status.collect { status ->
                setState {
                    if (journeyMode == CliAuthJourneyMode.GUIDED) {
                        copy(
                            status = status,
                            errorMessage = if (status is CliAuthStatus.Failed) status.error else null
                        )
                    } else {
                        this
                    }
                }
                if (status is CliAuthStatus.Success) {
                    sendEffect(CliAuthContract.Effect.SessionCompleted(isSuccess = true))
                }
            }
        }

        viewModelScope.launch {
            sessionService.guidedStrategy.extractedUrl.collect { url ->
                if (url != null) {
                    setState { copy(oauthUrl = url) }
                    // Automatically trigger browser open on first detection of OAuth URL
                    sendEffect(CliAuthContract.Effect.OpenUrl(url))
                }
            }
        }

        viewModelScope.launch {
            sessionService.terminalStrategy.lines.collect { lines ->
                setState { copy(terminalLines = lines) }
            }
        }

        viewModelScope.launch {
            sessionService.terminalStrategy.status.collect { status ->
                setState {
                    if (journeyMode == CliAuthJourneyMode.TERMINAL) {
                        copy(
                            status = status,
                            errorMessage = if (status is CliAuthStatus.Failed) status.error else null
                        )
                    } else {
                        this
                    }
                }
                if (status is CliAuthStatus.Success) {
                    sendEffect(CliAuthContract.Effect.SessionCompleted(isSuccess = true))
                }
            }
        }
    }

    override fun onIntent(intent: CliAuthContract.Intent) {
        when (intent) {
            is CliAuthContract.Intent.ChangeJourneyMode -> {
                sessionService.setJourneyMode(intent.mode)
                setState {
                    val activeStatus = if (intent.mode == CliAuthJourneyMode.GUIDED) {
                        sessionService.guidedStrategy.status.value
                    } else {
                        sessionService.terminalStrategy.status.value
                    }
                    copy(journeyMode = intent.mode, status = activeStatus)
                }
            }

            is CliAuthContract.Intent.UpdateAuthCodeInput -> {
                setState { copy(authCodeInput = intent.code) }
            }

            is CliAuthContract.Intent.SubmitAuthCode -> {
                val code = state.value.authCodeInput.trim()
                if (code.isNotEmpty()) {
                    setState { copy(isSubmitting = true) }
                    viewModelScope.launch {
                        sessionService.submitInput(code)
                        setState { copy(isSubmitting = false, authCodeInput = "") }
                    }
                }
            }

            is CliAuthContract.Intent.UpdateTerminalInput -> {
                setState { copy(terminalInputText = intent.input) }
            }

            is CliAuthContract.Intent.SubmitTerminalInput -> {
                val input = state.value.terminalInputText
                if (input.isNotEmpty()) {
                    viewModelScope.launch {
                        sessionService.submitInput(input)
                        setState { copy(terminalInputText = "") }
                    }
                }
            }

            is CliAuthContract.Intent.CancelSession -> {
                sessionService.cancelSession()
                setState { copy(status = CliAuthStatus.Cancelled) }
            }

            is CliAuthContract.Intent.RestartSession -> {
                startLoginSession()
            }

            is CliAuthContract.Intent.OpenOAuthInBrowser -> {
                state.value.oauthUrl?.let { url ->
                    sendEffect(CliAuthContract.Effect.OpenUrl(url))
                }
            }
        }
    }

    public override fun onCleared() {
        super.onCleared()
        sessionService.cancelSession()
    }
}
