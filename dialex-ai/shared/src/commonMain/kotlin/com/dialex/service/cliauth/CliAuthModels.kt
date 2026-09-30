package com.dialex.service.cliauth

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/**
 * Supported authentication journeys for in-app CLI login.
 */
enum class CliAuthJourneyMode {
    GUIDED,
    TERMINAL
}

/**
 * Represents the current lifecycle status of an in-app CLI authentication session.
 */
sealed interface CliAuthStatus {
    data object Idle : CliAuthStatus
    data object Starting : CliAuthStatus
    data class AwaitingBrowserAuth(val authUrl: String) : CliAuthStatus
    data class AwaitingCodeInput(val promptMessage: String, val authUrl: String?) : CliAuthStatus
    data class Running(val lastOutput: String?) : CliAuthStatus
    data class Success(val message: String) : CliAuthStatus
    data class Failed(val error: String, val exitCode: Int?) : CliAuthStatus
    data object Cancelled : CliAuthStatus
}

/**
 * Step information for the guided OAuth flow.
 */
data class GuidedAuthStep(
    val stepNumber: Int,
    val title: String,
    val description: String,
    val isCompleted: Boolean = false,
    val isCurrent: Boolean = false,
    val actionUrl: String? = null,
    val requiresInput: Boolean = false
)
