package com.artix.desktop.model

import kotlinx.serialization.Serializable

@Serializable
data class Stakeholder(
    val id: String,
    val name: String,
    val role: String,
    val avatarInitials: String,
    val influenceScore: Float // 0.0 to 1.0
)

@Serializable
data class CouncilMessage(
    val authorId: String,
    val authorRole: String,
    val round: Int,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class StorySpecUI(
    val id: String,
    val title: String,
    val userStory: String,
    val inScope: List<String>,
    val outOfScope: List<String>,
    val testCommands: List<String>,
    val rawMarkdown: String
)

@Serializable
data class DiffChunk(
    val id: String,
    val file: String,
    val header: String,
    val diffText: String,
    val isAccepted: Boolean = true
)

@Serializable
data class ReviewFeedbackUI(
    val round: Int,
    val approved: Boolean,
    val summary: String,
    val blockingIssues: List<String>,
    val testOutput: String
)

@Serializable
data class SteeringRuleUI(
    val id: String,
    val name: String,
    val path: String,
    val type: String, // "local", "standard", "remote_git", "remote_http"
    val isEnabled: Boolean = true,
    val boundPersonas: List<String> = emptyList()
)

@Serializable
data class PersonaDnaUI(
    val id: String,
    val name: String,
    val role: String,
    val cognitivePriors: Map<String, Float>, // Radar metrics e.g. "Strictness", "Testing Focus", "Brevity", "Paranoia", "Velocity"
    val tabooRules: List<String>,
    val heuristics: List<String>
)
