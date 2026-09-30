package com.dialex.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SelectedAgentSeat(
    val provider: String,
    val model: String,
    val runMode: String = "API"
)

@Serializable
data class AiSetupRequest(
    val prompt: String,
    val projectId: String,
    val model: String? = null,
    val provider: String? = null,
    val autoStart: Boolean = false,
    val numAgents: Int = 3,
    val selectedAgents: List<SelectedAgentSeat>? = null
)
