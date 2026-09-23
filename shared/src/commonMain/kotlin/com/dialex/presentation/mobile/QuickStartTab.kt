package com.dialex.presentation.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.CouncilPreset
import com.dialex.theme.LocalCcColors

@Composable
fun QuickStartTab(
    onSelectPreset: (CouncilPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val presets = CouncilPreset.defaultPresets

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        item {
            Text(
                text = "1-Tap Council Presets",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = cc.textPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Convene specialized multi-agent advisory boards calibrated for high-stakes decisions.",
                style = MaterialTheme.typography.bodySmall,
                color = cc.textMuted
            )
        }

        items(presets) { preset ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cc.panel)
                    .border(1.dp, cc.border, RoundedCornerShape(14.dp))
                    .clickable { onSelectPreset(preset) }
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = preset.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = cc.textPrimary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(cc.panelAlt)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = preset.badgeLabel,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = cc.accent
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = preset.subtitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = cc.accent
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textMuted,
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(12.dp))
                    // Seats summary pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val seats = listOf(preset.primaryAgent.displayName) + preset.secondaryAgents.map { it.displayName }
                            Text(
                                text = seats.joinToString(" • "),
                                fontSize = 11.sp,
                                color = cc.textMuted
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = cc.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
