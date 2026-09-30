package com.dialex.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ProblemAxis(
    val id: String,
    val title: String,
    val thesis: String,
    val antithesis: String,
    val keyQuestions: List<String> = emptyList(),
    val weight: Double = 0.8,
    val selected: Boolean = true
)

@Serializable
data class DecompositionPerspective(
    val id: String,
    val name: String,
    val lensDescription: String,
    val axes: List<ProblemAxis> = emptyList()
)

@Serializable
data class ProblemDecomposition(
    val topic: String,
    val perspectiveA: DecompositionPerspective,
    val perspectiveB: DecompositionPerspective
)

@Serializable
data class DecompositionRequest(
    val topic: String,
    val context: String = "",
    val model: String? = null,
    val provider: String? = null
)
