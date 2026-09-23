package com.dialex.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechRecognizerTest {

    @Test
    fun speechTextMerger_empty_existing_returns_speech() {
        val merged = SpeechTextMerger.merge("", "Should we migrate to Postgres?")
        assertEquals("Should we migrate to Postgres?", merged)
    }

    @Test
    fun speechTextMerger_appends_with_space() {
        val existing = "We need a faster backend"
        val speech = "with low memory footprint"
        val merged = SpeechTextMerger.merge(existing, speech)
        assertEquals("We need a faster backend with low memory footprint", merged)
    }

    @Test
    fun speechTextMerger_capitalizes_after_punctuation() {
        val existing = "Our budget is 20k."
        val speech = "we also have a 3-week deadline"
        val merged = SpeechTextMerger.merge(existing, speech)
        assertEquals("Our budget is 20k. We also have a 3-week deadline", merged)
    }

    @Test
    fun speechTextMerger_ignores_empty_speech() {
        val existing = "Existing text"
        val merged = SpeechTextMerger.merge(existing, "   ")
        assertEquals("Existing text", merged)
    }

    @Test
    fun speechRecognitionState_listening_properties() {
        val state = SpeechRecognitionState.Listening(partialText = "Hello", rmsDb = 0.75f)
        assertEquals("Hello", state.partialText)
        assertEquals(0.75f, state.rmsDb)
        assertTrue(state is SpeechRecognitionState)
    }

    @Test
    fun speechRecognitionState_result_properties() {
        val state = SpeechRecognitionState.Result(text = "Final recognized transcript")
        assertEquals("Final recognized transcript", state.text)
    }

    @Test
    fun speechRecognitionState_error_properties() {
        val state = SpeechRecognitionState.Error(message = "Audio recording failed")
        assertEquals("Audio recording failed", state.message)
    }
}
