package com.dialex.presentation.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TipsAndUpdates
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Project
import com.dialex.theme.LocalCcColors

@Composable
fun CouncilHubTab(
    discussions: List<Discussion>,
    onSelectDiscussion: (String) -> Unit,
    onNewDilemma: () -> Unit,
    projects: List<Project> = emptyList(),
    onOpenBenchmarkArena: () -> Unit = {},
    onOpenKnowledgeGraph: (String) -> Unit = {},
    onOpenSocraticInterview: () -> Unit = {},
    onCreateProject: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val activeDiscussions = discussions.filter { it.status == DiscussionStatus.RUNNING }

    // Track expanded project accordions
    val expandedProjects = remember { mutableStateMapOf<String, Boolean>() }
    var isNewProjectDialogOpen by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    if (isNewProjectDialogOpen) {
        AlertDialog(
            onDismissRequest = {
                isNewProjectDialogOpen = false
                newProjectName = ""
            },
            title = {
                Text("New Project Workspace", fontWeight = FontWeight.Bold, color = cc.textPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Organize related dilemmas, ADRs, and knowledge graphs into an isolated project workspace.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textMuted
                    )
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        placeholder = { Text("Project Name (e.g. Distributed Core)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newProjectName.trim()
                        if (trimmed.isNotEmpty()) {
                            onCreateProject(trimmed)
                            isNewProjectDialogOpen = false
                            newProjectName = ""
                        }
                    },
                    enabled = newProjectName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black)
                ) {
                    Text("Create Workspace", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    isNewProjectDialogOpen = false
                    newProjectName = ""
                }) {
                    Text("Cancel", color = cc.textMuted)
                }
            },
            containerColor = cc.panel,
            shape = RoundedCornerShape(14.dp)
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        // 1. Hero Status Card
        item {
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
                        text = "Synthetic advisory board for high-stakes trade-offs. Challenge axioms, benchmark decisions, and explore knowledge graphs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textMuted,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        // 2. Epistemic Quick Launchers: 1-on-1 Socratic & Null Hypothesis Arena
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Socratic Interview Launcher Card
                EpistemicActionCard(
                    title = "Socratic 1-on-1",
                    subtitle = "Adversarial Invariant Probe",
                    icon = Icons.Outlined.Psychology,
                    accentColor = Color(0xFFF59E0B),
                    onClick = onOpenSocraticInterview,
                    modifier = Modifier.weight(1f)
                )

                // Null Hypothesis Arena Launcher Card
                EpistemicActionCard(
                    title = "Benchmark Arena",
                    subtitle = "Null Hypothesis Testing",
                    icon = Icons.Outlined.Shield,
                    accentColor = Color(0xFF38BDF8),
                    onClick = onOpenBenchmarkArena,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 3. Active Deliberations (if running)
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

        // 4. Project Workspace Section Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Project Workspaces",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = cc.textPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { isNewProjectDialogOpen = true }) {
                        Text("+ New Project", color = cc.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    TextButton(onClick = onNewDilemma) {
                        Text("+ Dilemma", color = cc.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Group discussions by Project
        val discussionsByProject = discussions.groupBy { it.projectId }
        val displayProjects = if (projects.isNotEmpty()) {
            projects
        } else {
            // Synthesize from discussions if projects list empty
            listOf(Project(id = "default", name = "Main Workspace", sharedContext = "Default Project"))
        }

        items(displayProjects) { project ->
            val projectDiscussions = discussionsByProject[project.id].orEmpty()
            val isExpanded = expandedProjects[project.id] ?: true

            ProjectAccordionCard(
                project = project,
                discussionCount = projectDiscussions.size,
                isExpanded = isExpanded,
                onToggleExpand = { expandedProjects[project.id] = !isExpanded },
                onOpenGraph = { onOpenKnowledgeGraph(project.id) },
                discussions = projectDiscussions,
                onSelectDiscussion = onSelectDiscussion,
                onNewDilemmaInProject = onNewDilemma
            )
        }

        // If there are discussions whose projectId doesn't match any project
        val knownProjectIds = displayProjects.map { it.id }.toSet()
        val ungroupedDiscussions = discussions.filter { it.projectId !in knownProjectIds }
        if (ungroupedDiscussions.isNotEmpty()) {
            item {
                val isExpanded = expandedProjects["ungrouped"] ?: true
                ProjectAccordionCard(
                    project = Project(id = "ungrouped", name = "Ungrouped Dilemmas", sharedContext = "Discussions without a project folder"),
                    discussionCount = ungroupedDiscussions.size,
                    isExpanded = isExpanded,
                    onToggleExpand = { expandedProjects["ungrouped"] = !isExpanded },
                    onOpenGraph = {
                        val firstDiscProj = ungroupedDiscussions.firstOrNull()?.projectId
                        if (!firstDiscProj.isNullOrBlank()) onOpenKnowledgeGraph(firstDiscProj)
                    },
                    discussions = ungroupedDiscussions,
                    onSelectDiscussion = onSelectDiscussion,
                    onNewDilemmaInProject = onNewDilemma
                )
            }
        }

        // Zero state
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
        }
    }
}

@Composable
private fun EpistemicActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = cc.panel,
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
        modifier = modifier.heightIn(min = 68.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                    color = cc.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = cc.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProjectAccordionCard(
    project: Project,
    discussionCount: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onOpenGraph: () -> Unit,
    discussions: List<Discussion>,
    onSelectDiscussion: (String) -> Unit,
    onNewDilemmaInProject: () -> Unit
) {
    val cc = LocalCcColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cc.panel)
            .border(1.dp, cc.border, RoundedCornerShape(12.dp))
    ) {
        // Project Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleExpand() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = cc.accent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = cc.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(cc.panelAlt)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "$discussionCount",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cc.textMuted
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // Knowledge Graph Exploration Button
                IconButton(
                    onClick = onOpenGraph,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccountTree,
                        contentDescription = "Project Knowledge Graph",
                        tint = cc.accent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = cc.textMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Expanded Discussion List
        if (isExpanded) {
            HorizontalDivider(color = cc.border.copy(alpha = 0.5f), thickness = 0.5.dp)

            if (discussions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No dilemmas in this workspace",
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textMuted
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    discussions.forEach { disc ->
                        DiscussionRowCard(
                            discussion = disc,
                            onClick = { onSelectDiscussion(disc.id) }
                        )
                    }
                }
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
            .clip(RoundedCornerShape(10.dp))
            .background(cc.bg)
            .border(
                width = 1.dp,
                color = if (isRunning) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(12.dp),
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
