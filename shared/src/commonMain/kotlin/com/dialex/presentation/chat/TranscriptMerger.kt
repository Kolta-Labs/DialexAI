package com.dialex.presentation.chat

import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage

/**
 * Pure functions for transcript merging, deduplication, and round alignment.
 */
object TranscriptMerger {

    fun sanitizeTranscript(
        transcript: List<DebateMessage>,
        config: DebateConfig
    ): List<DebateMessage> {
        if (transcript.isEmpty()) return transcript
        val agentCount = config.agents.size.coerceAtLeast(1)
        val sanitized = mutableListOf<DebateMessage>()
        var currentAgentTurnsInRound = 0
        var currentRound = 1

        for (msg in transcript) {
            if (msg.isUserComment) {
                // If this user comment was placed in a round where all agents had already completed their turns,
                // it belongs to the next round as the opening user directive/comment.
                if (currentAgentTurnsInRound >= agentCount && msg.round <= currentRound) {
                    val nextRound = currentRound + 1
                    sanitized.add(msg.copy(round = nextRound))
                    currentRound = nextRound
                    currentAgentTurnsInRound = 0
                } else {
                    val effectiveRound = maxOf(msg.round, currentRound)
                    sanitized.add(msg.copy(round = effectiveRound))
                    currentRound = effectiveRound
                }
            } else if (!msg.isError && !msg.isSystem) {
                if (msg.round > currentRound) {
                    currentRound = msg.round
                    currentAgentTurnsInRound = 1
                } else {
                    currentAgentTurnsInRound++
                }
                sanitized.add(msg)
            } else {
                sanitized.add(msg)
            }
        }
        return sanitized
    }

    fun mergeTranscripts(
        local: List<DebateMessage>,
        remote: List<DebateMessage>,
        config: DebateConfig
    ): List<DebateMessage> {
        val rawMerged = if (local.isEmpty()) {
            remote
        } else if (remote.isEmpty()) {
            local.dropLastWhile { it.isError }
        } else {
            val localUserComments = local.filter { it.isUserComment }
            if (localUserComments.isEmpty()) {
                remote
            } else {
                // Fix remote entries that match local user comments in case remote serialized/deserialized without isUserComment
                val sanitizedRemote = remote.map { rm ->
                    if (!rm.isUserComment && localUserComments.any { luc ->
                        luc.content.trim() == rm.content.trim() && (luc.round == rm.round || (luc.timestampMs > 0L && rm.timestampMs > 0L && kotlin.math.abs(luc.timestampMs - rm.timestampMs) < 60_000L))
                    }) {
                        rm.copy(
                            isUserComment = true,
                            seatId = "observer",
                            authorDisplayName = "You (Observer)"
                        )
                    } else {
                        rm
                    }
                }

                // Identify local comments that are truly not yet present in remote
                val missingComments = localUserComments.filter { luc ->
                    sanitizedRemote.none { rc ->
                        rc.content.trim() == luc.content.trim() && (rc.isUserComment || rc.round == luc.round)
                    }
                }

                val combined = if (missingComments.isEmpty()) sanitizedRemote else sanitizedRemote + missingComments

                // Strict chronological ordering:
                // 1. By round ascending
                // 2. By timestampMs ascending (if available)
                // 3. User comments (injected prompts) always precede agent responses within the same round if timestamps are identical
                combined.sortedWith(
                    compareBy<DebateMessage> { it.round }
                        .thenBy { if (it.timestampMs > 0L) it.timestampMs else Long.MAX_VALUE }
                        .thenBy { if (it.isUserComment) 0 else 1 }
                )
            }
        }
        return sanitizeTranscript(rawMerged, config)
    }
}
