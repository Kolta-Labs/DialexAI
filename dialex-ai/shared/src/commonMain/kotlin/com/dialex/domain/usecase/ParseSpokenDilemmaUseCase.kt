package com.dialex.domain.usecase

import com.dialex.domain.model.CouncilPreset
import com.dialex.domain.model.DilemmaDraft

/**
 * UseCase to parse unstructured spoken/dictated dilemma input into structured
 * Topic, Hard Constraints, and recommended Council Preset.
 */
class ParseSpokenDilemmaUseCase {
    operator fun invoke(spokenText: String): DilemmaDraft {
        val trimmed = spokenText.trim()
        if (trimmed.isBlank()) {
            return DilemmaDraft(
                rawTranscript = "",
                topic = "",
                constraints = ""
            )
        }

        // Detect topic vs constraints using common spoken connectors ("budget is", "constraint", "deadline is")
        val lower = trimmed.lowercase()
        val suggestedPreset = when {
            lower.contains("tech") || lower.contains("architecture") || lower.contains("backend") ||
            lower.contains("database") || lower.contains("service") || lower.contains("code") ->
                CouncilPreset.TechArchitecture

            lower.contains("term sheet") || lower.contains("deal") || lower.contains("negotiate") ||
            lower.contains("pricing") || lower.contains("contract") ->
                CouncilPreset.DealNegotiation

            lower.contains("skeptic") || lower.contains("devil's advocate") || lower.contains("fail") ||
            lower.contains("risk") || lower.contains("critique") ->
                CouncilPreset.DevilsAdvocate

            else -> CouncilPreset.ExecutiveRedTeam
        }

        val constraintKeywords = listOf("budget is", "constraint:", "constraints:", "must have", "deadline is", "deadline:", "limit:", "timeline:")
        var topicPart = trimmed
        var constraintPart = ""

        for (kw in constraintKeywords) {
            val idx = lower.indexOf(kw)
            if (idx != -1) {
                topicPart = trimmed.substring(0, idx).trim().removeSuffix(";").removeSuffix(",")
                constraintPart = trimmed.substring(idx).trim()
                break
            }
        }

        return DilemmaDraft(
            rawTranscript = trimmed,
            topic = topicPart.ifBlank { trimmed },
            constraints = constraintPart,
            suggestedPresetId = suggestedPreset.id
        )
    }
}
