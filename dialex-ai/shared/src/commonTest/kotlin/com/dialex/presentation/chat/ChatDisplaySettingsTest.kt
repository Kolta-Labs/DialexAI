package com.dialex.presentation.chat

import com.dialex.service.TelegramFeedbackService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatDisplaySettingsTest {

    @Test
    fun defaultSettings_haveExpectedInitialValues() {
        val settings = ChatDisplaySettings.Default
        assertTrue(settings.separateAgentBubbleBackgrounds)
        assertTrue(settings.showArtifactGenerationStrips)
        assertTrue(settings.showCompactionPills)
        assertEquals("", settings.telegramBotToken)
        assertEquals("", settings.telegramChatId)
    }

    @Test
    fun copySettings_updatesCorrectProperties() {
        val original = ChatDisplaySettings.Default
        val modified = original.copy(
            separateAgentBubbleBackgrounds = false,
            showArtifactGenerationStrips = false,
            showCompactionPills = false,
            telegramBotToken = "123:ABC",
            telegramChatId = "-100999"
        )
        assertEquals(false, modified.separateAgentBubbleBackgrounds)
        assertEquals(false, modified.showArtifactGenerationStrips)
        assertEquals(false, modified.showCompactionPills)
        assertEquals("123:ABC", modified.telegramBotToken)
        assertEquals("-100999", modified.telegramChatId)
    }

    @Test
    fun telegramFeedbackService_rejectsBlankCredentials() = runTest {
        val blankTokenResult = TelegramFeedbackService.sendFeedback(
            botToken = "   ",
            chatId = "-100123",
            description = "Issue test"
        )
        assertTrue(blankTokenResult.isFailure)

        val blankChatIdResult = TelegramFeedbackService.sendFeedback(
            botToken = "123:ABC",
            chatId = "   ",
            description = "Issue test"
        )
        assertTrue(blankChatIdResult.isFailure)
    }
}
