package com.dialex.export

import com.dialex.model.Discussion
import com.dialex.model.Provider
import com.dialex.model.RoundMode
import com.dialex.model.brandName
import com.dialex.model.label

/** Resolves a transcript message's display name using seat ID, author snapshot, or provider fallback. */
fun Discussion.labelFor(seatId: String, fallbackProvider: Provider): String {
    val agent = config.agents.firstOrNull { it.id == seatId }
    return agent?.label() ?: config.agents.firstOrNull { it.provider == fallbackProvider }?.label() ?: fallbackProvider.brandName()
}

fun Discussion.labelFor(msg: com.dialex.model.DebateMessage): String {
    if (msg.authorDisplayName.isNotBlank()) return msg.authorDisplayName
    return labelFor(msg.seatId, msg.provider)
}

/** Resolves display name from provider only — kept for fallback and deliverable generator lookup. */
fun Discussion.labelFor(p: Provider): String =
    config.agents.firstOrNull { it.provider == p }?.label() ?: p.brandName()

/** A safe, short file name for this discussion's export. */
fun Discussion.exportFileName(): String {
    val slug = name.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .ifBlank { "discussion" }
        .take(60)
    return "$slug.md"
}

/**
 * Renders the whole discussion as a standalone Markdown document: title, setup summary,
 * the full transcript in order (errors called out, blank turns skipped), and the primary
 * agent's conclusion at the end if there is one.
 */
fun Discussion.toMarkdown(): String = buildString {
    appendLine("# $name")
    appendLine()
    appendLine("**Status:** ${status.name.lowercase()}")
    val participants = config.agents.joinToString(", ") { it.label() }
    appendLine("**Participants:** $participants")
    appendLine(
        "**Mode:** " + if (config.roundMode == RoundMode.FIXED) "Fixed, ${config.maxRounds} round(s)" else "Unlimited",
    )
    appendLine()
    appendLine("## Topic")
    appendLine()
    appendLine(config.topic)
    if (config.commonContext.isNotBlank()) {
        appendLine()
        appendLine("## Context")
        appendLine()
        appendLine(config.commonContext)
    }
    if (config.commonInfo.isNotBlank()) {
        appendLine()
        appendLine("## Information")
        appendLine()
        appendLine(config.commonInfo)
    }
    appendLine()
    appendLine("## Transcript")
    transcript.forEach { msg ->
        appendLine()
        val author = labelFor(msg)
        if (msg.isError) {
            appendLine("> ⚠️ **Warning (round ${msg.round}):** Turn dropped for $author: ${msg.content}")
        } else {
            appendLine("### $author — round ${msg.round}")
            appendLine()
            appendLine(msg.content)
        }
    }
    if (conclusion != null) {
        appendLine()
        appendLine("## ${config.primary.label()}'s Conclusion")
        appendLine()
        appendLine(conclusion)
    }
    if (deliverable != null) {
        appendLine()
        appendLine("## Deliverable (${config.deliverable.format.name.lowercase().replace('_', ' ')})")
        appendLine()
        appendLine(deliverable)
    }
    if (summary != null && summary != conclusion) {
        appendLine()
        appendLine("## Discussion Summary")
        appendLine()
        appendLine(summary)
    }
    if (credenceLedger != null && credenceLedger.hypotheses.isNotEmpty() && credenceLedger.snapshots.isNotEmpty()) {
        val initialSnap = credenceLedger.snapshots.first()
        val latestSnap = credenceLedger.latestSnapshot ?: credenceLedger.snapshots.last()
        val entropy = latestSnap.entropy
        val entropyStr = ((entropy * 100).toInt() / 100.0).toString()

        appendLine()
        appendLine("## Bayesian Epistemic Credence Matrix")
        appendLine()
        appendLine("Final Epistemic Shannon Entropy: **$entropyStr bits**")
        appendLine()
        appendLine("| Hypothesis Option | Prior (P₀) | Posterior (P_N) | Net Shift (ΔP) |")
        appendLine("| :--- | :---: | :---: | :---: |")
        credenceLedger.hypotheses.forEach { h ->
            val p0 = (initialSnap.probabilityFor(h.id) * 100).toInt()
            val pn = (latestSnap.probabilityFor(h.id) * 100).toInt()
            val delta = pn - p0
            val deltaSign = if (delta > 0) "+" else ""
            val dominantMark = if (h.id == latestSnap.dominantHypothesis) " 👑 (Dominant)" else ""
            val descPart = if (h.description.isNotBlank()) ": ${h.description}" else ""
            appendLine("| **${h.label}**$descPart$dominantMark | $p0% | $pn% | $deltaSign$delta% |")
        }
        val allTippingPoints = credenceLedger.snapshots.flatMap { it.tippingPoints }
        if (allTippingPoints.isNotEmpty()) {
            appendLine()
            appendLine("### Epistemic Tipping Points")
            appendLine()
            allTippingPoints.forEach { tp ->
                appendLine("- **Round ${tp.roundIndex}** (Likelihood Ratio Λ = ${((tp.likelihoodRatio * 10).toInt() / 10.0)}): ${tp.evidenceSnippet}")
            }
        }
    }
}

/**
 * Renders only the structured deliverable artifact with topic and metadata header.
 */
fun Discussion.toDeliverableMarkdown(): String = buildString {
    appendLine("# Deliverable: $name")
    appendLine()
    appendLine("**Topic:** ${config.topic}")
    appendLine("**Format:** ${config.deliverable.format.name.lowercase().replace('_', ' ')}")
    val generator = config.deliverable.generatorProvider?.let { labelFor(it) } ?: config.primary.label()
    appendLine("**Generated By:** $generator")
    appendLine()
    appendLine("---")
    appendLine()
    appendLine(deliverable ?: conclusion ?: "No deliverable generated yet.")
}

/**
 * Renders only the colloquial summary / recap.
 */
fun Discussion.toSummaryMarkdown(): String = buildString {
    appendLine("# Summary: $name")
    appendLine()
    appendLine("**Topic:** ${config.topic}")
    appendLine()
    appendLine("---")
    appendLine()
    appendLine(summary ?: conclusion ?: "No summary available yet.")
}

/**
 * Resolves a filename based on a pattern like "{date}-{topic}" or falls back to slug.
 */
fun Discussion.resolveExportFileName(suffix: String = "", pattern: String = config.output.fileNamePattern): String {
    val topicSlug = name.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .ifBlank { "discussion" }
        .take(40)

    val dateStr = com.dialex.util.nowDateString()

    val base = if (pattern.isNotBlank()) {
        pattern
            .replace("{date}", dateStr)
            .replace("{topic}", topicSlug)
            .replace("{status}", status.name.lowercase())
    } else {
        "$dateStr-$topicSlug"
    }

    val finalSuffix = if (suffix.isNotBlank()) "-$suffix" else ""
    return "$base$finalSuffix.md"
}
