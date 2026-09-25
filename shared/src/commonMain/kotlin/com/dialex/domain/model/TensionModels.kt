package com.dialex.domain.model

import com.dialex.model.Provider
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

/** Dialectic lifecycle stage of an isolated contradiction pair. */
@Serializable
enum class TensionStatus {
    OPEN,
    EXPLORED,
    RESOLVED,
    ACCEPTED_TRADE_OFF
}

/** Specific assertion made by an agent participant in a debate round. */
@Serializable
data class AgentAssertion(
    val seatId: String = "",
    val provider: Provider,
    val authorDisplayName: String,
    val statement: String,
    val quote: String? = null,
    val round: Int = 1
)

/** An isolated dialectic tension pair (thesis vs antithesis) grounded in paraconsistent logic (C_n systems). */
@Serializable
data class TensionPair(
    val id: String,
    val thesis: AgentAssertion,
    val antithesis: AgentAssertion,
    val underlyingConflict: String,
    val synthesis: String? = null,
    val tradeOffRationale: String? = null,
    val status: TensionStatus = TensionStatus.OPEN,
    val severity: Float = 0.5f,
    val detectedInRound: Int = 1,
    val resolvedInRound: Int? = null
)

/** Filter modes for inspecting tension pairs in the tension drawer. */
@Serializable
enum class TensionFilter {
    ALL,
    OPEN_ONLY,
    RESOLVED_ONLY,
    TRADE_OFFS
}

/** Extension functions for tension lists */
fun List<TensionPair>.hasOpenTensions(): Boolean =
    any { it.status == TensionStatus.OPEN || it.status == TensionStatus.EXPLORED }

fun List<TensionPair>.openCount(): Int =
    count { it.status == TensionStatus.OPEN || it.status == TensionStatus.EXPLORED }

fun List<TensionPair>.resolvedCount(): Int =
    count { it.status == TensionStatus.RESOLVED || it.status == TensionStatus.ACCEPTED_TRADE_OFF }
