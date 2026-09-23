package com.dialex.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class PlatformSpeechRecognizer(private val context: Context) {
    private val _state = MutableStateFlow<SpeechRecognitionState>(SpeechRecognitionState.Idle)
    actual val state: StateFlow<SpeechRecognitionState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var isListeningActive = false

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = SpeechRecognitionState.Listening(partialText = "", rmsDb = 0f)
        }

        override fun onBeginningOfSpeech() {
            _state.value = SpeechRecognitionState.Listening(partialText = "", rmsDb = 0.2f)
        }

        override fun onRmsChanged(rmsdB: Float) {
            val currentState = _state.value
            if (currentState is SpeechRecognitionState.Listening) {
                // rmsdB typically ranges between -2.0 and 10.0 dB
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _state.value = currentState.copy(rmsDb = normalized)
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Processing final result
        }

        override fun onError(error: Int) {
            isListeningActive = false
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Speech recognition client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout during speech recognition"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
                SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                else -> "Speech recognition error ($error)"
            }
            // For harmless no-match / speech-timeout, return to Idle cleanly without alert banner
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                _state.value = SpeechRecognitionState.Idle
            } else {
                _state.value = SpeechRecognitionState.Error(message)
            }
        }

        override fun onResults(results: Bundle?) {
            isListeningActive = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val resultText = matches?.firstOrNull().orEmpty()
            if (resultText.isNotBlank()) {
                _state.value = SpeechRecognitionState.Result(resultText)
            } else {
                _state.value = SpeechRecognitionState.Idle
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val partial = matches?.firstOrNull().orEmpty()
            val current = _state.value
            val currentRms = if (current is SpeechRecognitionState.Listening) current.rmsDb else 0.3f
            _state.value = SpeechRecognitionState.Listening(partialText = partial, rmsDb = currentRms)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun initRecognizerIfNeeded() {
        if (recognizer == null && SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(recognitionListener)
            }
        }
    }

    actual fun startListening(language: String) {
        mainHandler.post {
            try {
                initRecognizerIfNeeded()
                val currentRecognizer = recognizer
                if (currentRecognizer == null) {
                    _state.value = SpeechRecognitionState.Error("Speech recognition is not available on this device")
                    return@post
                }

                _state.value = SpeechRecognitionState.Initializing
                isListeningActive = true

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    // Request offline/embedded on-device recognition first
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                currentRecognizer.startListening(intent)
            } catch (e: Throwable) {
                isListeningActive = false
                _state.value = SpeechRecognitionState.Error(e.message ?: "Failed to start listening")
            }
        }
    }

    actual fun stopListening() {
        mainHandler.post {
            try {
                if (isListeningActive) {
                    recognizer?.stopListening()
                    isListeningActive = false
                }
            } catch (_: Throwable) {}
        }
    }

    actual fun cancel() {
        mainHandler.post {
            try {
                isListeningActive = false
                recognizer?.cancel()
                _state.value = SpeechRecognitionState.Idle
            } catch (_: Throwable) {}
        }
    }

    actual fun isSupported(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    actual fun release() {
        mainHandler.post {
            try {
                isListeningActive = false
                recognizer?.destroy()
                recognizer = null
                _state.value = SpeechRecognitionState.Idle
            } catch (_: Throwable) {}
        }
    }
}

@Composable
actual fun rememberPlatformSpeechRecognizer(): PlatformSpeechRecognizer {
    val context = LocalContext.current.applicationContext
    val recognizer = remember(context) { PlatformSpeechRecognizer(context) }

    DisposableEffect(recognizer) {
        onDispose {
            recognizer.release()
        }
    }

    return recognizer
}
