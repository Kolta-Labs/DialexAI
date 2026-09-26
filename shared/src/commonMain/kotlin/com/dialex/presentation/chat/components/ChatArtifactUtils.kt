package com.dialex.presentation.chat.components

import com.dialex.model.Agent
import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import com.dialex.model.DiscussionArtifact
import com.dialex.model.Provider
import com.dialex.model.brandName
import com.dialex.model.label
import com.dialex.export.resolveExportFileName
import com.dialex.export.toMarkdown
import com.dialex.export.toSummaryMarkdown
import com.dialex.util.formatMessageTimestamp

fun calculateMatchScrollOffset(msgContent: String, matchCharOffset: Int): Int {
    if (matchCharOffset <= 0 || msgContent.isBlank()) return 0
    val textBeforeMatch = msgContent.take(matchCharOffset.coerceAtMost(msgContent.length))
    val estimatedLines = textBeforeMatch.split('\n').sumOf { line ->
        (line.length / 70).coerceAtLeast(1)
    }
    val linePixelHeight = 24
    val headerPaddingPx = 48
    val viewportContextPx = 80
    val targetOffset = headerPaddingPx + (estimatedLines * linePixelHeight) - viewportContextPx
    return targetOffset.coerceAtLeast(0)
}

fun Discussion.labelFor(seatId: String, fallbackProvider: Provider): String {
    val agent = config.agents.firstOrNull { it.id == seatId }
    return agent?.label() ?: config.agents.firstOrNull { it.provider == fallbackProvider }?.label() ?: fallbackProvider.brandName()
}

fun Discussion.labelFor(msg: DebateMessage): String {
    if (msg.authorDisplayName.isNotBlank()) return msg.authorDisplayName
    return labelFor(msg.seatId, msg.provider)
}

fun Discussion.labelFor(p: Provider): String =
    config.agents.firstOrNull { it.provider == p }?.label() ?: p.brandName()

fun Discussion.effectiveRoundFor(artifact: DiscussionArtifact): Int? {
    if (artifact.round != null && artifact.round > 0) return artifact.round
    val rMatch = Regex("""_r(\d+)|\(Round (\d+)\)|Round (\d+)""").find(artifact.id + " " + artifact.name + " " + artifact.timestamp)
    if (rMatch != null) {
        val num = rMatch.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
        if (num != null) return num
    }
    if (artifact.type.equals("Reference Document", ignoreCase = true) || artifact.id.startsWith("doc_")) {
        return null
    }
    if (transcript.isEmpty()) return null
    if (artifact.timestampMs > 0L) {
        val matchingTurn = transcript.findLast { it.timestampMs in 1L..artifact.timestampMs + 60_000L }
        if (matchingTurn != null) return matchingTurn.round
        val nextTurn = transcript.firstOrNull { it.timestampMs >= artifact.timestampMs && it.timestampMs > 0L }
        if (nextTurn != null) return nextTurn.round
    }
    return transcript.firstOrNull()?.round ?: 1
}

fun Discussion.resolveAllArtifacts(snapshotRound: Int? = null): List<DiscussionArtifact> {
    val list = mutableListOf<DiscussionArtifact>()

    // 1. Explicitly generated/saved artifacts
    list.addAll(artifacts)

    val roundTag = if (snapshotRound != null && snapshotRound > 0) " (Round $snapshotRound)" else ""
    val roundIdSuffix = if (snapshotRound != null && snapshotRound > 0) "_r$snapshotRound" else ""
    val roundMsg = if (snapshotRound != null) transcript.findLast { it.round == snapshotRound } else transcript.lastOrNull()
    val generatedTimeMs = roundMsg?.timestampMs ?: if (updatedAt > 0L) updatedAt else System.currentTimeMillis()
    val timeLabel = formatMessageTimestamp(generatedTimeMs)
    val effectiveRound = snapshotRound ?: transcript.maxOfOrNull { it.round }

    // 2. Discussion Summary
    val sumId = if (snapshotRound != null && snapshotRound > 0) "art_sum$roundIdSuffix" else "art_sum"
    if (list.none { it.id == sumId || (snapshotRound == null && (it.type.equals("summary", ignoreCase = true) || it.id.startsWith("art_sum"))) }) {
        if (summary != null || conclusion != null) {
            val content = toSummaryMarkdown()
            list.add(
                DiscussionArtifact(
                    id = sumId,
                    name = if (snapshotRound != null && snapshotRound > 0) "Discussion Summary (Round $snapshotRound)" else resolveExportFileName("summary"),
                    type = "Summary",
                    format = "md",
                    content = content,
                    timestamp = timeLabel,
                    timestampMs = generatedTimeMs,
                    sizeBytes = content.encodeToByteArray().size.toLong(),
                    round = effectiveRound
                )
            )
        }
    }

    // 3. Full Transcript
    val traId = if (snapshotRound != null && snapshotRound > 0) "art_tra$roundIdSuffix" else "art_tra"
    if (list.none { it.id == traId || (snapshotRound == null && (it.type.equals("transcript", ignoreCase = true) || it.id.startsWith("art_tra"))) }) {
        if (transcript.isNotEmpty()) {
            val content = toMarkdown()
            list.add(
                DiscussionArtifact(
                    id = traId,
                    name = if (snapshotRound != null && snapshotRound > 0) "Full Transcript (Round $snapshotRound)" else resolveExportFileName("transcript"),
                    type = "Transcript",
                    format = "md",
                    content = content,
                    timestamp = timeLabel,
                    timestampMs = generatedTimeMs,
                    sizeBytes = content.encodeToByteArray().size.toLong(),
                    round = effectiveRound
                )
            )
        }
    }

    // Always include attached reference documents
    attachedFiles.forEach { f ->
        if (list.none { it.id == "doc_${f.id}" || it.name == f.name }) {
            list.add(
                DiscussionArtifact(
                    id = "doc_${f.id}",
                    name = f.name,
                    type = "Reference Document",
                    format = f.name.substringAfterLast('.', "txt"),
                    content = f.content,
                    timestamp = f.sizeLabel.ifBlank { "Attached" },
                    timestampMs = 0L,
                    sizeBytes = f.content.encodeToByteArray().size.toLong()
                )
            )
        }
    }

    return list
}
