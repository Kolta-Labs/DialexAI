package com.dialex.domain.model

import com.dialex.model.PredefinedPersona
import com.dialex.model.Provider
import com.dialex.model.RunMode
import kotlinx.serialization.Serializable

@Serializable
data class PersonaChatMessage(
    val id: String,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val parsedPersona: PredefinedPersona? = null,
    val timestamp: Long = 0L,
)

@Serializable
data class PersonaChatMessageDto(
    val role: String,
    val content: String,
)

@Serializable
data class PersonaChatRequest(
    val provider: Provider = Provider.ANTHROPIC,
    val model: String = "claude-sonnet-5",
    val runMode: RunMode = RunMode.API,
    val cliCommand: String = "",
    val messages: List<PersonaChatMessageDto> = emptyList(),
    val currentDraft: PredefinedPersona? = null,
)

@Serializable
data class PersonaChatResponse(
    val reply: String,
    val parsedPersona: PredefinedPersona? = null,
)
