package com.dialex.service

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TelegramSendMessagePayload(
    val chat_id: String,
    val text: String
)

object TelegramFeedbackService {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
    }

    suspend fun sendFeedback(
        botToken: String,
        chatId: String,
        description: String,
        sessionTranscript: String? = null
    ): Result<Unit> {
        val cleanToken = botToken.trim()
        val cleanChatId = chatId.trim()
        if (cleanToken.isBlank()) {
            return Result.failure(IllegalArgumentException("Telegram Bot Token cannot be blank"))
        }
        if (cleanChatId.isBlank()) {
            return Result.failure(IllegalArgumentException("Telegram Chat ID cannot be blank"))
        }

        val textBuilder = StringBuilder()
        textBuilder.append("🐞 [Dialex Bug Report & Feedback]\n\n")
        textBuilder.append(description.trim())
        if (!sessionTranscript.isNullOrBlank()) {
            textBuilder.append("\n\n────────────────\nSession Transcript Preview:\n")
            val preview = if (sessionTranscript.length > 2500) sessionTranscript.take(2500) + "\n...[truncated]" else sessionTranscript
            textBuilder.append(preview)
        }

        val url = "https://api.telegram.org/bot$cleanToken/sendMessage"

        return runCatching {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(TelegramSendMessagePayload(chat_id = cleanChatId, text = textBuilder.toString()))
            }
            if (response.status.isSuccess()) {
                Unit
            } else {
                val body = response.bodyAsText()
                throw RuntimeException("Telegram API Error (${response.status.value}): $body")
            }
        }
    }
}
