package com.dialex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.AppState
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Project
import com.dialex.theme.LocalCcColors

/**
 * Left rail: a small brand header, then projects, each expandable to its discussions.
 * Selecting a project (without picking a discussion) hands the "new discussion" CTA to the
 * right pane. Mirrors claude.ai/code's project + session list.
 */
@Composable
fun Sidebar(
    state: AppState,
    selectedProjectId: String?,
    selectedDiscussionId: String?,
    onSelectProject: (String) -> Unit,
    onSelectDiscussion: (String) -> Unit,
    onAddProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onDeleteDiscussion: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    /** False hides the CLI status row entirely (Android — no CLI support at all). */
    supportsCli: Boolean = false,
    /** Null while the check is still running — shown as "Checking…" rather than nothing. */
    cliStatus: Map<String, Boolean>? = null,
    cliLogins: Map<String, Boolean>? = null,
    onRecheckCli: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cc = LocalCcColors.current
    var expanded by remember(state.projects) { mutableStateOf(state.projects.map { it.id }.toSet()) }
    var addProjectDialogOpen by remember { mutableStateOf(false) }
    var projectPendingDelete by remember { mutableStateOf<Project?>(null) }
    var discussionPendingDelete by remember { mutableStateOf<Discussion?>(null) }

    Column(modifier.background(cc.panel).width(300.dp).fillMaxHeight()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(cc.textMuted))
            Spacer(Modifier.width(8.dp))
            Text("Dialex", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cc.textPrimary)
        }
        Text(
            "PROJECTS",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(12.dp, 12.dp, 12.dp, 4.dp),
        )
        GradientButton(
            text = "+ New Project",
            onClick = { addProjectDialogOpen = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            height = 34.dp
        )

        if (state.projects.isEmpty()) {
            Text(
                "No projects yet. Create one above.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }

        LazyColumn(Modifier.weight(1f)) {
            items(state.projects) { project ->
                val isExpanded = project.id in expanded
                val discussions = state.discussions.filter { it.projectId == project.id }
                val interactionSource = remember { MutableInteractionSource() }
                val hovered by interactionSource.collectIsHoveredAsState()
                val selected = project.id == selectedProjectId && selectedDiscussionId == null
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) cc.panelAlt else if (hovered) cc.panelAlt.copy(alpha = 0.5f) else cc.panel)
                        .hoverable(interactionSource)
                        .clickable {
                            expanded = if (isExpanded) expanded - project.id else expanded + project.id
                            onSelectProject(project.id)
                        }
                        .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isExpanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = cc.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(project.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    if (discussions.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = cc.panelAlt,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f))
                        ) {
                            Text(
                                "${discussions.size}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = cc.textMuted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { projectPendingDelete = project }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Delete project", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                    }
                }
                if (isExpanded) {
                    discussions.forEach { d ->
                        DiscussionRow(
                            discussion = d,
                            selected = d.id == selectedDiscussionId,
                            onClick = { onSelectDiscussion(d.id) },
                            onDelete = { discussionPendingDelete = d },
                        )
                    }
                }
            }
        }
        CliStatusFooter(supportsCli, cliStatus, cliLogins, onRecheckCli)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenSettings).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("Settings", style = MaterialTheme.typography.labelSmall, color = cc.textMuted)
        }
    }

    if (addProjectDialogOpen) {
        NameDialog(
            title = "New Project",
            placeholder = "Project name",
            onDismiss = { addProjectDialogOpen = false },
            onConfirm = { name -> onAddProject(name); addProjectDialogOpen = false },
        )
    }
    projectPendingDelete?.let { project ->
        ConfirmDialog(
            title = "Delete project",
            message = "Delete \"${project.name}\" and all its discussions? This can't be undone.",
            onDismiss = { projectPendingDelete = null },
            onConfirm = { onDeleteProject(project.id) },
        )
    }
    discussionPendingDelete?.let { discussion ->
        ConfirmDialog(
            title = "Delete discussion",
            message = "Delete \"${discussion.name}\"? This can't be undone.",
            onDismiss = { discussionPendingDelete = null },
            onConfirm = { onDeleteDiscussion(discussion.id) },
        )
    }
}

@Composable
private fun DiscussionRow(discussion: Discussion, selected: Boolean, onClick: () -> Unit, onDelete: () -> Unit) {
    val cc = LocalCcColors.current
    val (statusDot, statusLabel) = when (discussion.status) {
        DiscussionStatus.DRAFT -> cc.textMuted to "Draft"
        DiscussionStatus.RUNNING -> Color(0xFF4CAF50) to "Running"
        DiscussionStatus.PAUSED -> cc.agentRight to "Paused"
        DiscussionStatus.DONE, DiscussionStatus.COMPLETED -> cc.agentLeft to "Done"
        DiscussionStatus.COMPLETED_WITH_WARNING -> Color(0xFFFFB300) to "Partial"
        DiscussionStatus.ERROR, DiscussionStatus.FAILED -> MaterialTheme.colorScheme.error to "Error"
    }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) cc.panelAlt else if (hovered) cc.panelAlt.copy(alpha = 0.5f) else cc.panel)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(start = 26.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(statusDot))
        Text(discussion.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
        // Only shown while it means something beyond the dot's color — Draft is the common
        // resting state for a brand-new discussion and doesn't need calling out every time.
        if (discussion.status != DiscussionStatus.DRAFT) {
            Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusDot)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Delete discussion", tint = cc.textMuted, modifier = Modifier.size(13.dp))
        }
    }
}
