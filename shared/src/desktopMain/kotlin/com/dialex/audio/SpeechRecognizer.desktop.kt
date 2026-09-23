package com.dialex.audio

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import javax.sound.sampled.*
import kotlin.math.sqrt

actual class PlatformSpeechRecognizer {
    private val _state = MutableStateFlow<SpeechRecognitionState>(SpeechRecognitionState.Idle)
    actual val state: StateFlow<SpeechRecognitionState> = _state.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var recordingJob: Job? = null
    private var targetLine: TargetDataLine? = null

    private val audioFormat = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED,
        16000.0f,
        16,
        1,
        2,
        16000.0f,
        false
    )

    actual fun startListening(language: String) {
        if (_state.value is SpeechRecognitionState.Listening) return

        recordingJob?.cancel()
        recordingJob = scope.launch {
            try {
                _state.value = SpeechRecognitionState.Initializing

                val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
                if (!AudioSystem.isLineSupported(info)) {
                    _state.value = SpeechRecognitionState.Error("Microphone not supported on this system")
                    return@launch
                }

                val line = AudioSystem.getLine(info) as TargetDataLine
                targetLine = line
                line.open(audioFormat)
                line.start()

                _state.value = SpeechRecognitionState.Listening(partialText = "", rmsDb = 0f)

                val buffer = ByteArray(4096)
                val audioAccumulator = ByteArrayOutputStream()

                while (isActive && line.isOpen) {
                    val bytesRead = line.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        audioAccumulator.write(buffer, 0, bytesRead)

                        // Calculate RMS amplitude for visualizer
                        var sum = 0.0
                        var sampleCount = 0
                        for (i in 0 until bytesRead - 1 step 2) {
                            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
                            sum += sample.toDouble() * sample.toDouble()
                            sampleCount++
                        }
                        val rms = if (sampleCount > 0) sqrt(sum / sampleCount) else 0.0
                        // Normalize 16-bit PCM RMS (0 to 32767) into 0.0f .. 1.0f
                        val normalizedRms = (rms / 8000.0).toFloat().coerceIn(0.05f, 1.0f)

                        val current = _state.value
                        if (current is SpeechRecognitionState.Listening) {
                            _state.value = current.copy(rmsDb = normalizedRms)
                        }
                    }
                }
            } catch (e: Throwable) {
                if (e !is CancellationException) {
                    _state.value = SpeechRecognitionState.Error(e.message ?: "Failed to record audio")
                }
            } finally {
                closeLine()
            }
        }
    }

    actual fun stopListening() {
        scope.launch {
            val currentState = _state.value
            closeLine()
            recordingJob?.cancel()
            recordingJob = null

            if (currentState is SpeechRecognitionState.Listening) {
                if (currentState.partialText.isNotBlank()) {
                    _state.value = SpeechRecognitionState.Result(currentState.partialText)
                } else {
                    _state.value = SpeechRecognitionState.Idle
                }
            } else {
                _state.value = SpeechRecognitionState.Idle
            }
        }
    }

    actual fun cancel() {
        closeLine()
        recordingJob?.cancel()
        recordingJob = null
        _state.value = SpeechRecognitionState.Idle
    }

    actual fun isSupported(): Boolean {
        return try {
            val info = DataLine.Info(TargetDataLine::class.java, audioFormat)
            AudioSystem.isLineSupported(info)
        } catch (_: Throwable) {
            false
        }
    }

    actual fun release() {
        cancel()
        scope.cancel()
    }

    private fun closeLine() {
        try {
            targetLine?.stop()
            targetLine?.close()
            targetLine = null
        } catch (_: Throwable) {}
    }
}

@Composable
actual fun rememberPlatformSpeechRecognizer(): PlatformSpeechRecognizer {
    val recognizer = remember { PlatformSpeechRecognizer() }

    DisposableEffect(recognizer) {
        onDispose {
            recognizer.release()
        }
    }

    return recognizer
}
