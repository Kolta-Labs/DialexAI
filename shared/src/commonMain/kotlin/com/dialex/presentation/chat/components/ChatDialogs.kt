package com.dialex.presentation.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.presentation.chat.AgentUsage
import com.dialex.model.Discussion
import com.dialex.model.Provider
import com.dialex.model.brandName
import com.dialex.export.resolveExportFileName
import com.dialex.export.toSummaryMarkdown
import com.dialex.presentation.chat.ChatTypographySettings
import com.dialex.theme.CcPalette
import com.dialex.ui.GradientButton
import com.dialex.ui.MarkdownText
import com.dialex.util.formatTokenCount

@Composable
fun DiscussionSummaryDialog(
    discussion: Discussion,
    onDismiss: () -> Unit,
    onExportMarkdown: (content: String, fileName: String) -> Unit,
    typographySettings: ChatTypographySettings,
    cc: CcPalette
) {
    val clipboard = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier
                .widthIn(min = 460.dp, max = 680.dp)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cc.accent.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Summarize, contentDescription = null, tint = cc.accent, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(
                                "Executive Summary & Consensus",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = cc.textPrimary
                            )
                            Text(
                                discussion.name.ifBlank { "Dialex Debate" },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    // Minimum 48dp touch target
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                    }
                }

                // Summary Content Container
                val summaryMarkdown = remember(discussion) { discussion.toSummaryMarkdown() }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 460.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        MarkdownText(
                            text = summaryMarkdown,
                            cc = cc,
                            textColor = cc.textPrimary,
                            baseFontSize = typographySettings.fontSizeSp.sp
                        )
                    }
                }

                // Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(summaryMarkdown))
                                isCopied = true
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = cc.textPrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                if (isCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                tint = if (isCopied) Color(0xFF4CAF50) else cc.textMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (isCopied) "Copied" else "Copy Markdown", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val fileName = discussion.resolveExportFileName("summary")
                                onExportMarkdown(summaryMarkdown, fileName)
                            },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = cc.textPrimary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = null,
                                tint = cc.textMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Export .md", fontSize = 12.sp)
                        }
                    }

                    GradientButton(
                        text = "Close",
                        onClick = onDismiss,
                        height = 34.dp
                    )
                }
            }
        }
    }
}

@Composable
fun UsageModal(usageBreakdown: List<AgentUsage>, onDismiss: () -> Unit, cc: CcPalette) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cc.panel)
                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Token Usage Breakdown",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = cc.textPrimary
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = cc.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (usage in usageBreakdown) {
                    Surface(
                        color = cc.panelAlt,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    usage.agentName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = cc.textPrimary
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${formatTokenCount(usage.tokensIn)} in · ${formatTokenCount(usage.tokensOut)} out",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = cc.textMuted
                                )
                            }
                            Text(
                                "~\$${"%.3f".format(usage.estimatedCostUsd)}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = cc.textPrimary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                GradientButton(
                    text = "Close",
                    onClick = onDismiss,
                    height = 34.dp
                )
            }
        }
    }
}

