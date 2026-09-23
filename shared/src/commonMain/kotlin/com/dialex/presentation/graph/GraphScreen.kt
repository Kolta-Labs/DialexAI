package com.dialex.presentation.graph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.GraphNodeType
import com.dialex.domain.model.KnowledgeEdge
import com.dialex.domain.model.KnowledgeNode
import com.dialex.theme.LocalAppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(
    state: GraphState,
    onIntent: (GraphIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalAppColors.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = cc.bg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Hub,
                            contentDescription = null,
                            tint = cc.accent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Knowledge Graph",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = cc.textPrimary
                        )
                        Spacer(Modifier.width(12.dp))
                        Surface(
                            shape = CircleShape,
                            color = cc.accent.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.3f))
                        ) {
                            Text(
                                "${state.nodes.size} nodes",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = cc.accent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = cc.textPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { onIntent(GraphIntent.RefreshGraph) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = cc.textPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cc.panel)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Controls Rail: Search & Activation Decay Weight Threshold
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = cc.panelAlt,
                border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Search bar
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { onIntent(GraphIntent.UpdateSearchQuery(it)) },
                        placeholder = { Text("Search nodes (FTS5)...", fontSize = 13.sp, color = cc.textMuted) },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp)) },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(onClick = { onIntent(GraphIntent.UpdateSearchQuery("")) }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Clear", tint = cc.textMuted, modifier = Modifier.size(14.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = cc.panel,
                            unfocusedContainerColor = cc.panel,
                            focusedBorderColor = cc.accent,
                            unfocusedBorderColor = cc.border.copy(alpha = 0.5f),
                            focusedTextColor = cc.textPrimary,
                            unfocusedTextColor = cc.textPrimary
                        ),
                        modifier = Modifier.weight(1f).height(42.dp)
                    )

                    // Decay Threshold Slider
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Min Weight: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = cc.textMuted
                        )
                        Text(
                            "${(state.minWeightFilter * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = cc.accent
                        )
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = state.minWeightFilter,
                            onValueChange = { onIntent(GraphIntent.UpdateWeightFilter(it)) },
                            valueRange = 0.0f..1.0f,
                            steps = 19,
                            modifier = Modifier.width(140.dp)
                        )
                    }
                }
            }

            // Main Content Area: Split View (Nodes List + Inspector)
            Row(modifier = Modifier.fillMaxSize()) {
                // Nodes List
                val displayedNodes = if (state.searchQuery.isNotBlank()) state.searchResults else state.nodes

                if (state.isLoading) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = cc.accent, modifier = Modifier.size(32.dp))
                    }
                } else if (displayedNodes.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Hub, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                if (state.searchQuery.isNotBlank()) "No nodes match '${state.searchQuery}'"
                                else "No active nodes above ${(state.minWeightFilter * 100).toInt()}% weight",
                                style = MaterialTheme.typography.bodyMedium,
                                color = cc.textMuted
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Deliberation rounds automatically compound concepts and tensions into this graph.",
                                style = MaterialTheme.typography.bodySmall,
                                color = cc.textMuted.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(displayedNodes, key = { it.id }) { node ->
                            GraphNodeCard(
                                node = node,
                                isSelected = state.selectedNode?.id == node.id,
                                onClick = { onIntent(GraphIntent.SelectNode(node)) },
                                cc = cc
                            )
                        }
                    }
                }

                // Inspector Sidebar (Detail Panel)
                AnimatedVisibility(
                    visible = state.selectedNode != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val node = state.selectedNode
                    if (node != null) {
                        Surface(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight(),
                            color = cc.panel,
                            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.35f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    NodeTypeBadge(type = node.type, cc = cc)
                                    IconButton(
                                        onClick = { onIntent(GraphIntent.SelectNode(null)) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                    }
                                }

                                Spacer(Modifier.height(12.dp))
                                Text(
                                    node.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = cc.textPrimary
                                )

                                Spacer(Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        "Weight: ${(node.currentWeight * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = getWeightColor(node.currentWeight)
                                    )
                                    Text(
                                        "Half-life: ${node.decayHalfLifeSecs / 86400}d",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cc.textMuted
                                    )
                                }

                                HorizontalDivider(color = cc.border.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 12.dp))

                                Text(
                                    "CONTENT",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                    color = cc.textMuted
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    node.content,
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                                    color = cc.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )

                                // Connected Edges
                                val connectedEdges = state.edges.filter { it.sourceId == node.id || it.targetId == node.id }
                                if (connectedEdges.isNotEmpty()) {
                                    HorizontalDivider(color = cc.border.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 10.dp))
                                    Text(
                                        "CONNECTED RELATIONS (${connectedEdges.size})",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                        color = cc.textMuted
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    connectedEdges.take(4).forEach { edge ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                edge.relation.name,
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = cc.accent
                                            )
                                            Text(
                                                "Str: ${(edge.strength * 100).toInt()}%",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = cc.textMuted
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.height(16.dp))
                                OutlinedButton(
                                    onClick = { onIntent(GraphIntent.DeleteNode(node.id)) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Delete Node", style = MaterialTheme.typography.labelMedium)
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
private fun GraphNodeCard(
    node: KnowledgeNode,
    isSelected: Boolean,
    onClick: () -> Unit,
    cc: com.dialex.theme.CcPalette
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .animateContentSize(),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) cc.panelAlt else cc.panel,
        border = BorderStroke(
            if (isSelected) 1.5.dp else 0.5.dp,
            if (isSelected) cc.accent else cc.border.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NodeTypeBadge(type = node.type, cc = cc)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        node.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = cc.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    node.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(12.dp))
            // Activation Energy Badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = getWeightColor(node.currentWeight).copy(alpha = 0.12f),
                border = BorderStroke(1.dp, getWeightColor(node.currentWeight).copy(alpha = 0.35f))
            ) {
                Text(
                    "${(node.currentWeight * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = getWeightColor(node.currentWeight),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun NodeTypeBadge(type: GraphNodeType, cc: com.dialex.theme.CcPalette) {
    val (labelColor, labelBg) = when (type) {
        GraphNodeType.CONCEPT -> Pair(Color(0xFF3B82F6), Color(0xFF3B82F6).copy(alpha = 0.15f))
        GraphNodeType.TENSION -> Pair(Color(0xFFEF4444), Color(0xFFEF4444).copy(alpha = 0.15f))
        GraphNodeType.CONSENSUS -> Pair(Color(0xFF10B981), Color(0xFF10B981).copy(alpha = 0.15f))
        GraphNodeType.ARGUMENT -> Pair(Color(0xFFF59E0B), Color(0xFFF59E0B).copy(alpha = 0.15f))
        GraphNodeType.DELIVERABLE -> Pair(Color(0xFF8B5CF6), Color(0xFF8B5CF6).copy(alpha = 0.15f))
        GraphNodeType.SOURCE -> Pair(Color(0xFF6B7280), Color(0xFF6B7280).copy(alpha = 0.15f))
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = labelBg
    ) {
        Text(
            type.name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            ),
            color = labelColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

private fun getWeightColor(weight: Double): Color {
    return when {
        weight >= 0.70 -> Color(0xFF10B981) // Green (High activation)
        weight >= 0.30 -> Color(0xFFF59E0B) // Amber (Decaying)
        else -> Color(0xFFEF4444)           // Red / Dormant
    }
}
