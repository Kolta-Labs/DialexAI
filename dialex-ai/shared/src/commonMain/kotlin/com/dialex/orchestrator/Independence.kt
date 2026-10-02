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
            peerTurn && cfg.anonymizeTranscript ->
                m.copy(authorDisplayName = seatLabel(seatIndex!!), content = scrubIdentity(m.content, forAgent, seats))
            seatIndex != null && cfg.anonymizeTranscript && !m.isSystem && !m.isUserComment ->
                m.copy(content = scrubIdentity(m.content, forAgent, seats))
            else -> m
        }
    }
}

private fun seatLabel(i: Int) = "Participant ${'A' + i}"

/** Replaces other seats' names and roles inside a message with their anonymous labels, so
 * "As the Skeptic, I..." cannot undo the anonymization. The viewer's own name is left alone.
 * Removes the explicit leak only; writing style still carries identity. */
private fun scrubIdentity(text: String, viewer: Agent, seats: List<Agent>): String {
    val reps = seats.withIndex()
        .filter { (_, a) -> a.id != viewer.id }
        .flatMap { (i, a) -> listOf(a.displayName, a.role).map { it.trim() }.filter { it.length >= 3 }.map { it to seatLabel(i) } }
        .sortedByDescending { it.first.length } // "Neutral Chair" before "Chair"
    return reps.fold(text) { acc, (name, label) ->
        acc.replace(Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE), label)
    }
}
