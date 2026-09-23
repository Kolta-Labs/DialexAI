package com.dialex.audio

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * State representing the active status of speech recognition.
 */
sealed interface SpeechRecognitionState {
    /** Recognizer is inactive. */
    data object Idle : SpeechRecognitionState

    /** Preparing microphone and acoustic model. */
    data object Initializing : SpeechRecognitionState

    /**
     * Actively listening to microphone input.
     * @property partialText Current live partial transcript stream.
     * @property rmsDb Normalized audio volume level (0.0f - 1.0f) for visualizer pulse.
     */
    data class Listening(
        val partialText: String = "",
        val rmsDb: Float = 0f
    ) : SpeechRecognitionState

    /**
     * Final transcribed result segment.
     * @property text The final transcribed text.
     */
    data class Result(val text: String) : SpeechRecognitionState

    /**
     * An error occurred during recognition.
     * @property message Human-readable error message.
     */
    data class Error(val message: String) : SpeechRecognitionState
}

/**
 * Platform Expect for embedded speech-to-text recognition.
 */
expect class PlatformSpeechRecognizer {
    val state: StateFlow<SpeechRecognitionState>

    fun startListening(language: String = "en-US")
    fun stopListening()
    fun cancel()
    fun isSupported(): Boolean
    fun release()
}

/**
 * Compose helper to instantiate and lifecycle-manage [PlatformSpeechRecognizer].
 */
@Composable
expect fun rememberPlatformSpeechRecognizer(): PlatformSpeechRecognizer

/**
 * Helper to merge newly transcribed speech into existing text input intelligently.
 */
object SpeechTextMerger {
    fun merge(existingText: String, speechText: String): String {
        val trimmedSpeech = speechText.trim()
        if (trimmedSpeech.isEmpty()) return existingText
        if (existingText.isBlank()) return trimmedSpeech

        val trimmedExisting = existingText.trimEnd()
        val endsWithPunctuation = trimmedExisting.endsWith(".") ||
                trimmedExisting.endsWith("?") ||
                trimmedExisting.endsWith("!") ||
                trimmedExisting.endsWith(":") ||
                trimmedExisting.endsWith(";")

        val formattedSpeech = if (endsWithPunctuation) {
            trimmedSpeech.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        } else {
            trimmedSpeech
        }

        return "$trimmedExisting $formattedSpeech"
    }
}
