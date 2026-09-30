package com.dialex.presentation.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.DeliverableFormat
import com.dialex.model.Discussion
import com.dialex.model.DiscussionArtifact
import com.dialex.model.DiscussionStatus
import com.dialex.presentation.chat.ChatTypographySettings
import com.dialex.theme.CcPalette
import com.dialex.ui.MarkdownText
import com.dialex.ui.ThemedTooltipBox
import com.dialex.util.formatDateTime
import com.dialex.util.formatMessageTimestamp
import kotlinx.coroutines.delay

@Composable
fun DeliverableBlock(
    cc: CcPalette,
    discussion: Discussion,
    typographySettings: ChatTypographySettings,
    milestoneRound: Int? = null,
    generatingFormat: DeliverableFormat? = null,
    onOpenSummary: (() -> Unit)? = null,
    onOpenArtifacts: (() -> Unit)? = null,
    onGenerateFormat: ((DeliverableFormat) -> Unit)? = null,
    onExportDeliverable: ((content: String, name: String) -> Unit)? = null
) {
    val moderatorProvider = discussion.config.moderation.moderatorProvider ?: discussion.config.primary.provider
    val moderatorName = discussion.labelFor(moderatorProvider)
    val moderatorColor = Color(0xFFFF9800)

    var activeFormat by remember(discussion.id, milestoneRound) {
        mutableStateOf<DeliverableFormat?>(generatingFormat)
    }

    LaunchedEffect(generatingFormat) {
        if (generatingFormat != null) {
            activeFormat = generatingFormat
        }
    }

    val formats = listOf(
        DeliverableFormat.ACTION_PLAN to ("Action Plan" to Icons.AutoMirrored.Outlined.Assignment),
        DeliverableFormat.DECISION_MATRIX to ("Decision Matrix" to Icons.Outlined.Balance),
        DeliverableFormat.PRO_CON_LIST to ("Pro/Con List" to Icons.AutoMirrored.Outlined.FormatListBulleted),
        DeliverableFormat.EXECUTIVE_BRIEF to ("Executive Brief" to Icons.AutoMirrored.Outlined.Article),
        DeliverableFormat.DECISION_SUMMARY to ("Decision Summary" to Icons.AutoMirrored.Outlined.FactCheck),
        DeliverableFormat.CUSTOM to ("Custom" to Icons.Outlined.AutoAwesome),
    )

    fun resolveContentForFormat(fmt: DeliverableFormat): String? {
        if (milestoneRound != null) {
            val art = discussion.artifacts.firstOrNull { a ->
                (a.round == milestoneRound || a.timestamp == "Round $milestoneRound" || a.id.endsWith("_r$milestoneRound") || a.name.contains("(Round $milestoneRound)")) &&
                (a.name.contains(fmt.name.replace('_', ' '), ignoreCase = true) ||
                 a.id.startsWith(fmt.name.lowercase()) ||
                 (fmt == DeliverableFormat.ACTION_PLAN && a.name.contains("Action Plan", ignoreCase = true)) ||
                 (fmt == DeliverableFormat.DECISION_MATRIX && a.name.contains("Matrix", ignoreCase = true)) ||
                 (fmt == DeliverableFormat.PRO_CON_LIST && a.name.contains("Pro/Con", ignoreCase = true)) ||
                 (fmt == DeliverableFormat.EXECUTIVE_BRIEF && a.name.contains("Brief", ignoreCase = true)) ||
                 (fmt == DeliverableFormat.DECISION_SUMMARY && (a.name.contains("Summary", ignoreCase = true) || a.type.equals("Summary", ignoreCase = true))))
            }
            if (art != null && art.content.isNotBlank()) return art.content

            val roundDel = discussion.artifacts.firstOrNull { a ->
                (a.round == milestoneRound || a.timestamp == "Round $milestoneRound" || a.id.endsWith("_r$milestoneRound") || a.name.contains("(Round $milestoneRound)")) &&
                (a.type.equals("Deliverable", ignoreCase = true) || a.id.startsWith("art_del") || a.type.equals("Summary", ignoreCase = true) || a.id.startsWith("art_sum"))
            }
            if (roundDel != null && roundDel.content.isNotBlank()) return roundDel.content

            val maxAgentRound = discussion.transcript.filterNot { it.isUserComment || it.isError }.maxOfOrNull { it.round } ?: 1
            if (milestoneRound >= maxAgentRound && (!discussion.summary.isNullOrBlank() || !discussion.conclusion.isNullOrBlank())) {
                return discussion.summary ?: discussion.conclusion
            }
            return null
        }

        val art = discussion.artifacts.firstOrNull { a ->
            !a.id.contains("_r") && !a.name.contains("(Round ") &&
            (a.name.contains(fmt.name.replace('_', ' '), ignoreCase = true) ||
            a.id.startsWith(fmt.name.lowercase()) ||
            (fmt == DeliverableFormat.ACTION_PLAN && a.name.contains("Action Plan", ignoreCase = true)) ||
            (fmt == DeliverableFormat.DECISION_MATRIX && a.name.contains("Matrix", ignoreCase = true)) ||
            (fmt == DeliverableFormat.PRO_CON_LIST && a.name.contains("Pro/Con", ignoreCase = true)) ||
            (fmt == DeliverableFormat.EXECUTIVE_BRIEF && a.name.contains("Brief", ignoreCase = true)) ||
            (fmt == DeliverableFormat.DECISION_SUMMARY && (a.name.contains("Summary", ignoreCase = true) || a.type.equals("Summary", ignoreCase = true))))
        }
        if (art != null && art.content.isNotBlank()) return art.content

        if (discussion.config.deliverable.format == fmt && !discussion.deliverable.isNullOrBlank()) {
            return discussion.deliverable
        }

        if (fmt == DeliverableFormat.DECISION_SUMMARY && (!discussion.summary.isNullOrBlank() || !discussion.conclusion.isNullOrBlank())) {
            return discussion.summary ?: discussion.conclusion
        }

        return null
    }

    val outcomeSummary = (if (milestoneRound != null) resolveContentForFormat(DeliverableFormat.DECISION_SUMMARY) else (discussion.summary ?: discussion.conclusion))
        ?: resolveContentForFormat(DeliverableFormat.DECISION_SUMMARY)
        ?: discussion.summary
        ?: discussion.conclusion
        ?: discussion.deliverable
        ?: ""

    val isConsensus = discussion.transcript.takeLast(discussion.config.agents.size.coerceAtLeast(1))
        .any { it.content.trim().lowercase().startsWith("agreed") }

    val milestoneArtifact = if (milestoneRound != null) {
        discussion.artifacts.find { it.round == milestoneRound || it.id.contains("_r$milestoneRound") || it.timestamp.contains("Round $milestoneRound") || it.name.contains("(Round $milestoneRound)") }
    } else {
        discussion.artifacts.find { it.type.equals("deliverable", ignoreCase = true) || it.type.equals("summary", ignoreCase = true) }
    }
    val generatedTimeLabel = when {
        milestoneArtifact?.timestampMs != null && milestoneArtifact.timestampMs > 0L -> formatMessageTimestamp(milestoneArtifact.timestampMs)
        milestoneArtifact?.timestamp?.isNotBlank() == true && !milestoneArtifact.timestamp.startsWith("Round") && !milestoneArtifact.timestamp.equals("Live", ignoreCase = true) -> milestoneArtifact.timestamp
        milestoneRound != null -> discussion.transcript.findLast { it.round == milestoneRound }?.let { if (it.timestampMs > 0L) formatMessageTimestamp(it.timestampMs) else null }
        else -> discussion.transcript.lastOrNull()?.let { if (it.timestampMs > 0L) formatMessageTimestamp(it.timestampMs) else null }
    }

    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val activeSelectedFormat = activeFormat
    val activeDeliverableContent = activeSelectedFormat?.let { resolveContentForFormat(it) }

    if (milestoneRound == null && (discussion.isConsensusReached || discussion.earlyExitReason == "CONSENSUS")) {
        val maxRound = discussion.transcript.filter { !it.isError && !it.isSystem }.maxOfOrNull { it.round } ?: 1
        val configuredRounds = discussion.config.maxRounds
        val savedRounds = (configuredRounds - maxRound).coerceAtLeast(0)
        val agentCount = discussion.config.agents.size
        val savedTurns = savedRounds * agentCount
        val estimatedTokensSaved = savedTurns * 2500
        val estimatedSpendSavedUsd = (estimatedTokensSaved / 1000.0) * 0.04

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF2E7D32).copy(alpha = if (cc.isDark) 0.15f else 0.08f),
            border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "⚡ Early Consensus Reached in Round $maxRound of $configuredRounds",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (cc.isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                )
                Spacer(Modifier.height(4.dp))
                val spendFormatted = ((estimatedSpendSavedUsd * 100).toInt() / 100.0).toString()
                Text(
                    "Saved approximately $savedTurns turns (~$estimatedTokensSaved tokens / ~$$spendFormatted API spend) by avoiding redundant rounds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textPrimary
                )
            }
        }
    }

    if (milestoneRound == null && (discussion.status == DiscussionStatus.COMPLETED_WITH_WARNING || discussion.warning != null)) {
        val completedTurns = discussion.transcript.count { !it.isError && !it.isSystem && !it.isUserComment }
        val maxRound = discussion.transcript.filter { !it.isError }.maxOfOrNull { it.round } ?: 1
        val interruptedRound = discussion.transcript.findLast { it.isError }?.round ?: (maxRound + 1)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cc.accent.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "ℹ️ Deliberation Concluded at Round $maxRound",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = cc.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    discussion.warning ?: "A network interruption occurred in Round $interruptedRound. The Deliberation Moderator successfully synthesized the final outcome from the $completedTurns completed turns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )
            }
        }
    }
    val isGeneratingActive = activeSelectedFormat != null && generatingFormat == activeSelectedFormat

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = moderatorColor.copy(alpha = if (cc.isDark) 0.05f else 0.03f).compositeOver(cc.panelAlt),
        border = BorderStroke(1.25.dp, moderatorColor.copy(alpha = if (cc.isDark) 0.55f else 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Signature vertical moderator accent bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(moderatorColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(18.dp)
            ) {
                // ── 1. Header: Moderator Identity, Badge, & Tools ─────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(moderatorColor.copy(alpha = 0.15f))
                                .border(1.dp, moderatorColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = "Moderator",
                                tint = moderatorColor,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Moderator ($moderatorName)",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp
                                    ),
                                    color = cc.textPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = moderatorColor.copy(alpha = 0.15f),
                                    border = BorderStroke(0.75.dp, moderatorColor.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = if (milestoneRound != null) "ROUND $milestoneRound OUTCOME" else if (isConsensus) "CONSENSUS OUTCOME" else "OUTCOME RESOLUTION",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.5.sp,
                                            letterSpacing = 0.4.sp
                                        ),
                                        color = moderatorColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Deliberation synthesis & reply to topic",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 11.sp
                                    ),
                                    color = cc.textMuted
                                )
                                if (!generatedTimeLabel.isNullOrBlank()) {
                                    Text(
                                        "· $generatedTimeLabel",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = cc.textMuted
                                    )
                                }
                            }
                        }
                    }

                    // Header Tools (≥48dp touch targets)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ThemedTooltipBox(if (copied) "Copied!" else "Copy Consensus Outcome") {
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(outcomeSummary))
                                    copied = true
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy Summary",
                                    tint = if (copied) cc.accent else cc.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (onExportDeliverable != null && outcomeSummary.isNotBlank()) {
                            ThemedTooltipBox("Save Outcome to disk") {
                                IconButton(
                                    onClick = {
                                        val fileName = "${discussion.name.replace(' ', '_').lowercase()}_outcome.md"
                                        onExportDeliverable(outcomeSummary, fileName)
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = "Export Outcome",
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        if (onOpenArtifacts != null) {
                            ThemedTooltipBox("Open in Artifacts Panel") {
                                IconButton(
                                    onClick = onOpenArtifacts,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.OpenInNew,
                                        contentDescription = "Open in Artifacts Panel",
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (discussion.attachedFiles.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .padding(top = 4.dp, bottom = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onOpenArtifacts?.invoke() }
                    ) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = "Grounded Documents",
                            tint = moderatorColor,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            "Grounded in ${discussion.attachedFiles.size} attached document${if (discussion.attachedFiles.size > 1) "s" else ""} · Authoritative source citation",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                            color = moderatorColor
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)
                Spacer(Modifier.height(12.dp))

                // ── 2. Crisp Outcome Summary & Answer to Topic ─────────
                if (outcomeSummary.isNotBlank()) {
                    SelectionContainer {
                        MarkdownText(
                            text = outcomeSummary,
                            cc = cc,
                            textColor = cc.textPrimary,
                            baseFontSize = typographySettings.fontSizeSp.sp,
                            lineHeightMultiplier = typographySettings.lineSpacingMultiplier
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = moderatorColor)
                        Text(
                            "Synthesizing consensus outcome summary…",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            color = cc.textMuted
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── 3. Generative Deliverables Buttons Section ─────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cc.panel.copy(alpha = 0.6f))
                        .border(0.75.dp, cc.border.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Layers,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Generate Deliverables",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                ),
                                color = cc.textPrimary
                            )
                        }
                        Text(
                            "One-click synthesis from debate consensus",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = cc.textMuted
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Horizontal Scrollable Generative Buttons Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        formats.forEach { (fmt, info) ->
                            val (label, _) = info
                            val fmtContent = resolveContentForFormat(fmt)
                            val isAvailable = !fmtContent.isNullOrBlank()
                            val isGeneratingThis = generatingFormat == fmt
                            val isSelected = activeSelectedFormat == fmt && isAvailable

                            val pillShape = RoundedCornerShape(8.dp)
                            Surface(
                                shape = pillShape,
                                color = when {
                                    isSelected -> cc.accent
                                    isAvailable -> if (cc.isDark) Color(0xFF242531) else Color(0xFFEBECEF)
                                    else -> cc.panelAlt
                                },
                                border = BorderStroke(
                                    width = if (isSelected) 1.dp else 0.75.dp,
                                    color = when {
                                        isSelected -> cc.accent
                                        isAvailable -> cc.accent.copy(alpha = 0.5f)
                                        else -> cc.border.copy(alpha = 0.4f)
                                    }
                                ),
                                modifier = Modifier
                                    .clip(pillShape)
                                    .clickable {
                                        if (isAvailable) {
                                            activeFormat = if (activeSelectedFormat == fmt) null else fmt
                                        } else if (!isGeneratingThis) {
                                            activeFormat = fmt
                                            onGenerateFormat?.invoke(fmt)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    when {
                                        isGeneratingThis -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 1.8.dp,
                                                color = if (isSelected) Color.White else cc.accent
                                            )
                                        }
                                        isAvailable -> {
                                            Icon(
                                                Icons.Outlined.Check,
                                                contentDescription = "Generated",
                                                tint = if (isSelected) Color.White else cc.accent,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                        else -> {
                                            Icon(
                                                Icons.Outlined.Add,
                                                contentDescription = "Generate $label",
                                                tint = cc.textMuted,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected || isAvailable) FontWeight.SemiBold else FontWeight.Medium
                                        ),
                                        color = when {
                                            isSelected -> Color.White
                                            isAvailable -> cc.textPrimary
                                            else -> cc.textMuted
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ── 4. Expandable Active Deliverable Viewer Card ─────────
                    if (isGeneratingActive && activeDeliverableContent.isNullOrBlank()) {
                        val activeInfo = formats.firstOrNull { it.first == activeSelectedFormat }?.second ?: ("Deliverable" to Icons.AutoMirrored.Outlined.FactCheck)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(cc.panelAlt, RoundedCornerShape(8.dp))
                                .border(0.75.dp, cc.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cc.accent)
                            Column {
                                Text(
                                    "Synthesizing ${activeInfo.first}…",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                    color = cc.textPrimary
                                )
                                Text(
                                    "Distilling debate consensus into structured ${activeInfo.first.lowercase()}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    color = cc.textMuted
                                )
                            }
                        }
                    } else if (activeSelectedFormat != null && !activeDeliverableContent.isNullOrBlank()) {
                        val activeInfo = formats.firstOrNull { it.first == activeSelectedFormat }?.second ?: ("Deliverable" to Icons.AutoMirrored.Outlined.FactCheck)
                        val activeLabel = activeInfo.first
                        var deliverableCopied by remember { mutableStateOf(false) }
                        LaunchedEffect(deliverableCopied) {
                            if (deliverableCopied) {
                                delay(2000)
                                deliverableCopied = false
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = cc.panelAlt,
                            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(activeInfo.second, contentDescription = null, tint = cc.accent, modifier = Modifier.size(15.dp))
                                        Text(
                                            activeLabel,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                                            color = cc.textPrimary
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        ThemedTooltipBox(if (deliverableCopied) "Copied!" else "Copy $activeLabel") {
                                            IconButton(
                                                onClick = {
                                                    clipboard.setText(AnnotatedString(activeDeliverableContent))
                                                    deliverableCopied = true
                                                },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(
                                                    if (deliverableCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                                    contentDescription = "Copy",
                                                    tint = if (deliverableCopied) cc.accent else cc.textMuted,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                        if (onExportDeliverable != null) {
                                            ThemedTooltipBox("Save $activeLabel to disk") {
                                                IconButton(
                                                    onClick = {
                                                        val fileName = "${discussion.name.replace(' ', '_').lowercase()}_${activeSelectedFormat.name.lowercase()}.md"
                                                        onExportDeliverable(activeDeliverableContent, fileName)
                                                    },
                                                    modifier = Modifier.size(48.dp)
                                                ) {
                                                    Icon(Icons.Outlined.Download, contentDescription = "Save", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                                }
                                            }
                                        }
                                        ThemedTooltipBox("Close deliverable view") {
                                            IconButton(
                                                onClick = { activeFormat = null },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)
                                Spacer(Modifier.height(8.dp))

                                SelectionContainer {
                                    MarkdownText(
                                        text = activeDeliverableContent,
                                        cc = cc,
                                        textColor = cc.textPrimary,
                                        baseFontSize = typographySettings.fontSizeSp.sp,
                                        lineHeightMultiplier = typographySettings.lineSpacingMultiplier
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtifactStrip(
    cc: CcPalette,
    artifact: DiscussionArtifact,
    onView: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (cc.isDark) Color(0xFF1A1B22) else Color(0xFFF8F9FA),
        border = BorderStroke(0.75.dp, if (cc.isDark) Color(0xFF2E2E38) else Color(0xFFE5E7EB)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    when (artifact.type.lowercase()) {
                        "deliverable" -> Icons.AutoMirrored.Outlined.Article
                        "summary" -> Icons.Outlined.Summarize
                        else -> Icons.AutoMirrored.Outlined.ReceiptLong
                    },
                    contentDescription = null,
                    tint = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280),
                    modifier = Modifier.size(14.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "${artifact.type.lowercase().replaceFirstChar { it.uppercase() }} generated",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = cc.textPrimary
                        )
                        val timeLabel = when {
                            artifact.timestampMs > 0L -> formatMessageTimestamp(artifact.timestampMs)
                            artifact.timestamp.isNotBlank() && !artifact.timestamp.equals("Live", ignoreCase = true) -> artifact.timestamp
                            else -> ""
                        }
                        if (timeLabel.isNotBlank()) {
                            Text(
                                "· $timeLabel",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = cc.textMuted
                            )
                        }
                    }
                    Text(
                        artifact.name + if (artifact.sizeBytes > 0) " · ${maxOf(1, artifact.sizeBytes / 1024)} KB" else "",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = cc.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (cc.isDark) Color(0xFF282832) else Color(0xFFE5E7EB),
                    border = BorderStroke(0.5.dp, if (cc.isDark) Color(0xFF3E3E48) else Color(0xFFD1D5DB)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { onView() }
                ) {
                    Text(
                        "View Artifact",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                ThemedTooltipBox("Dismiss banner") {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss artifact banner",
                            tint = cc.textMuted,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ArtifactsSlidingPane(
    discussion: Discussion,
    onClose: () -> Unit,
    onExport: (content: String, fileName: String) -> Unit,
    typographySettings: ChatTypographySettings,
    cc: CcPalette,
    modifier: Modifier = Modifier,
    onDeleteArtifact: ((String) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    var copiedContent by remember { mutableStateOf(false) }
    var selectedArtifact by remember { mutableStateOf<DiscussionArtifact?>(null) }
    var isRawView by remember { mutableStateOf(false) }

    val allArtifacts = remember(discussion) {
        discussion.resolveAllArtifacts().sortedWith(compareBy<DiscussionArtifact> { if (it.timestampMs > 0L) it.timestampMs else 0L }.thenBy { it.id })
    }

    Surface(
        color = cc.panel,
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selectedArtifact == null) {
                // ── LIST VIEW ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (cc.isDark) Color(0xFF282832) else Color(0xFFE5E7EB)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280), modifier = Modifier.size(16.dp))
                        }
                        Column {
                            Text(
                                "Artifacts",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                                color = cc.textPrimary
                            )
                            Text(
                                "${allArtifacts.size} saved file${if (allArtifacts.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                    }
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)

                if (allArtifacts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.FolderZip, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(36.dp))
                            Text("No artifacts yet", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = cc.textPrimary)
                            Text(
                                "Deliverables, summaries, and transcripts appear here as the debate progresses.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = cc.textMuted,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(allArtifacts.size) { idx ->
                            val art = allArtifacts[idx]
                            val effectiveRound = discussion.effectiveRoundFor(art)
                            val formattedDateTime = when {
                                art.timestampMs > 0L -> formatDateTime(art.timestampMs)
                                art.timestamp.isNotBlank() && !art.timestamp.equals("Live", ignoreCase = true) -> art.timestamp
                                else -> "Attached"
                            }
                            val sizeLabel = "${maxOf(1, art.sizeBytes / 1024)} KB"

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedArtifact = art
                                        isRawView = false
                                        copiedContent = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (cc.isDark) Color(0xFF25262E) else Color(0xFFF3F4F6)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                when (art.type.lowercase()) {
                                                    "deliverable" -> Icons.AutoMirrored.Outlined.Article
                                                    "summary" -> Icons.Outlined.Summarize
                                                    else -> Icons.AutoMirrored.Outlined.ReceiptLong
                                                },
                                                contentDescription = null,
                                                tint = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                art.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                                color = cc.textPrimary,
                                                maxLines = 1
                                            )
                                            Spacer(Modifier.height(3.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Surface(
                                                    shape = RoundedCornerShape(3.dp),
                                                    color = if (cc.isDark) Color(0xFF282832) else Color(0xFFE5E7EB)
                                                ) {
                                                    Text(
                                                        art.type.uppercase(),
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                        color = if (cc.isDark) Color(0xFFD1D5DB) else Color(0xFF4B5563),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                                if (effectiveRound != null) {
                                                    Surface(
                                                        shape = RoundedCornerShape(3.dp),
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (cc.isDark) 0.22f else 0.12f)
                                                    ) {
                                                        Text(
                                                            "ROUND $effectiveRound",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    "$sizeLabel · $formattedDateTime",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                    color = cc.textMuted
                                                )
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (onDeleteArtifact != null && art.id.isNotBlank() && !art.id.startsWith("pseudo_")) {
                                            IconButton(
                                                onClick = { onDeleteArtifact(art.id) },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Delete,
                                                    contentDescription = "Delete artifact",
                                                    tint = cc.textMuted.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                        }
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "Open Document",
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ── DOCUMENT VIEWER VIEW ──
                val currentArt = selectedArtifact!!

                // Document Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = cc.panelAlt,
                            border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selectedArtifact = null }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "All Artifacts", tint = cc.textPrimary, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("All", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                            }
                        }

                        Text(
                            currentArt.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = cc.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Raw / Preview Toggle
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isRawView) (if (cc.isDark) Color(0xFF2E2E38) else Color(0xFFE5E7EB)) else cc.panelAlt,
                            border = BorderStroke(0.5.dp, if (isRawView) (if (cc.isDark) Color(0xFF6B7280) else Color(0xFF9CA3AF)) else cc.border.copy(alpha = 0.4f)),
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { isRawView = !isRawView }
                        ) {
                            Text(
                                if (isRawView) "Raw" else "Preview",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isRawView) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isRawView) cc.textPrimary else cc.textMuted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }

                        // Copy Button (Direct 1-Click)
                        ThemedTooltipBox(if (copiedContent) "Copied!" else "Copy to clipboard") {
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(currentArt.content))
                                    copiedContent = true
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    if (copiedContent) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = if (copiedContent) Color(0xFF4CAF50) else cc.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // More Copy Options Menu
                        var detailOverflowOpen by remember { mutableStateOf(false) }
                        Box {
                            ThemedTooltipBox("More options") {
                                IconButton(
                                    onClick = { detailOverflowOpen = true },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More Options", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                }
                            }

                            DropdownMenu(
                                expanded = detailOverflowOpen,
                                onDismissRequest = { detailOverflowOpen = false },
                                modifier = Modifier.background(cc.panel)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                "Copy with Topic & Title",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = cc.textPrimary
                                            )
                                            Text(
                                                "Includes 'Topic: ...' and '${currentArt.name}:'",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = cc.textMuted
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null, tint = cc.accent, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        detailOverflowOpen = false
                                        val topicText = discussion.config.topic.ifBlank { discussion.name }
                                        val formatted = buildString {
                                            append("Topic: ")
                                            append(topicText)
                                            append("\n\n")
                                            append("${currentArt.name}:\n")
                                            append(currentArt.content)
                                        }
                                        clipboard.setText(AnnotatedString(formatted))
                                        copiedContent = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                "Copy Raw Content",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = cc.textPrimary
                                            )
                                            Text(
                                                "Markdown body only",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = cc.textMuted
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        detailOverflowOpen = false
                                        clipboard.setText(AnnotatedString(currentArt.content))
                                        copiedContent = true
                                    }
                                )
                            }
                        }

                        // Download / Export Button
                        ThemedTooltipBox("Download / Export") {
                            IconButton(
                                onClick = { onExport(currentArt.content, currentArt.name) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Outlined.FileDownload, contentDescription = "Download", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                            }
                        }

                        // Delete Button
                        if (onDeleteArtifact != null && currentArt.id.isNotBlank() && !currentArt.id.startsWith("pseudo_")) {
                            ThemedTooltipBox("Delete artifact") {
                                IconButton(
                                    onClick = {
                                        onDeleteArtifact(currentArt.id)
                                        selectedArtifact = null
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete artifact", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        // Close Entire Pane
                        ThemedTooltipBox("Close Artifacts Pane") {
                            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)

                // Document Metadata Subheader
                val currentEffectiveRound = discussion.effectiveRoundFor(currentArt)
                val roundPrefix = if (currentEffectiveRound != null) "Round $currentEffectiveRound · " else ""
                val formattedViewerDateTime = when {
                    currentArt.timestampMs > 0L -> formatDateTime(currentArt.timestampMs)
                    currentArt.timestamp.isNotBlank() && !currentArt.timestamp.equals("Live", ignoreCase = true) -> currentArt.timestamp
                    else -> "Attached"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cc.panelAlt.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$roundPrefix${currentArt.format.uppercase()} · ${maxOf(1, currentArt.sizeBytes / 1024)} KB · ~${currentArt.content.length / 4} tokens",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = cc.textMuted
                    )
                    Text(
                        formattedViewerDateTime,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = cc.textMuted
                    )
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.5.dp)

                // Document Viewport
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (isRawView) {
                        Text(
                            text = currentArt.content,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = cc.textPrimary
                        )
                    } else {
                        MarkdownText(
                            text = currentArt.content,
                            cc = cc,
                            textColor = cc.textPrimary,
                            baseFontSize = 13.sp,
                            lineHeightMultiplier = 1.35f
                        )
                    }
                }
            }
        }
    }
}
