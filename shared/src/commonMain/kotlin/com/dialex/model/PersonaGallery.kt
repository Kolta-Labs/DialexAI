package com.dialex.model

import kotlinx.serialization.Serializable

@Serializable
data class PersonaGalleryResponse(
    val version: Int = 1,
    val title: String = "Dialex Persona Gallery",
    val description: String = "",
    val updatedAt: String = "",
    val personas: List<PredefinedPersona> = emptyList(),
)
