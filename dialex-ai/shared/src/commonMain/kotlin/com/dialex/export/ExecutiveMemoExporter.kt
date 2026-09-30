package com.dialex.export

import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Provider
import com.dialex.model.brandName
import com.dialex.model.defaultModel
import com.dialex.model.label

/**
 * A safe, clean file name for this discussion's Executive Memorandum export.
 */
fun Discussion.exportMemoFileName(): String {
    val slug = name.trim().lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .ifBlank { "decision-memo" }
        .take(60)
    return "$slug-executive-memo.html"
}

/**
 * Converts this discussion into a standalone, presentation-ready Executive Memorandum HTML document.
 * Features:
 * - Dialex Electric Iris & Cosmic Violet signature gradient accent
 * - Executive summary & consensus verdict
 * - Formatted primary deliverable (Decision Matrix, Action Plan, or Brief)
 * - Deliberation Council participant scorecard
 * - Grounded reference materials registry
 * - Print stylesheet (@media print) optimized for Cmd+P / Ctrl+P to PDF export
 */
fun Discussion.toExecutiveMemorandumHtml(projectName: String? = null): String {
    val deliverableContent = deliverable ?: conclusion ?: summary ?: "No deliverable synthesized yet."
    val safeDeliverableHtml = markdownToHtml(deliverableContent)
    val safeSummaryHtml = summary?.let { markdownToHtml(it) }

    val statusBadgeColor = when (status) {
        DiscussionStatus.DONE, DiscussionStatus.COMPLETED -> "#10B981"
        DiscussionStatus.COMPLETED_WITH_WARNING -> "#F59E0B"
        DiscussionStatus.RUNNING -> "#3B82F6"
        DiscussionStatus.PAUSED -> "#F59E0B"
        DiscussionStatus.ERROR, DiscussionStatus.FAILED -> "#EF4444"
        DiscussionStatus.DRAFT -> "#6B7280"
    }

    val participantsHtml = config.agents.joinToString("") { agent ->
        val providerName = agent.provider.brandName()
        val displayName = agent.label()
        val modelName = agent.model.ifBlank { agent.provider.defaultModel() }
        """
        <div class="council-card">
            <div class="council-card-header">
                <span class="council-name">$displayName</span>
                <span class="council-badge">$providerName</span>
            </div>
            <div class="council-model">$modelName</div>
            ${if (agent.systemPrompt.isNotBlank()) "<div class=\"council-prompt\">\"${escapeHtml(agent.systemPrompt.take(160))}${if (agent.systemPrompt.length > 160) "…" else ""}\"</div>" else ""}
        </div>
        """.trimIndent()
    }

    val attachmentsHtml = if (attachedFiles.isNotEmpty()) {
        val rows = attachedFiles.joinToString("") { f ->
            """
            <tr>
                <td><strong>${escapeHtml(f.name)}</strong></td>
                <td>${f.sizeLabel.ifBlank { "${f.content.length} chars" }}</td>
                <td>${f.tokenEstimateLabel()}</td>
                <td><span class="tag">${escapeHtml(f.scope)}</span></td>
            </tr>
            """.trimIndent()
        }
        """
        <div class="section">
            <h2 class="section-title">Grounded Reference Documents</h2>
            <p class="section-sub">Authoritative source documents attached for evidence citations during deliberation:</p>
            <table class="data-table">
                <thead>
                    <tr><th>Document Name</th><th>Size</th><th>Est. Tokens</th><th>Scope</th></tr>
                </thead>
                <tbody>$rows</tbody>
            </table>
        </div>
        """.trimIndent()
    } else ""

    val credenceHtml = credenceLedger?.let { ledger ->
        if (ledger.hypotheses.isEmpty() || ledger.snapshots.isEmpty()) return@let ""
        val initialSnap = ledger.snapshots.first()
        val latestSnap = ledger.latestSnapshot ?: ledger.snapshots.last()
        val entropy = latestSnap.entropy
        val entropyStr = ((entropy * 100).toInt() / 100.0).toString()

        val rows = ledger.hypotheses.joinToString("") { h ->
            val p0 = initialSnap.probabilityFor(h.id) * 100
            val pn = latestSnap.probabilityFor(h.id) * 100
            val delta = pn - p0
            val deltaSign = if (delta > 0) "+" else ""
            val deltaColor = if (delta > 0) "#10B981" else if (delta < 0) "#EF4444" else "inherit"
            val isDominant = h.id == latestSnap.dominantHypothesis
            val nameStyle = if (isDominant) "font-weight: 700; color: #6366F1;" else "font-weight: 500;"

            """
            <tr>
                <td style="$nameStyle">
                    <strong>${escapeHtml(h.label)}</strong>${if (h.description.isNotBlank()) ": ${escapeHtml(h.description)}" else ""}
                    ${if (isDominant) "<span class=\"tag\" style=\"margin-left: 6px; background: rgba(99, 102, 241, 0.15); color: #6366F1; border-color: rgba(99, 102, 241, 0.3);\">Dominant</span>" else ""}
                </td>
                <td style="text-align: right; font-family: monospace;">${p0.toInt()}%</td>
                <td style="text-align: right; font-family: monospace; font-weight: 600;">${pn.toInt()}%</td>
                <td style="text-align: right; font-family: monospace; color: $deltaColor;">$deltaSign${delta.toInt()}%</td>
            </tr>
            """.trimIndent()
        }

        val allTippingPoints = ledger.snapshots.flatMap { it.tippingPoints }
        val tippingHtml = if (allTippingPoints.isNotEmpty()) {
            val tippingRows = allTippingPoints.joinToString("") { tp ->
                """
                <li><strong>Round ${tp.roundIndex}</strong>: Likelihood Ratio &Lambda; = ${((tp.likelihoodRatio * 10).toInt() / 10.0)} &mdash; ${escapeHtml(tp.evidenceSnippet)}</li>
                """.trimIndent()
            }
            """
            <div style="margin-top: 14px; font-size: 12.5px; color: var(--text-muted);">
                <strong>Epistemic Tipping Points Detected:</strong>
                <ul style="margin: 6px 0 0 18px;">$tippingRows</ul>
            </div>
            """.trimIndent()
        } else ""

        """
        <div class="section">
            <h2 class="section-title">Bayesian Epistemic Credence Matrix</h2>
            <p class="section-sub">Quantitative probabilistic tracking across deliberation rounds with DNA domain authority weighting (Terminal Shannon Entropy: <strong>$entropyStr bits</strong>):</p>
            <table class="data-table">
                <thead>
                    <tr><th>Hypothesis Option</th><th style="text-align: right;">Prior (P₀)</th><th style="text-align: right;">Posterior (P_N)</th><th style="text-align: right;">Net Shift (&Delta;P)</th></tr>
                </thead>
                <tbody>$rows</tbody>
            </table>
            $tippingHtml
        </div>
        """.trimIndent()
    } ?: ""

    return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${escapeHtml(name)} — Executive Memorandum</title>
    <style>
        :root {
            --font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            --bg-canvas: #111115;
            --bg-card: #18181E;
            --bg-card-alt: #22222A;
            --border-color: #2E2E38;
            --text-primary: #F9FAFB;
            --text-muted: #9CA3AF;
            --text-secondary: #D1D5DB;
            --accent-start: #6366F1;
            --accent-mid: #8B5CF6;
            --accent-end: #A855F7;
        }

        @media (prefers-color-scheme: light) {
            :root {
                --bg-canvas: #F9FAFB;
                --bg-card: #FFFFFF;
                --bg-card-alt: #F3F4F6;
                --border-color: #E5E7EB;
                --text-primary: #111827;
                --text-muted: #6B7280;
                --text-secondary: #374151;
                --accent-start: #4F46E5;
                --accent-mid: #6366F1;
                --accent-end: #8B5CF6;
            }
        }

        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: var(--font-sans);
            background-color: var(--bg-canvas);
            color: var(--text-primary);
            line-height: 1.6;
            padding: 32px 16px 64px 16px;
        }

        .container {
            max-width: 880px;
            margin: 0 auto;
            background: var(--bg-card);
            border-radius: 12px;
            border: 1px solid var(--border-color);
            box-shadow: 0 8px 24px rgba(0,0,0,0.18);
            overflow: hidden;
        }

        .top-gradient-bar {
            height: 6px;
            background: linear-gradient(90deg, var(--accent-start) 0%, var(--accent-mid) 50%, var(--accent-end) 100%);
        }

        .action-bar {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 16px 28px;
            background: var(--bg-card-alt);
            border-bottom: 1px solid var(--border-color);
        }

        .brand-badge {
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 13px;
            font-weight: 600;
            color: var(--text-muted);
            letter-spacing: 0.5px;
            text-transform: uppercase;
        }

        .brand-dot {
            width: 8px;
            height: 8px;
            border-radius: 50%;
            background: linear-gradient(135deg, var(--accent-start), var(--accent-end));
        }

        .btn-print {
            background: linear-gradient(90deg, var(--accent-start) 0%, var(--accent-mid) 50%, var(--accent-end) 100%);
            color: #FFFFFF;
            border: none;
            padding: 7px 15px;
            font-size: 12px;
            font-weight: 600;
            border-radius: 6px;
            cursor: pointer;
            transition: opacity 0.15s ease;
        }
        .btn-print:hover { opacity: 0.9; }

        .memo-header {
            padding: 32px 28px 24px 28px;
            border-bottom: 1px solid var(--border-color);
        }

        .pill-row {
            display: flex;
            flex-wrap: wrap;
            gap: 8px;
            align-items: center;
            margin-bottom: 14px;
        }

        .meta-pill {
            display: inline-flex;
            align-items: center;
            padding: 3px 8px;
            border-radius: 4px;
            font-size: 11px;
            font-weight: 600;
            background: var(--bg-card-alt);
            color: var(--text-muted);
            border: 1px solid var(--border-color);
        }

        .status-dot {
            width: 6px;
            height: 6px;
            border-radius: 50%;
            margin-right: 5px;
            background-color: $statusBadgeColor;
        }

        .memo-title {
            font-size: 26px;
            font-weight: 700;
            letter-spacing: -0.4px;
            margin-bottom: 12px;
            color: var(--text-primary);
        }

        .topic-box {
            background: var(--bg-card-alt);
            padding: 14px 16px;
            border-radius: 8px;
            border-left: 3px solid var(--accent-mid);
            font-size: 14px;
            color: var(--text-secondary);
        }

        .section {
            padding: 28px 28px;
            border-bottom: 1px solid var(--border-color);
        }

        .section:last-of-type { border-bottom: none; }

        .section-title {
            font-size: 16px;
            font-weight: 700;
            letter-spacing: -0.2px;
            margin-bottom: 8px;
            color: var(--text-primary);
            display: flex;
            align-items: center;
            gap: 8px;
        }

        .section-sub {
            font-size: 13px;
            color: var(--text-muted);
            margin-bottom: 16px;
        }

        .markdown-body {
            font-size: 14px;
            color: var(--text-secondary);
        }
        .markdown-body p { margin-bottom: 12px; }
        .markdown-body h1, .markdown-body h2, .markdown-body h3 {
            color: var(--text-primary);
            margin: 20px 0 10px 0;
        }
        .markdown-body ul, .markdown-body ol {
            margin: 0 0 16px 20px;
        }
        .markdown-body li { margin-bottom: 6px; }
        .markdown-body blockquote {
            padding: 10px 16px;
            background: var(--bg-card-alt);
            border-left: 3px solid var(--border-color);
            border-radius: 4px;
            margin-bottom: 14px;
        }

        .council-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
            gap: 12px;
            margin-top: 14px;
        }

        .council-card {
            background: var(--bg-card-alt);
            border: 1px solid var(--border-color);
            border-radius: 8px;
            padding: 12px 14px;
        }

        .council-card-header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            margin-bottom: 4px;
        }

        .council-name {
            font-size: 13px;
            font-weight: 600;
            color: var(--text-primary);
        }

        .council-badge {
            font-size: 10px;
            font-weight: 600;
            color: var(--accent-mid);
            background: rgba(139, 92, 246, 0.12);
            padding: 2px 5px;
            border-radius: 4px;
        }

        .council-model {
            font-size: 11px;
            color: var(--text-muted);
            font-family: monospace;
            margin-bottom: 6px;
        }

        .council-prompt {
            font-size: 11.5px;
            color: var(--text-muted);
            font-style: italic;
            line-height: 1.4;
        }

        .data-table {
            width: 100%;
            border-collapse: collapse;
            font-size: 13px;
            margin-top: 10px;
        }
        .data-table th, .data-table td {
            padding: 8px 12px;
            text-align: left;
            border-bottom: 1px solid var(--border-color);
        }
        .data-table th {
            background: var(--bg-card-alt);
            font-weight: 600;
            color: var(--text-muted);
            font-size: 11px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        .data-table td { color: var(--text-secondary); }

        .tag {
            font-size: 10.5px;
            padding: 2px 6px;
            border-radius: 4px;
            background: var(--bg-card-alt);
            border: 1px solid var(--border-color);
            font-weight: 500;
        }

        .memo-footer {
            padding: 20px 28px;
            background: var(--bg-card-alt);
            border-top: 1px solid var(--border-color);
            display: flex;
            justify-content: space-between;
            align-items: center;
            font-size: 11.5px;
            color: var(--text-muted);
        }

        @media print {
            body {
                background: #FFFFFF !important;
                color: #111827 !important;
                padding: 0 !important;
            }
            .container {
                box-shadow: none !important;
                border: 1px solid #E5E7EB !important;
                max-width: 100% !important;
            }
            .btn-print { display: none !important; }
            .council-card, .topic-box, .data-table th {
                background: #F9FAFB !important;
                border-color: #E5E7EB !important;
            }
            .memo-title, .section-title, .markdown-body h1, .markdown-body h2, .markdown-body h3 {
                color: #111827 !important;
            }
            .markdown-body, .data-table td, .topic-box {
                color: #374151 !important;
            }
            .section { page-break-inside: avoid; }
        }
    </style>
</head>
<body>
    <div class="container">
        <div class="top-gradient-bar"></div>
        <div class="action-bar">
            <div class="brand-badge">
                <span class="brand-dot"></span>
                Dialex Executive Deliberation System
            </div>
            <button class="btn-print" onclick="window.print()">Print to PDF / Share</button>
        </div>

        <header class="memo-header">
            <div class="pill-row">
                <span class="meta-pill"><span class="status-dot"></span>${status.name}</span>
                ${if (!projectName.isNullOrBlank()) "<span class=\"meta-pill\">Project: ${escapeHtml(projectName)}</span>" else ""}
                <span class="meta-pill">${config.agents.size} Deliberators</span>
                <span class="meta-pill">${config.deliverable.format.name.replace('_', ' ')}</span>
            </div>
            <h1 class="memo-title">${escapeHtml(name)}</h1>
            <div class="topic-box">
                <strong>Objective:</strong> ${escapeHtml(config.topic)}
            </div>
        </header>

        ${if (safeSummaryHtml != null) """
        <div class="section">
            <h2 class="section-title">Executive Summary & Consensus Takeaways</h2>
            <div class="markdown-body">$safeSummaryHtml</div>
        </div>
        """ else ""}

        <div class="section">
            <h2 class="section-title">Primary Strategic Deliverable</h2>
            <p class="section-sub">Synthesized actionable output based on multi-agent consensus deliberation:</p>
            <div class="markdown-body">$safeDeliverableHtml</div>
        </div>

        <div class="section">
            <h2 class="section-title">Deliberation Council Scorecard</h2>
            <p class="section-sub">Participating frontier AI agents who evaluated and stress-tested this decision:</p>
            <div class="council-grid">$participantsHtml</div>
        </div>

        $credenceHtml

        $attachmentsHtml

        <footer class="memo-footer">
            <span>Generated by Dialex Multi-Agent Deliberation Engine</span>
            <span>Local & Private Governance</span>
        </footer>
    </div>
</body>
</html>
    """.trimIndent()
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

/**
 * Lightweight, zero-dependency Markdown-to-HTML parser for rendering structured deliverables
 * into pristine HTML without bloat or external dependencies.
 */
private fun markdownToHtml(markdown: String): String {
    val lines = markdown.lines()
    val sb = StringBuilder()
    var inList = false
    var inTable = false

    fun closeList() {
        if (inList) {
            sb.append("</ul>\n")
            inList = false
        }
    }

    fun closeTable() {
        if (inTable) {
            sb.append("</tbody></table>\n")
            inTable = false
        }
    }

    for (line in lines) {
        val trimmed = line.trim()

        if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
            closeList()
            val cells = trimmed.split("|").filter { it.isNotBlank() }.map { it.trim() }
            if (cells.all { it.all { c -> c == '-' || c == ':' } }) {
                // Table separator row
                continue
            }
            if (!inTable) {
                sb.append("<table class=\"data-table\"><thead><tr>")
                cells.forEach { sb.append("<th>").append(formatInline(it)).append("</th>") }
                sb.append("</tr></thead><tbody>\n")
                inTable = true
            } else {
                sb.append("<tr>")
                cells.forEach { sb.append("<td>").append(formatInline(it)).append("</td>") }
                sb.append("</tr>\n")
            }
            continue
        } else {
            closeTable()
        }

        when {
            trimmed.startsWith("### ") -> {
                closeList()
                sb.append("<h3>").append(formatInline(trimmed.removePrefix("### "))).append("</h3>\n")
            }
            trimmed.startsWith("## ") -> {
                closeList()
                sb.append("<h2>").append(formatInline(trimmed.removePrefix("## "))).append("</h2>\n")
            }
            trimmed.startsWith("# ") -> {
                closeList()
                sb.append("<h1>").append(formatInline(trimmed.removePrefix("# "))).append("</h1>\n")
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                if (!inList) {
                    sb.append("<ul>\n")
                    inList = true
                }
                sb.append("<li>").append(formatInline(trimmed.substring(2))).append("</li>\n")
            }
            trimmed.isBlank() -> {
                closeList()
            }
            else -> {
                closeList()
                sb.append("<p>").append(formatInline(trimmed)).append("</p>\n")
            }
        }
    }
    closeList()
    closeTable()
    return sb.toString()
}

private fun formatInline(text: String): String {
    var s = escapeHtml(text)
    // Bold
    s = s.replace(Regex("\\*\\*(.*?)\\*\\*"), "<strong>$1</strong>")
    // Italic
    s = s.replace(Regex("\\*(.*?)\\*"), "<em>$1</em>")
    // Inline code
    s = s.replace(Regex("`(.*?)`"), "<code>$1</code>")
    return s
}
