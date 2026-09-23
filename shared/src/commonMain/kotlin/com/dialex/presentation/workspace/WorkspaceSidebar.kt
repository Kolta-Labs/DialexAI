@file:Suppress("DEPRECATION")

package com.dialex.presentation.workspace

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import com.dialex.model.UserInterventionPolicy
import com.dialex.util.formatRelativeTime
import com.dialex.ui.CliStatusFooter
import com.dialex.ui.windowTitleBarDoubleClick
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Project
import com.dialex.presentation.settings.ProviderStatus
import com.dialex.theme.LocalAppColors
import com.dialex.ui.ConfirmDialog
import com.dialex.ui.DialexLogoView
import com.dialex.ui.NameDialog
import com.dialex.ui.ThemedTooltipBox

enum class ProjectSortOrder(val label: String) {
    LAST_ACTIVE("Most Recent Activity (Default)"),
    NAME_ASC("Alphabetical: A → Z"),
    NAME_DESC("Alphabetical: Z → A"),
    DATE_NEWEST("Date Created: Newest First"),
    DATE_OLDEST("Date Created: Oldest First");

    companion object {
        val LAST_UPDATED = LAST_ACTIVE
    }
}

private fun Discussion.lastActiveTimestamp(): Long {
    if (updatedAt > 0L) return updatedAt
    if (createdAt > 0L) return createdAt
    return transcript.lastOrNull()?.timestampMs ?: 0L
}

private fun Project.lastActiveTimestamp(discussions: List<Discussion>, projects: List<Project>): Long {
    val isUngrouped = name.equals("Ungrouped", ignoreCase = true)
    val projectDiscussions = if (isUngrouped) {
        discussions.filter { it.projectId == id || it.projectId.isNullOrBlank() || projects.none { p -> p.id == it.projectId } }
    } else {
        discussions.filter { it.projectId == id }
    }
    return projectDiscussions.maxOfOrNull { it.lastActiveTimestamp() } ?: 0L
}

