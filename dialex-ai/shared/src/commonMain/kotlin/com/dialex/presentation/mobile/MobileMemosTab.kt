package com.dialex.presentation.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.export.exportFileName
import com.dialex.export.toMarkdown
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.theme.LocalCcColors

@Composable
fun MobileMemosTab(
    discussions: List<Discussion>,
    onOpenDiscussion: (String) -> Unit,
    onExportMarkdown: (markdown: String, fileName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val completedWithDeliverables = discussions.filter {
        it.status == DiscussionStatus.DONE ||
        it.deliverable != null ||
        it.summary != null ||
        it.artifacts.isNotEmpty()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        item {
            Text(
                text = "Executive Memos",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = cc.textPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Synthesized consensus decisions, SWOT analyses, and team action plans.",
                style = MaterialTheme.typography.bodySmall,
                color = cc.textMuted
            )
        }

        if (completedWithDeliverables.isEmpty()) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = cc.textMuted,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "No completed memos yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cc.textMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Completed councils automatically generate executive deliverables here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cc.textMuted
                        )
                    }
                }
            }
        } else {
            items(completedWithDeliverables) { disc ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cc.panel)
                        .border(1.dp, cc.border, RoundedCornerShape(12.dp))
                        .clickable { onOpenDiscussion(disc.id) }
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = disc.name.ifBlank { "Untitled Deliberation" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = cc.textPrimary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = {
                                    val md = disc.toMarkdown()
                                    val fn = disc.exportFileName()
                                    onExportMarkdown(md, fn)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = "Share Memo",
                                    tint = cc.accent,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        val summaryText = disc.deliverable
                            ?: disc.summary
                            ?: disc.artifacts.firstOrNull()?.content
                            ?: disc.transcript.lastOrNull()?.content

                        if (!summaryText.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = summaryText,
                                style = MaterialTheme.typography.bodySmall,
                                color = cc.textMuted,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 18.sp
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${disc.transcript.size} turns • ${disc.config.agents.size} agents",
                                fontSize = 11.sp,
                                color = cc.textMuted
                            )
                            Text(
                                text = "View Memo →",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = cc.accent
                            )
                        }
                    }
                }
            }
        }
    }
}
