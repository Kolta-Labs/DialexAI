package com.dialex.presentation.mobile.memos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.export.exportFileName
import com.dialex.export.toMarkdown
import com.dialex.model.Discussion
import com.dialex.theme.LocalCcColors

@Composable
fun ExecutiveMemoSheet(
    discussion: Discussion,
    onDismiss: () -> Unit,
    onShareMarkdown: (markdown: String, fileName: String) -> Unit
) {
    val cc = LocalCcColors.current
    val summaryContent = discussion.deliverable
        ?: discussion.summary
        ?: discussion.artifacts.firstOrNull()?.content
        ?: "No synthesized deliverable available yet."

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(18.dp))
                .background(cc.panel)
                .border(1.5.dp, cc.accent, RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = cc.accent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Executive Memorandum",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = cc.textPrimary
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = cc.textMuted)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Topic & Decision Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(cc.panelAlt)
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "DELIBERATION TOPIC",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = cc.accent
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = discussion.name.ifBlank { discussion.config.topic },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = cc.textPrimary
                            )
                        }
                    }

                    // Deliverable / SWOT Content
                    Text(
                        text = "Executive Summary & Action Plan",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = cc.textPrimary
                    )

                    Text(
                        text = summaryContent,
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textPrimary,
                        lineHeight = 20.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Action Export Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val md = discussion.toMarkdown()
                            val fn = discussion.exportFileName()
                            onShareMarkdown(md, fn)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share to Slack / Notes / PDF", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