/**
 * Modern Claude Desktop / Antigravity Aesthetic Navigation Sidebar.
 * Matches UI specifications:
 * - Projects header with Sort/Filter icon button & New Project icon button on the right.
 * - Expanding search bar at top alongside the pane collapse button.
 * - Clean project items: 📁 Project Name · [⋮] [+]
 * - Indented discussion items with rounded active pill, relative timestamps (6m, 2h, 1d), and hover action icons.
 * - Drag-and-drop discussions into and between projects.
 * - Bottom Dialex app branding & Settings icon button on the right.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSidebar(
    projects: List<Project>,
    discussions: List<Discussion>,
    selectedProjectId: String?,
    selectedDiscussionId: String?,
    onSelectProject: (String) -> Unit,
    onSelectDiscussion: (String) -> Unit,
    onNewDiscussion: (projectId: String?) -> Unit,
    onCopyDiscussionSettings: ((Discussion) -> Unit)? = null,
    onCreateProject: (String) -> Unit,
    onUpdateProject: ((Project) -> Unit)? = null,
    onDeleteProject: (String) -> Unit,
    onDeleteDiscussion: (String) -> Unit,
    onRenameDiscussion: ((discussionId: String, newName: String) -> Unit)? = null,
    onMoveDiscussionToProject: ((discussionId: String, targetProjectId: String) -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onOpenAiSetup: () -> Unit = {},
    onOpenPersonaBuilder: () -> Unit,
    onOpenKnowledgeGraph: ((projectId: String) -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
    onSendFeedback: (() -> Unit)? = null,
    connectionLabel: String,
    userName: String = "Guest",
    onSwitchConnection: (() -> Unit)? = null,
    onToggleSidebar: () -> Unit = {},
    providerStatuses: List<ProviderStatus> = emptyList(),
    cliStatus: Map<String, Boolean>? = null,
    cliLogins: Map<String, Boolean>? = null,
    onRecheckCli: (() -> Unit)? = null,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cc = LocalAppColors.current
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    var newProjectDialogOpen by remember { mutableStateOf(false) }
    var renamingProject by remember { mutableStateOf<Project?>(null) }
    var renamingDiscussion by remember { mutableStateOf<Discussion?>(null) }
    var editingProjectConfig by remember { mutableStateOf<Project?>(null) }
    var projectPendingDelete by remember { mutableStateOf<Project?>(null) }
    var discussionPendingDelete by remember { mutableStateOf<Discussion?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortOrder by remember { mutableStateOf(ProjectSortOrder.LAST_ACTIVE) }
    var groupBy by remember { mutableStateOf("Project") }
    var subtitleMode by remember { mutableStateOf("Worktree") }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var profileMenuOpen by remember { mutableStateOf(false) }

    // Drag-and-drop state for moving discussions between projects
    var draggingDiscussion by remember { mutableStateOf<Discussion?>(null) }
    var hoveredTargetProjectId by remember { mutableStateOf<String?>(null) }
    val projectBounds = remember { mutableStateMapOf<String, Rect>() }
    val collapsedProjects = remember { mutableStateMapOf<String, Boolean>() }
    val expandedAllProjects = remember { mutableStateMapOf<String, Boolean>() }

    // Dynamic live relative time ticker (updates timestamps periodically every 30s)
    var currentTimeMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(30_000)
            currentTimeMs = System.currentTimeMillis()
        }
    }

    // Seen discussion statuses tracking (clears glowing dots once opened unless running)
    val seenDiscussionStatuses = remember { mutableStateMapOf<String, DiscussionStatus>() }
    var hasInitializedSeenMap by remember { mutableStateOf(false) }

    LaunchedEffect(discussions) {
        if (!hasInitializedSeenMap && discussions.isNotEmpty()) {
            hasInitializedSeenMap = true
            discussions.forEach { disc ->
                if (disc.status != DiscussionStatus.RUNNING) {
                    seenDiscussionStatuses[disc.id] = disc.status
                }
            }
        }
    }

    LaunchedEffect(selectedDiscussionId, discussions) {
        if (selectedDiscussionId != null) {
            val currentDisc = discussions.firstOrNull { it.id == selectedDiscussionId }
            if (currentDisc != null) {
                seenDiscussionStatuses[currentDisc.id] = currentDisc.status
            }
        }
    }

    val sortedProjects = remember(projects, sortOrder, discussions) {
        val base = when (sortOrder) {
            ProjectSortOrder.LAST_ACTIVE -> projects.sortedByDescending { it.lastActiveTimestamp(discussions, projects) }
            ProjectSortOrder.NAME_ASC -> projects.sortedBy { it.name.lowercase() }
            ProjectSortOrder.NAME_DESC -> projects.sortedByDescending { it.name.lowercase() }
            ProjectSortOrder.DATE_NEWEST -> projects.reversed()
            ProjectSortOrder.DATE_OLDEST -> projects
        }
        val ungrouped = base.filter { proj ->
            proj.name.equals("Ungrouped", ignoreCase = true) &&
                discussions.any { it.projectId == proj.id || it.projectId.isNullOrBlank() || projects.none { p -> p.id == it.projectId } }
        }
        val others = base.filterNot { it.name.equals("Ungrouped", ignoreCase = true) }
        others + ungrouped
    }

    val sortedDiscussions = remember(discussions, sortOrder, searchQuery) {
        val filtered = if (searchQuery.isNotBlank()) {
            discussions.filter { it.name.contains(searchQuery, ignoreCase = true) }
        } else discussions

        when (sortOrder) {
            ProjectSortOrder.LAST_ACTIVE -> filtered.sortedByDescending { it.lastActiveTimestamp() }
            ProjectSortOrder.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            ProjectSortOrder.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            ProjectSortOrder.DATE_NEWEST -> filtered.sortedByDescending { if (it.createdAt > 0L) it.createdAt else if (it.updatedAt > 0L) it.updatedAt else 0L }
            ProjectSortOrder.DATE_OLDEST -> filtered.sortedBy { if (it.createdAt > 0L) it.createdAt else if (it.updatedAt > 0L) it.updatedAt else 0L }
        }
    }

    val sidebarBg = if (cc.isDark) Color(0xFF141418) else Color(0xFFF7F7F8)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(sidebarBg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // ── 1. Top Header: Search button & Sidebar toggle ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowTitleBarDoubleClick()
                    .padding(start = if (isCompact) 14.dp else 16.dp, end = 12.dp, top = if (isCompact) 8.dp else 5.dp, bottom = if (isCompact) 6.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (!isCompact) {
                    // Reserve spacing for native macOS traffic lights
                    Spacer(Modifier.width(68.dp))
                } else {
                    Text(
                        "Workspace",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        ),
                        color = cc.textPrimary
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Expanding Search toggle icon button
                    ThemedTooltipBox("Search discussions") {
                        IconButton(
                            onClick = { searchExpanded = !searchExpanded },
                            modifier = Modifier.size(if (isCompact) 40.dp else 28.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = "Search discussions",
                                tint = cc.textMuted,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }

                    if (!isCompact) {
                        // Collapse sidebar icon button (Desktop only)
                        ThemedTooltipBox("Collapse sidebar") {
                            IconButton(
                                onClick = onToggleSidebar,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ViewSidebar,
                                    contentDescription = "Collapse sidebar",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ── Expanding Search Bar ──────────────────────────────────────────
            AnimatedVisibility(
                visible = searchExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (cc.isDark) cc.panelAlt else Color(0xFFEDEDEE))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = cc.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary),
                        cursorBrush = SolidColor(cc.accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "Search discussions...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cc.textMuted.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = cc.textMuted, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }

            // ── 2. Top Navigation Items (Antigravity / Modern Capsule Style) ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // Item 1: Elevated White Pill for "+ New Conversation"
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (cc.isDark) Color(0xFF222228) else Color.White,
                    border = BorderStroke(1.dp, if (cc.isDark) Color(0xFF2E2E38) else Color(0xFFE5E7EB)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clickable { onNewDiscussion(selectedProjectId) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "New Conversation",
                            tint = cc.textPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "New Conversation",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = cc.textPrimary
                        )
                    }
                }

                // Item 2: Distinct AI Setup Capsule for "Setup with AI"
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cc.accent.copy(alpha = 0.09f),
                    border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.28f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clickable { onOpenAiSetup() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = "Setup Conversation with AI",
                            tint = cc.accent,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Setup with AI",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = cc.accent
                        )
                    }
                }

                // Item 3: Clean Flat Row for "AI Personas"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenPersonaBuilder() }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Psychology,
                        contentDescription = "AI Personas",
                        tint = cc.textPrimary.copy(alpha = 0.85f),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "AI Personas",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        color = cc.textPrimary.copy(alpha = 0.9f)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 3. Projects Header with Sort and New Project Icon Buttons ─────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Projects",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.5.sp,
                        letterSpacing = 0.2.sp
                    ),
                    color = if (cc.isDark) Color(0xFF9CA3AF).copy(alpha = 0.8f) else Color(0xFF6B7280).copy(alpha = 0.8f)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sort / Filter Icon Button
                    Box {
                        ThemedTooltipBox("Sort and group") {
                            IconButton(
                                onClick = { sortMenuOpen = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.FilterList,
                                    contentDescription = "Sort and filter options",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        // Streamlined Sort & Group dropdown with consistent panel background
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                            modifier = Modifier
                                .background(cc.panel)
                                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)), RoundedCornerShape(10.dp))
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            Text(
                                "Group By",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                                color = cc.textMuted,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            listOf("Project", "None").forEach { opt ->
                                DropdownMenuItem(
                                    modifier = Modifier.height(32.dp),
                                    text = { Text(opt, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = cc.textPrimary) },
                                    onClick = {
                                        groupBy = opt
                                        sortMenuOpen = false
                                    },
                                    trailingIcon = if (groupBy == opt) {
                                        { Icon(Icons.Default.Check, contentDescription = null, tint = cc.textPrimary, modifier = Modifier.size(14.dp)) }
                                    } else null,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }
                            HorizontalDivider(color = cc.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 2.dp))
                            Text(
                                "Sort Order",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                                color = cc.textMuted,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                            listOf(
                                ProjectSortOrder.LAST_ACTIVE to "Most Recent Activity (Default)",
                                ProjectSortOrder.NAME_ASC to "Alphabetical (A → Z)",
                                ProjectSortOrder.NAME_DESC to "Alphabetical (Z → A)",
                                ProjectSortOrder.DATE_NEWEST to "Date Created (Newest First)",
                                ProjectSortOrder.DATE_OLDEST to "Date Created (Oldest First)"
                            ).forEach { (order, label) ->
                                DropdownMenuItem(
                                    modifier = Modifier.height(32.dp),
                                    text = { Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = cc.textPrimary) },
                                    onClick = {
                                        sortOrder = order
                                        sortMenuOpen = false
                                    },
                                    trailingIcon = if (sortOrder == order) {
                                        { Icon(Icons.Default.Check, contentDescription = null, tint = cc.textPrimary, modifier = Modifier.size(14.dp)) }
                                    } else null,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }
                        }
                    }

                    // New Project Icon Button (Creates new project)
                    ThemedTooltipBox("Create New Project") {
                        IconButton(
                            onClick = { newProjectDialogOpen = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Outlined.CreateNewFolder,
                                contentDescription = "Create New Project",
                                tint = cc.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // ── 4. Aerated Projects and Discussions List ──────────────────────
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (groupBy == "None") 2.dp else 10.dp)
            ) {
                if (groupBy == "None") {
                    if (sortedDiscussions.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp, horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("No discussions yet", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = { onNewDiscussion(null) }) {
                                    Text("+ Start new debate", style = MaterialTheme.typography.labelMedium, color = cc.textPrimary)
                                }
                            }
                        }
                    } else {
                        itemsIndexed(sortedDiscussions, key = { _, d -> d.id }) { index, disc ->
                            SidebarDiscussionRow(
                                disc = disc,
                                index = index,
                                isSelected = disc.id == selectedDiscussionId,
                                isSeen = seenDiscussionStatuses[disc.id] == disc.status,
                                isCompact = isCompact,
                                cc = cc,
                                projects = projects,
                                onSelectDiscussion = onSelectDiscussion,
                                onMoveDiscussionToProject = onMoveDiscussionToProject,
                                onRenameClick = { d -> renamingDiscussion = d },
                                onDeleteClick = { d -> discussionPendingDelete = d },
                                onCopyDiscussionSettings = onCopyDiscussionSettings,
                                clipboard = clipboard,
                                haptic = haptic,
                                showProjectIndent = false,
                                currentTimeMs = currentTimeMs
                            )
                        }
                    }
                } else {
                    if (sortedProjects.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp, horizontal = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("No projects yet", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = { newProjectDialogOpen = true }) {
                                    Text("+ Create first project", style = MaterialTheme.typography.labelMedium, color = cc.textPrimary)
                                }
                            }
                        }
                    }

                    items(sortedProjects) { project ->
                    val isUngrouped = project.name.equals("Ungrouped", ignoreCase = true)
                    val projectDiscussions = if (isUngrouped) {
                        discussions.filter { it.projectId == project.id || it.projectId.isNullOrBlank() || projects.none { p -> p.id == it.projectId } }
                    } else {
                        discussions.filter { it.projectId == project.id }
                    }
                    val filteredDiscussions = if (searchQuery.isNotBlank()) {
                        projectDiscussions.filter { it.name.contains(searchQuery, ignoreCase = true) }
                    } else projectDiscussions

                    val visibleDiscussions = when (sortOrder) {
                        ProjectSortOrder.LAST_ACTIVE -> filteredDiscussions.sortedByDescending { it.lastActiveTimestamp() }
                        ProjectSortOrder.NAME_ASC -> filteredDiscussions.sortedBy { it.name.lowercase() }
                        ProjectSortOrder.NAME_DESC -> filteredDiscussions.sortedByDescending { it.name.lowercase() }
                        ProjectSortOrder.DATE_NEWEST -> filteredDiscussions.sortedByDescending { if (it.createdAt > 0L) it.createdAt else if (it.updatedAt > 0L) it.updatedAt else 0L }
                        ProjectSortOrder.DATE_OLDEST -> filteredDiscussions.sortedBy { if (it.createdAt > 0L) it.createdAt else if (it.updatedAt > 0L) it.updatedAt else 0L }
                    }

                    val isCollapsed = collapsedProjects[project.id] ?: false
                    val isExpanded = !isCollapsed
                    var projectMenuOpen by remember { mutableStateOf(false) }

                    // Mobile project actions bottom sheet
                    if (isCompact && projectMenuOpen) {
                        ModalBottomSheet(
                            onDismissRequest = { projectMenuOpen = false },
                            containerColor = cc.panel,
                            tonalElevation = 0.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .padding(bottom = 28.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    project.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                                    color = cc.textPrimary,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )

                                Surface(
                                    color = Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            projectMenuOpen = false
                                            clipboard.setText(AnnotatedString(project.name))
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Text("Copy Project Name", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cc.textPrimary)
                                    }
                                }

                                Surface(
                                    color = Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            projectMenuOpen = false
                                            renamingProject = project
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Outlined.Edit, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Text("Rename Project", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cc.textPrimary)
                                    }
                                }

                                Surface(
                                    color = Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 44.dp)
                                        .clickable {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            projectMenuOpen = false
                                            editingProjectConfig = project
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Outlined.Settings, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Text("Project Settings", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cc.textPrimary)
                                    }
                                }

                                if (onOpenKnowledgeGraph != null) {
                                    Surface(
                                        color = Color.Transparent,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 44.dp)
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                projectMenuOpen = false
                                                onOpenKnowledgeGraph.invoke(project.id)
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Icon(Icons.Outlined.Hub, contentDescription = null, tint = cc.accent, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(12.dp))
                                            Text("Knowledge Graph", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cc.textPrimary)
                                        }
                                    }
                                }

                                if (!isUngrouped) {
                                    HorizontalDivider(color = cc.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 4.dp))

                                    Surface(
                                        color = Color.Transparent,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                projectMenuOpen = false
                                                projectPendingDelete = project
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                            Spacer(Modifier.width(12.dp))
                                            Text("Delete Project", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Project Row: 📁 Project Name (actions shown on hover)
                        val isDropTarget = draggingDiscussion != null && hoveredTargetProjectId == project.id && draggingDiscussion?.projectId != project.id
                        val projectInteractionSource = remember { MutableInteractionSource() }
                        val isProjectHovered by projectInteractionSource.collectIsHoveredAsState()

                        // Smoothly animated drop target properties
                        val targetBgColor by animateColorAsState(
                            targetValue = when {
                                isDropTarget -> cc.accent.copy(alpha = 0.22f)
                                isProjectHovered -> if (cc.isDark) Color(0xFF1E1F24) else Color(0xFFEBECEE)
                                else -> Color.Transparent
                            },
                            animationSpec = tween(180)
                        )
                        val targetBorderColor by animateColorAsState(
                            targetValue = if (isDropTarget) cc.accent else Color.Transparent,
                            animationSpec = tween(180)
                        )
                        val targetBorderWidth by animateDpAsState(
                            targetValue = if (isDropTarget) 1.5.dp else 0.dp,
                            animationSpec = tween(180)
                        )
                        val targetScale by animateFloatAsState(
                            targetValue = if (isDropTarget) 1.025f else 1.0f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy)
                        )

                        // Auto-expand project when dragging discussion hovers over it
                        LaunchedEffect(isDropTarget) {
                            if (isDropTarget && isCollapsed) {
                                delay(380)
                                collapsedProjects[project.id] = false
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    scaleX = targetScale
                                    scaleY = targetScale
                                }
                                .clip(RoundedCornerShape(8.dp))
                                .background(targetBgColor)
                                .then(
                                    if (targetBorderWidth > 0.dp) Modifier.border(BorderStroke(targetBorderWidth, targetBorderColor), RoundedCornerShape(8.dp))
                                    else Modifier
                                )
                                .onGloballyPositioned { coords ->
                                    projectBounds[project.id] = coords.boundsInWindow()
                                }
                                .hoverable(projectInteractionSource)
                                .combinedClickable(
                                    onClick = {
                                        onSelectProject(project.id)
                                        collapsedProjects[project.id] = isExpanded
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        projectMenuOpen = true
                                    }
                                )
                                .heightIn(min = 34.dp)
                                .padding(horizontal = 8.dp, vertical = if (isCompact) 4.dp else 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                val projectItemColor = if (isDropTarget) cc.accent else (if (cc.isDark) Color(0xFF9CA3AF).copy(alpha = 0.8f) else Color(0xFF6B7280).copy(alpha = 0.8f))
                                AnimatedContent(
                                    targetState = isDropTarget,
                                    transitionSpec = { fadeIn(tween(140)) togetherWith fadeOut(tween(140)) }
                                ) { activeDrop ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            when {
                                                activeDrop -> Icons.AutoMirrored.Outlined.DriveFileMove
                                                isExpanded -> Icons.Filled.FolderOpen
                                                else -> Icons.Outlined.Folder
                                            },
                                            contentDescription = if (isExpanded) "Collapse ${project.name}" else "Expand ${project.name}",
                                            tint = projectItemColor,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            if (activeDrop) "Drop to move here" else project.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (activeDrop) FontWeight.SemiBold else FontWeight.Medium,
                                                fontSize = 14.sp
                                            ),
                                            color = projectItemColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (isCollapsed && !isDropTarget && visibleDiscussions.isNotEmpty()) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "(${visibleDiscussions.size})",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        color = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)
                                    )
                                }
                            }

                            if (isCompact || isProjectHovered || projectMenuOpen) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Project 3-dots Context Menu
                                    Box(
                                        modifier = Modifier
                                            .size(if (isCompact) 36.dp else 24.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(if (projectMenuOpen) cc.panelAlt else Color.Transparent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ThemedTooltipBox("Project options") {
                                            IconButton(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    projectMenuOpen = true
                                                },
                                                modifier = Modifier.size(if (isCompact) 36.dp else 24.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.MoreVert,
                                                    contentDescription = "Options",
                                                    tint = if (projectMenuOpen) cc.textPrimary else cc.textMuted.copy(alpha = 0.75f),
                                                    modifier = Modifier.size(if (isCompact) 18.dp else 14.dp)
                                                )
                                            }
                                        }
                                        if (!isCompact) {
                                            DropdownMenu(
                                                expanded = projectMenuOpen,
                                                onDismissRequest = { projectMenuOpen = false },
                                                modifier = Modifier
                                                    .background(cc.panel)
                                                    .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
                                                    .clip(RoundedCornerShape(12.dp))
                                            ) {
                                                DropdownMenuItem(
                                                    modifier = Modifier.height(32.dp),
                                                    text = {
                                                        Text(
                                                            "Copy Project Name",
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                                            color = cc.textPrimary
                                                        )
                                                    },
                                                    onClick = {
                                                        projectMenuOpen = false
                                                        clipboard.setText(AnnotatedString(project.name))
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                                    },
                                                    colors = MenuDefaults.itemColors(
                                                        textColor = cc.textPrimary,
                                                        leadingIconColor = cc.textMuted
                                                    ),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                )
                                                DropdownMenuItem(
                                                    modifier = Modifier.height(32.dp),
                                                    text = {
                                                        Text(
                                                            "Rename Project",
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                                            color = cc.textPrimary
                                                        )
                                                    },
                                                    onClick = {
                                                        projectMenuOpen = false
                                                        renamingProject = project
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Outlined.Edit, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                                    },
                                                    colors = MenuDefaults.itemColors(
                                                        textColor = cc.textPrimary,
                                                        leadingIconColor = cc.textMuted
                                                    ),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                )
                                                DropdownMenuItem(
                                                    modifier = Modifier.height(32.dp),
                                                    text = {
                                                        Text(
                                                            "Project Settings",
                                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                                            color = cc.textPrimary
                                                        )
                                                    },
                                                    onClick = {
                                                        projectMenuOpen = false
                                                        editingProjectConfig = project
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Outlined.Settings, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                                    },
                                                    colors = MenuDefaults.itemColors(
                                                        textColor = cc.textPrimary,
                                                        leadingIconColor = cc.textMuted
                                                    ),
                                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                )
                                                if (onOpenKnowledgeGraph != null) {
                                                    DropdownMenuItem(
                                                        modifier = Modifier.height(32.dp),
                                                        text = {
                                                            Text(
                                                                "Knowledge Graph",
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                                                color = cc.textPrimary
                                                            )
                                                        },
                                                        onClick = {
                                                            projectMenuOpen = false
                                                            onOpenKnowledgeGraph.invoke(project.id)
                                                        },
                                                        leadingIcon = {
                                                            Icon(Icons.Outlined.Hub, contentDescription = null, tint = cc.accent, modifier = Modifier.size(15.dp))
                                                        },
                                                        colors = MenuDefaults.itemColors(
                                                            textColor = cc.textPrimary,
                                                            leadingIconColor = cc.accent
                                                        ),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                    )
                                                }
                                                if (!isUngrouped) {
                                                    HorizontalDivider(color = cc.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 2.dp))
                                                    DropdownMenuItem(
                                                        modifier = Modifier.height(32.dp),
                                                        text = {
                                                            Text(
                                                                "Delete Project",
                                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                                                color = MaterialTheme.colorScheme.error
                                                            )
                                                        },
                                                        onClick = {
                                                            projectMenuOpen = false
                                                            projectPendingDelete = project
                                                        },
                                                        leadingIcon = {
                                                            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                                                        },
                                                        colors = MenuDefaults.itemColors(
                                                            textColor = MaterialTheme.colorScheme.error,
                                                            leadingIconColor = MaterialTheme.colorScheme.error
                                                        ),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Quick "+" Icon to add discussion under this project
                                    ThemedTooltipBox("New Discussion in this project") {
                                        IconButton(
                                            onClick = { onNewDiscussion(project.id) },
                                            modifier = Modifier.size(if (isCompact) 36.dp else 24.dp)
                                        ) {
                                            Icon(
                                                Icons.Outlined.Add,
                                                contentDescription = "New Discussion",
                                                tint = cc.textMuted.copy(alpha = 0.75f),
                                                modifier = Modifier.size(if (isCompact) 18.dp else 14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Indented Discussions List (Collapsible with AnimatedVisibility)
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            if (visibleDiscussions.isEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(30.dp)
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Spacer(Modifier.width(21.dp))
                                    Text(
                                        "No conversations yet",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        color = if (cc.isDark) Color(0xFF6B7280) else Color(0xFF9CA3AF)
                                    )
                                }
                            } else {
                                val isShowAll = expandedAllProjects[project.id] ?: false
                                val displayedDiscussions = if (!isShowAll && visibleDiscussions.size > 6) visibleDiscussions.take(6) else visibleDiscussions

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy))
                                        .padding(top = 1.dp, bottom = 2.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    displayedDiscussions.forEachIndexed { index, disc ->
                                    SidebarDiscussionRow(
                                        disc = disc,
                                        index = index,
                                        isSelected = disc.id == selectedDiscussionId,
                                        isSeen = seenDiscussionStatuses[disc.id] == disc.status,
                                        isCompact = isCompact,
                                        cc = cc,
                                        projects = projects,
                                        onSelectDiscussion = onSelectDiscussion,
                                        onMoveDiscussionToProject = onMoveDiscussionToProject,
                                        onRenameClick = { d -> renamingDiscussion = d },
                                        onDeleteClick = { d -> discussionPendingDelete = d },
                                        onCopyDiscussionSettings = onCopyDiscussionSettings,
                                        clipboard = clipboard,
                                        haptic = haptic,
                                        showProjectIndent = true,
                                        projectBounds = projectBounds,
                                        onDragStateChange = { isDragging: Boolean, draggingDisc: Discussion?, hitProj: String? ->
                                            draggingDiscussion = draggingDisc
                                            hoveredTargetProjectId = hitProj
                                        },
                                        currentTimeMs = currentTimeMs
                                    )
                                }
                            }

                            if (visibleDiscussions.size > 6) {
                                val seeAllInteractionSource = remember { MutableInteractionSource() }
                                val isSeeAllHovered by seeAllInteractionSource.collectIsHoveredAsState()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSeeAllHovered) cc.panelAlt.copy(alpha = 0.5f) else Color.Transparent)
                                        .hoverable(seeAllInteractionSource)
                                        .clickable { expandedAllProjects[project.id] = !isShowAll }
                                        .height(28.dp)
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Spacer(Modifier.width(21.dp))
                                    Text(
                                        text = if (isShowAll) "See less" else "See all (${visibleDiscussions.size})",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Normal,
                                            color = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)
                                        )
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

        if (cliStatus != null) {
            CliStatusFooter(
                supportsCli = true,
                status = cliStatus,
                cliLogins = cliLogins,
                onRecheck = onRecheckCli
            )
        }

        // ── 5. Bottom App Brand Logo, Name & Settings ─────────────────────
        HorizontalDivider(color = cc.border.copy(alpha = 0.4f), thickness = 0.75.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 14.dp, vertical = if (isCompact) 6.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Aesthetic App Brand Logo & Name (Clickable to open About & Legal)
            ThemedTooltipBox("About Dialex, Privacy & Legal") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onOpenAbout?.invoke() ?: onOpenSettings() }
                        .padding(vertical = 2.dp, horizontal = 2.dp)
                ) {
                    DialexLogoView(
                        size = if (isCompact) 32.dp else 26.dp,
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(Modifier.width(9.dp))

                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            "Dialex",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.5.sp
                            ),
                            color = cc.textPrimary,
                            maxLines = 1
                        )
                        val engineStatus = remember(connectionLabel) {
                            val trimmed = connectionLabel.trim()
                            when {
                                trimmed.isEmpty() ||
                                trimmed.contains("This device", ignoreCase = true) ||
                                trimmed.contains("Local", ignoreCase = true) -> "Local Engine"
                                trimmed.startsWith("Remote:", ignoreCase = true) -> {
                                    val rawUrl = trimmed.substringAfter("Remote:").trim()
                                    val host = runCatching {
                                        rawUrl.substringAfter("://").substringBefore("/")
                                    }.getOrDefault(rawUrl)
                                    if (host.isNotBlank()) host else rawUrl
                                }
                                trimmed.contains("://") -> {
                                    val host = runCatching {
                                        trimmed.substringAfter("://").substringBefore("/")
                                    }.getOrDefault(trimmed)
                                    if (host.isNotBlank()) host else trimmed
                                }
                                else -> trimmed
                            }
                        }
                        Text(
                            text = engineStatus,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal
                            ),
                            color = cc.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (onSendFeedback != null) {
                    ThemedTooltipBox("Send feedback / Report issue") {
                        IconButton(
                            onClick = onSendFeedback,
                            modifier = Modifier.size(if (isCompact) 48.dp else 28.dp)
                        ) {
                            Icon(
                                Icons.Outlined.BugReport,
                                contentDescription = "Send feedback",
                                tint = cc.textMuted,
                                modifier = Modifier.size(if (isCompact) 20.dp else 16.dp)
                            )
                        }
                    }
                }

                // Settings icon button
                ThemedTooltipBox("Settings & API Keys") {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(if (isCompact) 48.dp else 28.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = cc.textPrimary,
                            modifier = Modifier.size(if (isCompact) 20.dp else 16.dp)
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (newProjectDialogOpen) {
        NameDialog(
            title = "Create New Project",
            onDismiss = { newProjectDialogOpen = false },
            onConfirm = { name ->
                newProjectDialogOpen = false
                onCreateProject(name)
            }
        )
    }

    renamingProject?.let { proj ->
        NameDialog(
            title = "Rename Project",
            confirmLabel = "Save",
            placeholder = "Project Name",
            initialValue = proj.name,
            onDismiss = { renamingProject = null },
            onConfirm = { name ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank() && trimmed != proj.name) {
                    onUpdateProject?.invoke(proj.copy(name = trimmed))
                }
                renamingProject = null
            }
        )
    }

    renamingDiscussion?.let { disc ->
        NameDialog(
            title = "Rename Discussion",
            confirmLabel = "Save",
            placeholder = "Discussion Name",
            initialValue = disc.name,
            onDismiss = { renamingDiscussion = null },
            onConfirm = { name ->
                val trimmed = name.trim()
                if (trimmed.isNotBlank() && trimmed != disc.name) {
                    onRenameDiscussion?.invoke(disc.id, trimmed)
                }
                renamingDiscussion = null
            }
        )
    }

    if (editingProjectConfig != null) {
        ProjectConfigDialog(
            project = editingProjectConfig!!,
            isCompact = isCompact,
            onDismiss = { editingProjectConfig = null },
            onSave = { updated ->
                onUpdateProject?.invoke(updated)
                editingProjectConfig = null
            }
        )
    }

    if (projectPendingDelete != null) {
        val proj = projectPendingDelete!!
        ConfirmDialog(
            title = "Delete \"${proj.name}\"?",
            message = "All debates under this project will be deleted permanently.",
            confirmLabel = "Delete Project",
            onDismiss = { projectPendingDelete = null },
            onConfirm = {
                onDeleteProject(proj.id)
                projectPendingDelete = null
            }
        )
    }

    if (discussionPendingDelete != null) {
        val disc = discussionPendingDelete!!
        ConfirmDialog(
            title = "Delete \"${disc.name}\"?",
            message = "This conversation transcript will be removed.",
            confirmLabel = "Delete",
            onDismiss = { discussionPendingDelete = null },
            onConfirm = {
                onDeleteDiscussion(disc.id)
                discussionPendingDelete = null
            }
        )
    }
}

@Composable
private fun SidebarTopItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val cc = LocalAppColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) cc.panelAlt
                else if (isHovered) cc.panelAlt.copy(alpha = 0.5f)
                else Color.Transparent
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = cc.textMuted,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = if (isSelected) cc.textPrimary else cc.textPrimary.copy(alpha = 0.9f)
        )
    }
}

@Composable
fun GlowingDot(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glowingDot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowingDotAlpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowingDotScale"
    )

    Box(
        modifier = modifier.size(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing glowing halo
        Box(
            modifier = Modifier
                .size(11.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(CircleShape)
                .background(color.copy(alpha = 0.45f))
        )
        // Solid crisp core dot
        Box(
            modifier = Modifier
                .size(6.5.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

@Composable
private fun DiscussionStatusIndicator(
    disc: Discussion,
    timeLabel: String,
    isSelected: Boolean,
    isSeen: Boolean,
    modifier: Modifier = Modifier
) {
    val cc = LocalAppColors.current

    // If currently running, always show loading spinner ("unless its loading?")
    if (disc.status == DiscussionStatus.RUNNING) {
        ThemedTooltipBox("Discussion in progress") {
            CircularProgressIndicator(
                modifier = modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = cc.accent
            )
        }
        return
    }

    // Once opened by user or already seen, remove notification dots and show clean time label
    if (isSelected || isSeen) {
        Text(
            timeLabel,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 11.5.sp,
                color = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)
            ),
            modifier = modifier.padding(end = 2.dp)
        )
        return
    }

    val isWaitingApproval = (disc.config.userInterventionPolicy == UserInterventionPolicy.HUMAN_GATEKEEPER && !disc.status.isCompleted && disc.status != DiscussionStatus.DRAFT) ||
        (disc.handoffPrompt != null && disc.status == DiscussionStatus.PAUSED)
    val isManuallyPaused = disc.status == DiscussionStatus.PAUSED && !isWaitingApproval

    when {
        isWaitingApproval -> {
            ThemedTooltipBox("Requires user intervention / approval") {
                GlowingDot(color = Color(0xFF3B82F6), modifier = modifier)
            }
        }
        disc.status.isCompleted -> {
            ThemedTooltipBox("Debate finished") {
                GlowingDot(color = Color(0xFF10B981), modifier = modifier)
            }
        }
        isManuallyPaused -> {
            ThemedTooltipBox("Debate paused / stopped") {
                Box(
                    modifier = modifier.size(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF59E0B))
                    )
                }
            }
        }
        else -> {
            Text(
                timeLabel,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.5.sp,
                    color = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280)
                ),
                modifier = modifier.padding(end = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SidebarDiscussionRow(
    disc: Discussion,
    index: Int,
    isSelected: Boolean,
    isSeen: Boolean,
    isCompact: Boolean,
    cc: com.dialex.theme.CcPalette,
    projects: List<com.dialex.model.Project>,
    onSelectDiscussion: (String) -> Unit,
    onMoveDiscussionToProject: ((String, String) -> Unit)?,
    onRenameClick: (Discussion) -> Unit,
    onDeleteClick: (Discussion) -> Unit,
    onCopyDiscussionSettings: ((Discussion) -> Unit)? = null,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    showProjectIndent: Boolean = true,
    projectBounds: Map<String, Rect> = emptyMap(),
    onDragStateChange: ((Boolean, Discussion?, String?) -> Unit)? = null,
    currentTimeMs: Long = 0L
) {
    val timeLabel = remember(disc.updatedAt, disc.createdAt, disc.id, currentTimeMs) {
        val ts = if (disc.updatedAt > 0L) disc.updatedAt else if (disc.createdAt > 0L) disc.createdAt else 0L
        formatRelativeTime(ts, if (currentTimeMs > 0L) currentTimeMs else System.currentTimeMillis())
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val coroutineScope = rememberCoroutineScope()
    var itemWindowPos by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var startTouchOffset by remember { mutableStateOf(Offset.Zero) }
    var isItemDragging by remember { mutableStateOf(false) }
    val dragOffsetAnim = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var lastHitProject by remember { mutableStateOf<String?>(null) }
    var discActionsOpen by remember { mutableStateOf(false) }
    var discMenuOpen by remember { mutableStateOf(false) }

    // Mobile discussion actions bottom sheet
    if (isCompact && discActionsOpen) {
        ModalBottomSheet(
            onDismissRequest = { discActionsOpen = false },
            containerColor = cc.panel,
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    disc.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                    color = cc.textPrimary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            discActionsOpen = false
                            clipboard.setText(AnnotatedString(disc.name))
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Copy Discussion Name", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cc.textPrimary)
                    }
                }

                if (onCopyDiscussionSettings != null) {
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                discActionsOpen = false
                                onCopyDiscussionSettings(disc)
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Outlined.CopyAll, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("Copy Settings to New Discussion", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cc.textPrimary)
                        }
                    }
                }

                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            discActionsOpen = false
                            onRenameClick(disc)
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Rename Discussion", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cc.textPrimary)
                    }
                }

                val otherProjects = remember(projects, disc.projectId) {
                    projects.filter { it.id != disc.projectId }
                }
                if (otherProjects.isNotEmpty() && onMoveDiscussionToProject != null) {
                    Text(
                        "Move to Project",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = cc.textMuted
                    )
                    otherProjects.forEach { otherProj ->
                        Surface(
                            color = Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    discActionsOpen = false
                                    onMoveDiscussionToProject.invoke(disc.id, otherProj.id)
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Outlined.Folder, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(otherProj.name, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cc.textPrimary)
                            }
                        }
                    }
                    HorizontalDivider(color = cc.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 4.dp))
                }

                Surface(
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            discActionsOpen = false
                            onDeleteClick(disc)
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Delete Discussion", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    // Floating elevation and scale animations for the dragged card
    val dragScale by animateFloatAsState(
        targetValue = if (isItemDragging) 1.03f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    val dragElevation by animateDpAsState(
        targetValue = if (isItemDragging) 10.dp else 0.dp,
        animationSpec = tween(150)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (isItemDragging) 100f else 0f)
            .graphicsLayer {
                translationX = dragOffsetAnim.value.x
                translationY = dragOffsetAnim.value.y
                scaleX = dragScale
                scaleY = dragScale
                rotationZ = (dragOffsetAnim.value.x * 0.035f).coerceIn(-4f, 4f)
                alpha = if (isItemDragging) 0.90f else 1.0f
                shadowElevation = if (isItemDragging) 16f else 0f
            }
            .then(
                if (isItemDragging) Modifier.shadow(dragElevation, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isItemDragging) (if (cc.isDark) Color(0xFF2E3038) else Color(0xFFE5E7EB))
                else if (isSelected) (if (cc.isDark) Color(0xFF282830) else Color(0xFFE5E7EB))
                else if (isHovered) (if (cc.isDark) Color(0xFF1E1F24) else Color(0xFFEDEDEE))
                else Color.Transparent
            )
            .then(
                if (isItemDragging) Modifier.border(BorderStroke(1.dp, cc.accent.copy(alpha = 0.6f)), RoundedCornerShape(8.dp))
                else Modifier
            )
            .onGloballyPositioned { coords ->
                itemWindowPos = coords.positionInWindow()
            }
            .then(
                if (showProjectIndent && onDragStateChange != null) {
                    Modifier.pointerInput(disc.id) {
                        detectDragGestures(
                            onDragStart = { localOffset ->
                                isItemDragging = true
                                startTouchOffset = localOffset
                                lastHitProject = null
                                dragDelta = Offset.Zero
                                coroutineScope.launch { dragOffsetAnim.snapTo(Offset.Zero) }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDragStateChange(true, disc, null)
                            },
                            onDragEnd = {
                                val targetProj = lastHitProject
                                if (targetProj != null && onMoveDiscussionToProject != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onMoveDiscussionToProject.invoke(disc.id, targetProj)
                                    isItemDragging = false
                                    dragDelta = Offset.Zero
                                    coroutineScope.launch { dragOffsetAnim.snapTo(Offset.Zero) }
                                    onDragStateChange(false, null, null)
                                } else {
                                    onDragStateChange(false, null, null)
                                    coroutineScope.launch {
                                        dragOffsetAnim.animateTo(
                                            Offset.Zero,
                                            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                                        )
                                        isItemDragging = false
                                        dragDelta = Offset.Zero
                                    }
                                }
                                lastHitProject = null
                            },
                            onDragCancel = {
                                onDragStateChange(false, null, null)
                                coroutineScope.launch {
                                    dragOffsetAnim.animateTo(
                                        Offset.Zero,
                                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
                                    )
                                    isItemDragging = false
                                    dragDelta = Offset.Zero
                                }
                                lastHitProject = null
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragDelta += dragAmount
                                coroutineScope.launch { dragOffsetAnim.snapTo(dragDelta) }
                                val cursorGlobal = itemWindowPos + startTouchOffset + dragDelta
                                val hitProject = projectBounds.entries.firstOrNull { (projId, rect) ->
                                    projId != disc.projectId &&
                                    cursorGlobal.x in (rect.left - 24f)..(rect.right + 24f) &&
                                    cursorGlobal.y in (rect.top - 8f)..(rect.bottom + 8f)
                                }?.key
                                lastHitProject = hitProject
                                onDragStateChange(true, disc, hitProject)
                            }
                        )
                    }
                } else Modifier
            )
            .hoverable(interactionSource)
            .combinedClickable(
                onClick = { onSelectDiscussion(disc.id) },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    discActionsOpen = true
                }
            )
            .height(32.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showProjectIndent) {
            Spacer(Modifier.width(21.dp))
        } else {
            Icon(
                Icons.AutoMirrored.Outlined.Chat,
                contentDescription = null,
                tint = if (isSelected) cc.accent else cc.textMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            disc.name,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal
            ),
            color = if (isSelected) (if (cc.isDark) Color(0xFFF9FAFB) else Color(0xFF111827))
                    else (if (cc.isDark) Color(0xFFD1D5DB) else Color(0xFF374151)),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        val showDesktopActions = !isCompact && (isHovered || discMenuOpen)

        if (isCompact) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DiscussionStatusIndicator(disc, timeLabel, isSelected, isSeen)
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        discActionsOpen = true
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Discussion options",
                        tint = cc.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else if (showDesktopActions) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DiscussionStatusIndicator(disc, timeLabel, isSelected, isSeen)

                // Discussion 3-dots Context Menu
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (discMenuOpen) cc.panelAlt else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    ThemedTooltipBox("Discussion options") {
                        IconButton(
                            onClick = { discMenuOpen = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = if (discMenuOpen) cc.textPrimary else cc.textMuted.copy(alpha = 0.75f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = discMenuOpen,
                        onDismissRequest = { discMenuOpen = false },
                        modifier = Modifier
                            .background(cc.panel)
                            .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        DropdownMenuItem(
                            modifier = Modifier.height(32.dp),
                            text = {
                                Text(
                                    "Copy Discussion Name",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                    color = cc.textPrimary
                                )
                            },
                            onClick = {
                                discMenuOpen = false
                                clipboard.setText(AnnotatedString(disc.name))
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(15.dp))
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = cc.textPrimary,
                                leadingIconColor = cc.textMuted
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        )
                        if (onCopyDiscussionSettings != null) {
                            DropdownMenuItem(
                                modifier = Modifier.height(32.dp),
                                text = {
                                    Text(
                                        "Copy Settings to New Discussion",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                        color = cc.textPrimary
                                    )
                                },
                                onClick = {
                                    discMenuOpen = false
                                    onCopyDiscussionSettings(disc)
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.CopyAll, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                },
                                colors = MenuDefaults.itemColors(
                                    textColor = cc.textPrimary,
                                    leadingIconColor = cc.textMuted
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                            )
                        }
                        DropdownMenuItem(
                            modifier = Modifier.height(32.dp),
                            text = {
                                Text(
                                    "Rename Discussion",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                    color = cc.textPrimary
                                )
                            },
                            onClick = {
                                discMenuOpen = false
                                onRenameClick(disc)
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Edit, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(15.dp))
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = cc.textPrimary,
                                leadingIconColor = cc.textMuted
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        )
                        val otherProjects = remember(projects, disc.projectId) {
                            projects.filter { it.id != disc.projectId }
                        }
                        if (otherProjects.isNotEmpty() && onMoveDiscussionToProject != null) {
                            otherProjects.forEach { otherProj ->
                                DropdownMenuItem(
                                    modifier = Modifier.height(32.dp),
                                    text = {
                                        Text(
                                            "Move to: ${otherProj.name}",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                            color = cc.textPrimary
                                        )
                                    },
                                    onClick = {
                                        discMenuOpen = false
                                        onMoveDiscussionToProject.invoke(disc.id, otherProj.id)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = cc.textPrimary,
                                        leadingIconColor = cc.textMuted
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = cc.border.copy(alpha = 0.35f), modifier = Modifier.padding(vertical = 2.dp))

                        DropdownMenuItem(
                            modifier = Modifier.height(32.dp),
                            text = {
                                Text(
                                    "Delete Discussion",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                discMenuOpen = false
                                onDeleteClick(disc)
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(15.dp))
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error,
                                leadingIconColor = MaterialTheme.colorScheme.error
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                        )
                    }
                }
            }
        } else {
            DiscussionStatusIndicator(disc, timeLabel, isSelected, isSeen)
        }
    }
}
