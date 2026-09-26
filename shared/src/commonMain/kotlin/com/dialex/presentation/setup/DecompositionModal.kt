package com.dialex.presentation.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dialex.domain.model.DecompositionPerspective
import com.dialex.domain.model.ProblemAxis
import com.dialex.domain.model.ProblemDecomposition
import com.dialex.theme.LocalCcColors
import kotlinx.collections.immutable.ImmutableSet

@Composable
fun DecompositionModal(
    decomposition: ProblemDecomposition,
    selectedAxisIds: ImmutableSet<String>,
    onToggleAxis: (String) -> Unit,
    onSelectAllPerspectiveA: () -> Unit,
    onSelectAllPerspectiveB: () -> Unit,
    onApplyToAgenda: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cc = LocalCcColors.current
    var selectedTab by remember { mutableStateOf(0) } // 0 = Perspective A, 1 = Perspective B

    val activePerspective = if (selectedTab == 0) decomposition.perspectiveA else decomposition.perspectiveB
    val totalSelected = selectedAxisIds.size

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 640.dp

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = if (isCompact) Alignment.BottomCenter else Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(if (isCompact) 1f else 0.92f)
                        .fillMaxHeight(if (isCompact) 0.92f else 0.88f)
                        .clip(
                            if (isCompact) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                            else RoundedCornerShape(16.dp)
                        ),
                    color = cc.bg,
                    border = BorderStroke(1.dp, cc.border),
                    shadowElevation = 24.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (isCompact) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 10.dp)
                                    .size(36.dp, 4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(cc.border.copy(alpha = 0.8f))
                                    .align(Alignment.CenterHorizontally)
                            )
                        }

                        // Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isCompact) 16.dp else 24.dp, vertical = if (isCompact) 12.dp else 18.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(cc.accent.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = cc.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Problem Decomposition Matrix",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = cc.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Orthogonal Divergent Axes before Round 1",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = cc.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = cc.textMuted
                                )
                            }
                        }

                        HorizontalDivider(color = cc.border.copy(alpha = 0.5f))

                        // Topic Context Banner
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isCompact) 16.dp else 24.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "DEBATE TOPIC",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = cc.textMuted
                            )
                            Text(
                                text = decomposition.topic,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = cc.textPrimary
                            )

                            // Preset Filter Chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PresetChip(
                                    label = "Select All Technical (A)",
                                    onClick = onSelectAllPerspectiveA,
                                    cc = cc
                                )
                                PresetChip(
                                    label = "Select All Strategic (B)",
                                    onClick = onSelectAllPerspectiveB,
                                    cc = cc
                                )
                            }
                        }

                        // Perspective Selector Tabs
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isCompact) 16.dp else 24.dp)
                        ) {
                            PerspectiveTabButton(
                                title = decomposition.perspectiveA.name,
                                count = decomposition.perspectiveA.axes.count { it.id in selectedAxisIds },
                                total = decomposition.perspectiveA.axes.size,
                                isSelected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            PerspectiveTabButton(
                                title = decomposition.perspectiveB.name,
                                count = decomposition.perspectiveB.axes.count { it.id in selectedAxisIds },
                                total = decomposition.perspectiveB.axes.size,
                                isSelected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Lens Description Banner
                        Surface(
                            color = cc.panel.copy(alpha = 0.6f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = if (isCompact) 16.dp else 24.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = activePerspective.lensDescription,
                                style = MaterialTheme.typography.bodySmall,
                                color = cc.textMuted,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        // Axes List
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = if (isCompact) 16.dp else 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(activePerspective.axes, key = { it.id }) { axis ->
                                AxisCard(
                                    axis = axis,
                                    isSelected = axis.id in selectedAxisIds,
                                    onToggle = { onToggleAxis(axis.id) }
                                )
                            }
                        }

                        HorizontalDivider(color = cc.border.copy(alpha = 0.5f))

                        // Bottom Action Bar: Stacked on mobile, side-by-side on desktop
                        if (isCompact) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "$totalSelected axes selected for agenda",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = if (totalSelected > 0) cc.textPrimary else cc.textMuted
                                )
                                Button(
                                    onClick = onApplyToAgenda,
                                    enabled = totalSelected > 0,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = cc.accent,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Adopt as Debate Agenda ($totalSelected)", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = onDismiss,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cc.textMuted),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                ) {
                                    Text("Cancel")
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$totalSelected axes selected for agenda",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (totalSelected > 0) cc.textPrimary else cc.textMuted
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = onDismiss,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = cc.textMuted)
                                    ) {
                                        Text("Cancel")
                                    }

                                    Button(
                                        onClick = onApplyToAgenda,
                                        enabled = totalSelected > 0,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = cc.accent,
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Adopt as Debate Agenda ($totalSelected)")
                                    }
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
private fun PresetChip(
    label: String,
    onClick: () -> Unit,
    cc: com.dialex.theme.CcPalette,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = cc.panelAlt,
        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
        modifier = Modifier.height(28.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = cc.textMuted
            )
        }
    }
}

@Composable
private fun PerspectiveTabButton(
    title: String,
    count: Int,
    total: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cc = LocalCcColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) cc.accent else cc.border.copy(alpha = 0.5f)
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) cc.accent else cc.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) cc.accent else cc.panel,
                contentColor = if (isSelected) Color.White else cc.textMuted
            ) {
                Text(
                    text = "$count/$total",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AxisCard(
    axis: ProblemAxis,
    isSelected: Boolean,
    onToggle: () -> Unit,
) {
    val cc = LocalCcColors.current

    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) cc.panelAlt else cc.panel.copy(alpha = 0.4f),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) cc.accent else cc.border.copy(alpha = 0.4f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Checkbox + Title + Relevance Badge
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) cc.accent else Color.Transparent)
                            .border(
                                width = 1.5.dp,
                                color = if (isSelected) cc.accent else cc.border,
                                shape = RoundedCornerShape(4.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Text(
                        text = axis.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = cc.textPrimary
                    )
                }

                // Relevance Score Badge
                val relevancePct = (axis.weight * 100).toInt()
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cc.panel,
                    border = BorderStroke(0.5.dp, cc.border)
                ) {
                    Text(
                        text = "$relevancePct% relevant",
                        style = MaterialTheme.typography.labelSmall,
                        color = cc.textMuted,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            // Thesis & Antithesis Tension Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(cc.bg.copy(alpha = 0.6f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Thesis:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF10B981) // emerald
                    )
                    Text(
                        text = axis.thesis,
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textPrimary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Antithesis:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF59E0B) // amber
                    )
                    Text(
                        text = axis.antithesis,
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textPrimary
                    )
                }
            }

            // Key Questions
            if (axis.keyQuestions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Probing Questions:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = cc.textMuted
                    )
                    axis.keyQuestions.forEach { q ->
                        Text(
                            text = "• $q",
                            style = MaterialTheme.typography.bodySmall,
                            color = cc.textMuted.copy(alpha = 0.9f),
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
