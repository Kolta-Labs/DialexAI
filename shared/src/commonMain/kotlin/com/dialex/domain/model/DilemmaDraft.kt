package com.dialex.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class DilemmaDraft(
    val rawTranscript: String,
    val topic: String,
    val constraints: String,
    val suggestedPresetId: String? = null
)
