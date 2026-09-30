package com.dialex.presentation.mobile.arena

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
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
import com.dialex.model.DebateMessage
import com.dialex.theme.LocalCcColors
import com.dialex.theme.accentColor
import kotlinx.collections.immutable.ImmutableList

/**
 * Horizontal swipeable turn deck for side-by-side agent viewpoint comparison.
 */
@Composable
fun PerspectiveCarousel(
    turns: ImmutableList<DebateMessage>,
    selectedIndex: Int,
    onSelectTurn: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current

    if (turns.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(cc.panel)
                .border(1.dp, cc.border, RoundedCornerShape(14.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Council is preparing opening arguments...",
                style = MaterialTheme.typography.bodyMedium,
                color = cc.textMuted
            )
        }
        return
    }

    val safeIndex = selectedIndex.coerceIn(0, turns.lastIndex)
    val currentTurn = turns[safeIndex]
    val agentColor = com.dialex.theme.memberColorFor(safeIndex, cc)
    val agentDisplayName = currentTurn.agentId.name.lowercase().replaceFirstChar { it.uppercase() }

    Column(modifier = modifier.fillMaxWidth()) {
        // Turn Selector Pill Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { if (safeIndex > 0) onSelectTurn(safeIndex - 1) },
                enabled = safeIndex > 0,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = "Previous Argument",
                    tint = if (safeIndex > 0) cc.textPrimary else cc.border
                )
            }

            // Dot Indicators
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                turns.forEachIndexed { i, turn ->
                    Box(
                        modifier = Modifier
                            .size(if (i == safeIndex) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (i == safeIndex) com.dialex.theme.memberColorFor(i, cc) else cc.border
                            )
                            .clickable { onSelectTurn(i) }
                    )
                }
            }

            IconButton(
                onClick = { if (safeIndex < turns.lastIndex) onSelectTurn(safeIndex + 1) },
                enabled = safeIndex < turns.lastIndex,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Next Argument",
                    tint = if (safeIndex < turns.lastIndex) cc.textPrimary else cc.border
                )
            }
        }

        // Active Turn Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(cc.panel)
                .border(1.5.dp, agentColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(agentColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = agentDisplayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = cc.textPrimary
                        )
                    }

                    // Provider & Style Tag Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(agentColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = currentTurn.agentId.name,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = agentColor
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = currentTurn.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textPrimary,
                        lineHeight = 20.sp
                    )
                }
            }
        }
    }
}
