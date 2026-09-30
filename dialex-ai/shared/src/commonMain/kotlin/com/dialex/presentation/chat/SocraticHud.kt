package com.dialex.presentation.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.LedgerItemType
import com.dialex.domain.model.SocraticLedgerItem
import com.dialex.domain.model.SocraticStage
import com.dialex.domain.model.SocraticStance
import com.dialex.theme.LocalCcColors

@Composable
fun SocraticHud(
    stance: SocraticStance,
    stage: SocraticStage,
    interviewerName: String,
    ledger: List<SocraticLedgerItem>,
    activeProbe: String?,
    onGenerateDigest: () -> Unit,
    isGeneratingDigest: Boolean,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    var isExpanded by remember { mutableStateOf(true) }

    val hardenedItems = ledger.filter { it.type == LedgerItemType.HARDENED }
    val concededItems = ledger.filter { it.type == LedgerItemType.CONCEDED }
    val underSiegeItems = ledger.filter { it.type == LedgerItemType.UNDER_SIEGE }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (cc.isDark) Color(0xFF1E1E24) else Color(0xFFF9FAFB),
        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .animateContentSize()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Stance pill, Stage indicator, and Collapse/Expand toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                        border = BorderStroke(0.75.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.TipsAndUpdates,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                stance.displayName,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.5.sp
                                ),
                                color = if (cc.isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.4f))
                    ) {
                        Text(
                            stage.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = cc.textMuted,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }

                    Text(
                        "Examiner: $interviewerName",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = cc.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Generate Digest CTA
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        border = BorderStroke(0.75.dp, Color(0xFF10B981).copy(alpha = 0.6f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = !isGeneratingDigest) { onGenerateDigest() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (isGeneratingDigest) {
                                CircularProgressIndicator(
                                    strokeWidth = 1.5.dp,
                                    color = Color(0xFF10B981),
                                    modifier = Modifier.size(11.dp)
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                            Text(
                                if (isGeneratingDigest) "Compiling..." else "Generate Digest",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 11.sp
                                ),
                                color = if (cc.isDark) Color(0xFF34D399) else Color(0xFF059669)
                            )
                        }
                    }

                    // Collapse toggle
                    Surface(
                        shape = CircleShape,
                        color = Color.Transparent,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { isExpanded = !isExpanded }
                    ) {
                        Icon(
                            if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = cc.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Epistemic Ledger Items
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Hardened Invariants Column
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                                Text(
                                    "Hardened Invariants (${hardenedItems.size})",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    ),
                                    color = if (cc.isDark) Color(0xFF34D399) else Color(0xFF059669)
                                )
                            }

                            if (hardenedItems.isEmpty()) {
                                Text(
                                    "No invariants validated yet",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                                    color = cc.textMuted.copy(alpha = 0.6f)
                                )
                            } else {
                                hardenedItems.take(3).forEach { item ->
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(11.dp).padding(top = 2.dp)
                                        )
                                        Text(
                                            item.statement,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = cc.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        // Conceded Axioms Column
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFEF4444))
                                )
                                Text(
                                    "Conceded / Relinquished (${concededItems.size})",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.sp
                                    ),
                                    color = if (cc.isDark) Color(0xFFF87171) else Color(0xFFDC2626)
                                )
                            }

                            if (concededItems.isEmpty()) {
                                Text(
                                    "No axioms conceded",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
                                    color = cc.textMuted.copy(alpha = 0.6f)
                                )
                            } else {
                                concededItems.take(3).forEach { item ->
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(11.dp).padding(top = 2.dp)
                                        )
                                        Text(
                                            item.statement,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 11.sp,
                                                textDecoration = TextDecoration.LineThrough
                                            ),
                                            color = cc.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Active Probe Dilemma Banner
                    if (!activeProbe.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFF59E0B).copy(alpha = 0.08f),
                            border = BorderStroke(0.75.dp, Color(0xFFF59E0B).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.HelpOutline,
                                    contentDescription = null,
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(14.dp).padding(top = 1.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "Active Dilemma",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 10.5.sp
                                        ),
                                        color = if (cc.isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
                                    )
                                    Text(
                                        activeProbe,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = cc.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
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
