package com.dialex.audio

import com.dialex.model.Provider

actual class PlatformTextToSpeechPlayer {
    private var playing: Boolean = false

    actual fun speak(text: String, provider: Provider, speed: Float, onComplete: () -> Unit) {
        playing = true
        // Desktop mock/system speech synthesizer
        onComplete()
        playing = false
    }

    actual fun stop() {
        playing = false
    }

    actual fun pause() {
        playing = false
    }

    actual fun resume() {
        playing = true
    }

    actual fun isPlaying(): Boolean = playing
}