@Composable
fun DebateErrorDialog(
    discussion: Discussion,
    errorMessage: String,
    agentProvider: Provider?,
    round: Int?,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    cc: CcPalette
) {
    val clipboard = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }
    val errorInfo = remember(errorMessage, agentProvider) {
        parseTurnError(errorMessage, agentProvider ?: Provider.ANTHROPIC)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier
                .widthIn(min = 460.dp, max = 640.dp)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(errorInfo.badgeColor.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(errorInfo.badgeIcon, fontSize = 17.sp)
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    errorInfo.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                                    color = cc.textPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = errorInfo.badgeColor.copy(alpha = 0.15f),
                                    border = BorderStroke(0.5.dp, errorInfo.badgeColor.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = errorInfo.badgeLabel.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                        color = errorInfo.badgeColor,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            val metaText = buildString {
                                if (agentProvider != null) {
                                    append(agentProvider.brandName())
                                }
                                if (round != null && round > 0) {
                                    if (isNotEmpty()) append(" • ")
                                    append("Round $round")
                                }
                                if (isEmpty()) append(discussion.name.ifBlank { "Dialex Debate" })
                            }
                            Text(
                                metaText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                    }
                }

                // Friendly advice banner
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = errorInfo.badgeColor.copy(alpha = if (cc.isDark) 0.08f else 0.05f),
                    border = BorderStroke(0.75.dp, errorInfo.badgeColor.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = errorInfo.summary,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = cc.textPrimary.copy(alpha = 0.88f)
                        )
                        if (!errorInfo.resetSchedule.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = errorInfo.badgeColor, modifier = Modifier.size(14.dp))
                                Text(
                                    "Resets at: ${errorInfo.resetSchedule}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                                    color = errorInfo.badgeColor
                                )
                            }
                        }
                    }
                }

                // Error Details Container
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = cc.textMuted
                            )
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(errorMessage))
                            isCopied = true
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = cc.textPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            if (isCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = if (isCopied) Color(0xFF4CAF50) else cc.textMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isCopied) "Copied" else "Copy Error Details", fontSize = 12.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Close", color = cc.textMuted, fontSize = 12.sp)
                        }

                        GradientButton(
                            text = "Retry Debate",
                            icon = Icons.Outlined.PlayArrow,
                            onClick = {
                                onDismiss()
                                onResume()
                            },
                            height = 34.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContinueDebateDialog(
    cc: CcPalette,
    currentMaxRound: Int,
    configuredMaxRounds: Int,
    isUnlimited: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (additionalRounds: Int, unlimited: Boolean) -> Unit
) {
    var selectedPreset by remember { mutableStateOf<Int?>(3) }
    var isUnlimitedSelected by remember { mutableStateOf(false) }
    var customRoundsText by remember { mutableStateOf("3") }
    var isCustom by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier
                .widthIn(min = 420.dp, max = 520.dp)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cc.accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                "Continue Discussion",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                                color = cc.textPrimary
                            )
                            Text(
                                "Currently completed Round $currentMaxRound",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                    }
                }

                Text(
                    "Choose how many additional rounds you would like the agents to deliberate:",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = cc.textPrimary
                )

                // Quick preset pills: +1, +3, +5, +10
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 3, 5, 10).forEach { rounds ->
                        val isSelected = !isUnlimitedSelected && !isCustom && selectedPreset == rounds
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) cc.accent.copy(alpha = 0.15f) else cc.bg,
                            border = BorderStroke(1.dp, if (isSelected) cc.accent else cc.border.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    isUnlimitedSelected = false
                                    isCustom = false
                                    selectedPreset = rounds
                                    customRoundsText = rounds.toString()
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "+$rounds",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                    color = if (isSelected) cc.accent else cc.textPrimary
                                )
                            }
                        }
                    }
                }

                // Custom & Unlimited Options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isCustomSelected = isCustom && !isUnlimitedSelected
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCustomSelected) cc.accent.copy(alpha = 0.15f) else cc.bg,
                        border = BorderStroke(1.dp, if (isCustomSelected) cc.accent else cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                isUnlimitedSelected = false
                                isCustom = true
                                selectedPreset = null
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Custom",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Medium),
                                color = if (isCustomSelected) cc.accent else cc.textPrimary
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUnlimitedSelected) cc.accent.copy(alpha = 0.15f) else cc.bg,
                        border = BorderStroke(1.dp, if (isUnlimitedSelected) cc.accent else cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                isUnlimitedSelected = true
                                isCustom = false
                                selectedPreset = null
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Unlimited",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isUnlimitedSelected) FontWeight.Bold else FontWeight.Medium),
                                color = if (isUnlimitedSelected) cc.accent else cc.textPrimary
                            )
                        }
                    }
                }

                // If Custom is selected, show input field
                if (isCustom && !isUnlimitedSelected) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Number of additional rounds:",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = cc.textMuted
                        )
                        OutlinedTextField(
                            value = customRoundsText,
                            onValueChange = { str ->
                                val filtered = str.filter { it.isDigit() }
                                customRoundsText = filtered
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = cc.textPrimary),
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. 7", color = cc.textMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = cc.accent,
                                unfocusedBorderColor = cc.border.copy(alpha = 0.5f),
                                focusedTextColor = cc.textPrimary,
                                unfocusedTextColor = cc.textPrimary,
                                cursorColor = cc.accent
                            )
                        )
                    }
                }

                // Summary hint
                Text(
                    text = when {
                        isUnlimitedSelected -> "Agents will continue debating indefinitely until manually paused or concluded."
                        isCustom -> {
                            val add = customRoundsText.toIntOrNull() ?: 1
                            "Will run +$add more round(s), up to Round ${currentMaxRound + add}."
                        }
                        else -> {
                            val add = selectedPreset ?: 3
                            "Will run +$add more round(s), up to Round ${currentMaxRound + add}."
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cc.textMuted
                )

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Cancel", color = cc.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (isUnlimitedSelected) {
                                onConfirm(0, true)
                            } else {
                                val rounds = if (isCustom) {
                                    (customRoundsText.toIntOrNull() ?: 1).coerceAtLeast(1)
                                } else {
                                    selectedPreset ?: 3
                                }
                                onConfirm(rounds, false)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cc.accent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Continue Debate", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
