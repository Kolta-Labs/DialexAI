package com.dialex.model

/**
 * Represents the outcome of a persona selection interaction.
 * - [None]: no persona, use baseline model.
 * - [Stock]: a built-in catalog persona selected with no edits.
 *   Only personaId, role, caveman, ponytail, and displayName are written to the Agent.
 *   systemPrompt is NOT written — it is resolved from the catalog at runtime.
 * - [Custom]: user has edited the persona (name or instructions).
 *   All fields including systemPrompt are written. personaId gets a "_custom" suffix.
 *   displayName gets " (Custom)" appended.
 */
sealed interface PersonaSelectionResult {
    data object None : PersonaSelectionResult
    data class Stock(val persona: PredefinedPersona) : PersonaSelectionResult
    data class Custom(
        val basePersonaId: String,
        val displayName: String,
        val role: String,
        val systemPrompt: String,
        val caveman: Boolean,
        val ponytail: Boolean,
        val roleAndPersona: String = "",
        val coreExpertise: String = "",
        val toneAndVoice: String = "",
        val objective: String = "",
    ) : PersonaSelectionResult
}
