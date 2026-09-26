package com.dialex.model

import kotlinx.serialization.Serializable

/**
 * A predefined AI persona that can be applied to any agent seat in the setup screen.
 * Personas pre-populate the agent's systemPrompt, role, ponytail style modifiers,
 * and display name. The user can also create custom personas via the Persona Builder.
 */
@Serializable
data class PredefinedPersona(
    val id: String,
    val name: String,
    val description: String = "",
    /** The profession or domain category, e.g. "Software Engineering", "Scientific Research". */
    val category: String = "General Debate",
    /** The agent's functional role label, e.g. "Lead Engineering Moderator" or "System Architect". */
    val role: String = "",
    /** An icon key (e.g. "code", "security", "gavel") or a base64 data URL. */
    val icon: String = "",
    /** The full system prompt injected for this persona. */
    val systemPrompt: String = "",
    /** Whether this persona speaks in Ponytail (structured, formal) style by default. */
    val ponytail: Boolean = false,
    /** True for built-in system personas from the spec catalog. False for user-created ones. */
    val isSystem: Boolean = false,
    /** Default sampling temperature for this persona (null = inherit provider default). */
    val temperature: Double? = null,
    /** Default nucleus sampling top_p for this persona (null = inherit provider default). */
    val topP: Double? = null,
    /** Default max completion tokens for this persona (null = inherit provider default). */
    val maxTokens: Int? = null,
    /** Optional detailed persona and role description. */
    val roleAndPersona: String = "",
    /** Optional core domain expertise or specialty areas. */
    val coreExpertise: String = "",
    /** Optional tone, mannerisms, and vocal style directives. */
    val toneAndVoice: String = "",
    /** Optional primary objective or fiduciary mandate. */
    val objective: String = "",
    /** Optional 8-Layer Cognitive DNA Schema. */
    val dna: com.dialex.domain.model.PersonaDNA? = null,
)

/**
 * Builds a structured system prompt from persona attributes when explicit systemPrompt is absent.
 */
fun buildComposedSystemPrompt(
    roleAndPersona: String = "",
    coreExpertise: String = "",
    toneAndVoice: String = "",
    objective: String = "",
    systemContextPrompt: String = "",
): String {
    val sections = mutableListOf<String>()
    if (roleAndPersona.isNotBlank()) sections.add("### ROLE & PERSONA\n$roleAndPersona")
    if (coreExpertise.isNotBlank()) sections.add("### CORE EXPERTISE\n$coreExpertise")
    if (toneAndVoice.isNotBlank()) sections.add("### TONE & VOICE\n$toneAndVoice")
    if (objective.isNotBlank()) sections.add("### OBJECTIVE\n$objective")
    if (systemContextPrompt.isNotBlank()) sections.add("### SYSTEM CONTEXT\n$systemContextPrompt")
    return sections.joinToString("\n\n")
}

// ══════════════════════════════════════════════════════════════════════════════
// Master catalog of built-in system personas loaded dynamically from bundled JSON
// ══════════════════════════════════════════════════════════════════════════════

/** The complete master catalog of built-in system personas across all professions. */
val SystemPersonas: List<PredefinedPersona>
    get() = com.dialex.data.loader.PersonaLoader.getCachedBundledPersonas()

/** Built-in personas grouped by their profession category. */
val SystemPersonaGroups: Map<String, List<PredefinedPersona>>
    get() = SystemPersonas.groupBy { it.category }

/** Built-in General Debate personas. */
val GeneralDebatePersonas: List<PredefinedPersona>
    get() = SystemPersonaGroups["General Debate"].orEmpty()
