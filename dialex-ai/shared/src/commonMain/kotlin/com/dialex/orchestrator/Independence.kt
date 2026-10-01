package com.dialex.orchestrator

import com.dialex.model.Agent
import com.dialex.model.DebateMessage
import com.dialex.model.IndependenceConfig
import com.dialex.model.isFrom

/**
 * The transcript view a seat is allowed to see; never mutates the persisted transcript.
 * - blindFirstRound drops other seats' round-1 answers while round 1 is played.
 * - anonymizeTranscript renames other seats "Participant A/B/C" by their fixed seat order.
 * Moderator interventions, system notes, user comments and the seat's own turns are untouched.
 * Mirrors the Go engine's applyIndependence.
 */
internal fun applyIndependence(
    views: List<DebateMessage>,
    forAgent: Agent,
    round: Int,
    cfg: IndependenceConfig,
    seats: List<Agent>,
): List<DebateMessage> {
    if (!cfg.blindFirstRound && !cfg.anonymizeTranscript) return views
    val index = seats.withIndex().associate { (i, a) -> a.id to i }
    return views.mapNotNull { m ->
        val seatIndex = index[m.seatId]
        val peerTurn = seatIndex != null && !m.isSystem && !m.isUserComment &&
            !m.isModeratorIntervention && !m.isFrom(forAgent)
        when {
            peerTurn && cfg.blindFirstRound && round == 1 && m.round == 1 -> null
            peerTurn && cfg.anonymizeTranscript -> m.copy(authorDisplayName = "Participant ${'A' + seatIndex!!}")
            else -> m
        }
    }
}
