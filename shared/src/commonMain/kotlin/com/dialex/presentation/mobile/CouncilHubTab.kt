package com.dialex.presentation.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.theme.LocalCcColors

@Composable
fun CouncilHubTab(
    discussions: List<Discussion>,
    onSelectDiscussion: (String) -> Unit,
    onNewDilemma: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val activeDiscussions = discussions.filter { it.status == DiscussionStatus.RUNNING }
    val completedDiscussions = discussions.filter { it.status == DiscussionStatus.DONE }
    val drafts = discussions.filter { it.status == DiscussionStatus.DRAFT || it.status == DiscussionStatus.PAUSED }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        item {
            // Hero Status Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(cc.panel)
                    .border(1.dp, cc.border, RoundedCornerShape(14.dp))
                    .padding(18.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pocket Council",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = cc.textPrimary
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(cc.accent.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${discussions.size} Councils",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = cc.accent
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Your synthetic advisory board is active. Deliberate on high-stakes trade-offs anytime, anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textMuted,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        if (activeDiscussions.isNotEmpty()) {
            item {
                Text(
                    text = "Active Deliberations",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = cc.accent
                )
            }
            items(activeDiscussions) { disc ->
                DiscussionRowCard(
                    discussion = disc,
                    onClick = { onSelectDiscussion(disc.id) }
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Councils",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = cc.textPrimary
                )
                TextButton(onClick = onNewDilemma) {
                    Text("+ New Dilemma", color = cc.accent, fontSize = 12.sp)
                }
            }
        }

        if (discussions.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "No councils convened yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cc.textMuted
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onNewDilemma,
                            colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Launch First Dilemma", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            items(discussions) { disc ->
                DiscussionRowCard(
                    discussion = disc,
                    onClick = { onSelectDiscussion(disc.id) }
                )
            }
        }
    }
}

@Composable
private fun DiscussionRowCard(
    discussion: Discussion,
    onClick: () -> Unit
) {
    val cc = LocalCcColors.current
    val isRunning = discussion.status == DiscussionStatus.RUNNING

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cc.panel)
            .border(
                width = 1.dp,
                color = if (isRunning) cc.accent.copy(alpha = 0.5f) else cc.border,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (discussion.status) {
                                DiscussionStatus.RUNNING -> Color(0xFF10B981)
                                DiscussionStatus.PAUSED -> Color(0xFFF59E0B)
                                else -> cc.panelAlt
                            }
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = discussion.status.name,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${discussion.transcript.size} turns",
                    fontSize = 11.sp,
                    color = cc.textMuted
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = discussion.name.ifBlank { "Untitled Dilemma" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = cc.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (discussion.config.topic.isNotBlank()) {
                Text(
                    text = discussion.config.topic,
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = cc.textMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}
