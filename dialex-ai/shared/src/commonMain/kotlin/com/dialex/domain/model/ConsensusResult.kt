package com.dialex.domain.model

import kotlinx.serialization.Serializable

/** The engine's verdict on whether the council has reached consensus (see socratix-engine consensus.Result). */
@Serializable
data class ConsensusResult(
    val achieved: Boolean = false,
    val ongoing: Boolean = false,
    val notReady: String = "",
    val agreedSeats: List<String> = emptyList(),
    val agreedCount: Int = 0,
    val totalCount: Int = 0,
    val ratio: Double = 0.0,
)
