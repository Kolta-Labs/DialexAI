package com.dialex.presentation.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dialex.domain.model.EvidenceItem
import com.dialex.domain.model.EvidenceSourceType
import com.dialex.domain.model.RoundEvidence
import com.dialex.domain.model.totalItemCount
import com.dialex.theme.LocalCcColors
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay

@Composable
fun RoundEvidenceDrawer(
    evidenceList: ImmutableList<RoundEvidence>,
    selectedRound: Int?,
    onRoundSelect: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cc = LocalCcColors.current
    val totalCount = evidenceList.totalItemCount()

    val availableRounds = remember(evidenceList) {
        evidenceList.map { it.round }.distinct().sorted()
    }

    val displayedItems = remember(evidenceList, selectedRound) {
        if (selectedRound == null) {
            evidenceList.flatMap { it.items }
        } else {
            evidenceList.find { it.round == selectedRound }?.items.orEmpty()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp)),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = cc.accent.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.4f)),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.TravelExplore,
                                    contentDescription = null,
                                    tint = cc.accent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Round-Aware Dynamic Evidence RAG",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = cc.textPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = cc.accent.copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, cc.accent.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        text = "$totalCount Items Injected",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = cc.accent,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Authoritative grounding passages dynamically retrieved between rounds from Knowledge Graph & project files",
                                fontSize = 12.sp,
                                color = cc.textMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            tint = cc.textMuted
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Round Filter Tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EvidenceRoundChip(
                        label = "All Rounds ($totalCount)",
                        isSelected = selectedRound == null,
                        onClick = { onRoundSelect(null) }
                    )
                    availableRounds.forEach { r ->
                        val count = evidenceList.find { it.round == r }?.items?.size ?: 0
                        EvidenceRoundChip(
                            label = "Round $r ($count)",
                            isSelected = selectedRound == r,
                            onClick = { onRoundSelect(r) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(thickness = 0.5.dp, color = cc.border.copy(alpha = 0.6f))
                Spacer(Modifier.height(16.dp))

                // Content List
                if (displayedItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = cc.textMuted.copy(alpha = 0.6f),
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = "No dynamic evidence retrieved for this selection",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = cc.textMuted
                            )
                            Text(
                                text = "When council members argue empirical assertions or technical trade-offs, authoritative citations will appear here automatically.",
                                fontSize = 12.sp,
                                color = cc.textMuted.copy(alpha = 0.8f),
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(displayedItems, key = { it.id }) { item ->
                            EvidenceItemCard(item = item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceRoundChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val cc = LocalCcColors.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) cc.accent else cc.panelAlt,
        border = BorderStroke(1.dp, if (isSelected) cc.accent else cc.border),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else cc.textPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun EvidenceItemCard(item: EvidenceItem) {
    val cc = LocalCcColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cc.panelAlt,
        border = BorderStroke(1.dp, cc.border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Source Type Icon + Title + Round Tag + Score + Copy Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val icon = when (item.sourceType) {
                        EvidenceSourceType.KNOWLEDGE_GRAPH -> Icons.Outlined.Hub
                        EvidenceSourceType.ATTACHED_FILE -> Icons.Outlined.Description
                        EvidenceSourceType.WORKSPACE_DOC -> Icons.Outlined.Description
                    }
                    val sourceColor = when (item.sourceType) {
                        EvidenceSourceType.KNOWLEDGE_GRAPH -> Color(0xFF7C4DFF)
                        EvidenceSourceType.ATTACHED_FILE -> Color(0xFF0288D1)
                        EvidenceSourceType.WORKSPACE_DOC -> Color(0xFF00897B)
                    }

                    Icon(
                        icon,
                        contentDescription = null,
                        tint = sourceColor,
                        modifier = Modifier.size(16.dp)
                    )

                    Text(
                        text = item.sourceTitle,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = cc.textPrimary
                    )

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = sourceColor.copy(alpha = 0.12f),
                        border = BorderStroke(0.5.dp, sourceColor.copy(alpha = 0.35f))
                    ) {
                        Text(
                            text = when (item.sourceType) {
                                EvidenceSourceType.KNOWLEDGE_GRAPH -> "Knowledge Graph"
                                EvidenceSourceType.ATTACHED_FILE -> "Attached File"
                                EvidenceSourceType.WORKSPACE_DOC -> "Workspace Doc"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = sourceColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cc.accent.copy(alpha = 0.10f),
                        border = BorderStroke(0.5.dp, cc.accent.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "Injected for Round ${item.round}",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = cc.accent,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Relevance Score & Copy Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Relevance Score Gauge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2E7D32))
                        )
                        Text(
                            text = item.formattedScore(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32)
                        )
                    }

                    // Copy Attribution Tag
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cc.panel,
                        border = BorderStroke(0.5.dp, cc.border),
                        modifier = Modifier.clickable {
                            clipboard.setText(AnnotatedString(item.attributionBadge))
                            copied = true
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                contentDescription = "Copy Citation",
                                tint = if (copied) Color(0xFF2E7D32) else cc.textMuted,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = if (copied) "Copied" else "Copy Citation",
                                fontSize = 11.sp,
                                color = if (copied) Color(0xFF2E7D32) else cc.textMuted
                            )
                        }
                    }
                }
            }

            // Trigger Query Note
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Trigger Query:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cc.textMuted
                )
                Text(
                    text = "\"${item.query}\"",
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    color = cc.textPrimary
                )
            }

            // Excerpt Snippet
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = cc.panel,
                border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.7f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "“${item.snippet}”",
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.SansSerif,
                    color = cc.textPrimary,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}
