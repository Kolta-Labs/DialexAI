package com.dialex.audio

import com.dialex.model.Provider

enum class PlaybackState {
    IDLE,
    PLAYING,
    PAUSED
}

/**
 * Platform Expect for Neural/System Multi-Voice Text-To-Speech Player.
 */
expect class PlatformTextToSpeechPlayer {
    fun speak(text: String, provider: Provider, speed: Float = 1.0f, onComplete: () -> Unit = {})
    fun stop()
    fun pause()
    fun resume()
    fun isPlaying(): Boolean
}
