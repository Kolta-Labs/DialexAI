package com.dialex.presentation.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp)),
            color = cc.bg,
            border = BorderStroke(1.dp, cc.border),
            shadowElevation = 24.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                                color = cc.textPrimary
                            )
                            Text(
                                text = "Orthogonal Divergent Axes before Round 1",
                                style = MaterialTheme.typography.bodySmall,
                                color = cc.textMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = cc.textMuted
                        )
                    }
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.5f))

                // Topic Pill & Quick Preset Chips
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        color = cc.panel,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "TOPIC:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = cc.accent
                            )
                            Text(
                                text = decomposition.topic,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cc.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

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
                        .padding(horizontal = 24.dp)
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
                        .padding(horizontal = 24.dp, vertical = 8.dp),
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
                        .padding(horizontal = 24.dp),
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

                // Bottom Action Bar
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

@Composable
private fun PresetChip(
    label: String,
    onClick: () -> Unit,
    cc: com.dialex.theme.CcPalette
) {
    Surface(
        onClick = onClick,
        color = cc.panel,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = cc.textPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun PerspectiveTabButton(
    title: String,
    count: Int,
    total: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) cc.accent.copy(alpha = 0.15f) else cc.panel,
        border = BorderStroke(1.dp, if (isSelected) cc.accent else cc.border.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) cc.accent else cc.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = CircleShape,
                color = if (isSelected) cc.accent else cc.border.copy(alpha = 0.4f)
            ) {
                Text(
                    text = "$count/$total",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) Color.White else cc.textMuted,
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
    onToggle: () -> Unit
) {
    val cc = LocalCcColors.current

    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) cc.panel else cc.panel.copy(alpha = 0.5f),
        border = BorderStroke(
            1.dp,
            if (isSelected) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.3f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) cc.accent else Color.Transparent)
                            .then(
                                if (!isSelected) Modifier.background(Color.Transparent)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.5.dp, cc.border),
                                color = Color.Transparent,
                                modifier = Modifier.fillMaxSize()
                            ) {}
                        }
                    }

                    Text(
                        text = axis.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = cc.textPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = cc.border.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = "Weight ${(axis.weight * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = cc.textMuted,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
