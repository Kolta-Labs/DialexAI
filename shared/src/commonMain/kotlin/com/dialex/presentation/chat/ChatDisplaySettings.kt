package com.dialex.presentation.chat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf

data class ChatDisplaySettings(
    val separateAgentBubbleBackgrounds: Boolean = true,
    val showArtifactGenerationStrips: Boolean = true,
    val showCompactionPills: Boolean = true,
    val telegramBotToken: String = "",
    val telegramChatId: String = ""
) {
    companion object {
        val Default = ChatDisplaySettings()
    }
}

val LocalChatDisplaySettings = compositionLocalOf { mutableStateOf(ChatDisplaySettings.Default) }
