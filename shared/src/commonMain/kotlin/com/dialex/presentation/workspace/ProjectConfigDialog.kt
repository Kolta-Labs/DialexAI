package com.dialex.presentation.workspace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dialex.model.FolderScope
import com.dialex.model.Project
import com.dialex.model.DebatePolicy
import com.dialex.model.UserInterventionPolicy
import com.dialex.theme.LocalAppColors
import com.dialex.ui.AestheticSwitch
import com.dialex.ui.GradientButton
import com.dialex.ui.ThemedTooltipBox
import com.dialex.util.validateFolders

private enum class ProjectConfigTab(val label: String, val icon: ImageVector) {
    INHERITANCE("Context", Icons.Outlined.Description),
    PERMISSIONS("Permissions", Icons.Outlined.Shield),
    DEBATE_POLICY("Policies", Icons.Outlined.Tune),
    GENERAL("Workspace", Icons.Outlined.Folder)
}

/**
 * Adaptive dialog and bottom sheet for configuring project settings.
 * Adopts the clean, spacious grouped-card design philosophy from Antigravity/Claude Code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectConfigDialog(
    project: Project,
    isCompact: Boolean,
    onDismiss: () -> Unit,
    onSave: (Project) -> Unit,
) {
    val cc = LocalAppColors.current
    var selectedTab by remember { mutableStateOf(ProjectConfigTab.INHERITANCE) }

    var name by remember(project.id) { mutableStateOf(project.name) }
    var sharedContext by remember(project.id) { mutableStateOf(project.sharedContext) }
    var sharedInstructions by remember(project.id) { mutableStateOf(project.sharedInstructions) }
    var defaultConsensus by remember(project.id) { mutableStateOf(project.defaultConsensus) }
    var commandApproval by remember(project.id) { mutableStateOf(project.workspaceScope.commandApproval) }
    var autoApproveSafe by remember(project.id) { mutableStateOf(project.workspaceScope.autoApproveSafeCommands) }
    var folders by remember(project.id) { mutableStateOf(project.workspaceScope.folders) }
    var addFolderDialogOpen by remember { mutableStateOf(false) }
    var validationRefreshTrigger by remember { mutableStateOf(0) }
    var inheritFromApp by remember(project.id) { mutableStateOf(project.debatePolicy == null) }
    var debatePolicy by remember(project.id) { mutableStateOf(project.debatePolicy ?: DebatePolicy()) }
    var permissions by remember(project.id) { mutableStateOf(project.permissions) }

    val invalidFolders = remember(folders, validationRefreshTrigger) {
        validateFolders(folders)
    }

    fun handleSave() {
        if (name.isNotBlank() && invalidFolders.isEmpty()) {
            val updated = project.copy(
                name = name.trim(),
                sharedContext = sharedContext.trim(),
                sharedInstructions = sharedInstructions.trim(),
                defaultConsensus = defaultConsensus,
                workspaceScope = project.workspaceScope.copy(
                    folders = folders,
                    commandApproval = commandApproval,
                    autoApproveSafeCommands = autoApproveSafe
                ),
                debatePolicy = if (inheritFromApp) null else debatePolicy,
                permissions = permissions
            )
            onSave(updated)
        }
    }

    @Composable
    fun SectionTitle(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 13.5.sp
            ),
            color = cc.textPrimary.copy(alpha = 0.9f),
            modifier = Modifier.padding(bottom = 2.dp)
        )
    }

    @Composable
    fun SettingGroupCard(content: @Composable ColumnScope.() -> Unit) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cc.panelAlt.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }

    @Composable
    fun SettingItemRow(
        title: String,
        description: String? = null,
        modifier: Modifier = Modifier,
        action: @Composable () -> Unit
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = cc.textPrimary.copy(alpha = 0.88f)
                )
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        ),
                        color = cc.textMuted
                    )
                }
            }
            action()
        }
    }

    @Composable
    fun ContentBody() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (isCompact) 20.dp else 28.dp, vertical = 20.dp)
        ) {
            // ── Top Header ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        "Project Settings",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp
                        ),
                        color = cc.textPrimary
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Configure project context, agent permissions, and debate defaults.",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                        color = cc.textMuted
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Close",
                        tint = cc.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            // ── Tab Bar Navigation ───
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = cc.panelAlt.copy(alpha = 0.6f),
                border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ProjectConfigTab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) cc.panel else Color.Transparent,
                            border = if (isSelected) BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f)) else null,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedTab = tab }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    tab.icon,
                                    contentDescription = null,
                                    tint = if (isSelected) cc.accent else cc.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    tab.label,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                                        fontSize = 12.5.sp
                                    ),
                                    color = if (isSelected) cc.textPrimary else cc.textMuted
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Scrollable Body Viewport ───
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    when (selectedTab) {
                        // ── Tab 1: Discussion Context ───
                        ProjectConfigTab.INHERITANCE -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionTitle("Shared Context")
                                SettingGroupCard {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "Domain knowledge, architecture constraints, and business rules visible to all debate agents.",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                                            color = cc.textMuted
                                        )
                                        OutlinedTextField(
                                            value = sharedContext,
                                            onValueChange = { sharedContext = it },
                                            placeholder = {
                                                Text(
                                                    "e.g. Kotlin Multiplatform 2.1, Compose Multiplatform, Clean Architecture with MVI…",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                                    color = cc.textMuted.copy(alpha = 0.5f)
                                                )
                                            },
                                            minLines = 4,
                                            maxLines = 8,
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = cc.textPrimary),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = cc.accent,
                                                unfocusedBorderColor = cc.border.copy(alpha = 0.4f),
                                                focusedContainerColor = cc.panel,
                                                unfocusedContainerColor = cc.panel
                                            )
                                        )
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionTitle("Shared Instructions")
                                SettingGroupCard {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            "Rules of engagement, code style guidelines, and evaluation criteria enforced across discussions.",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                                            color = cc.textMuted
                                        )
                                        OutlinedTextField(
                                            value = sharedInstructions,
                                            onValueChange = { sharedInstructions = it },
                                            placeholder = {
                                                Text(
                                                    "e.g. Evaluate performance trade-offs, cite official documentation, avoid breaking public APIs…",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                                    color = cc.textMuted.copy(alpha = 0.5f)
                                                )
                                            },
                                            minLines = 4,
                                            maxLines = 8,
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp, color = cc.textPrimary),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = cc.accent,
                                                unfocusedBorderColor = cc.border.copy(alpha = 0.4f),
                                                focusedContainerColor = cc.panel,
                                                unfocusedContainerColor = cc.panel
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // ── Tab 2: Permissions ───
                        ProjectConfigTab.PERMISSIONS -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionTitle("Read Capabilities")
                                SettingGroupCard {
                                    SettingItemRow(
                                        title = "Web Search & URL Content",
                                        description = "Allows agents to query search engines and read public documentation."
                                    ) {
                                        AestheticSwitch(
                                            checked = permissions.allowWebSearch,
                                            onCheckedChange = { permissions = permissions.copy(allowWebSearch = it) }
                                        )
                                    }

                                    HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)

                                    SettingItemRow(
                                        title = "Workspace File Reading",
                                        description = "Allows reading files inside workspace and attached folders."
                                    ) {
                                        AestheticSwitch(
                                            checked = permissions.allowFileRead,
                                            onCheckedChange = { permissions = permissions.copy(allowFileRead = it) }
                                        )
                                    }

                                    HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)

                                    SettingItemRow(
                                        title = "Safe Shell Inspections",
                                        description = "Allows read-only inspection commands (git status, log, ls, grep)."
                                    ) {
                                        AestheticSwitch(
                                            checked = permissions.allowSafeShell,
                                            onCheckedChange = { permissions = permissions.copy(allowSafeShell = it) }
                                        )
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionTitle("Execution & Modifications")
                                SettingGroupCard {
                                    SettingItemRow(
                                        title = "Workspace File Writes",
                                        description = "Allows agents to create, modify, or delete files in the project workspace."
                                    ) {
                                        AestheticSwitch(
                                            checked = permissions.allowFileWrite,
                                            onCheckedChange = { permissions = permissions.copy(allowFileWrite = it) }
                                        )
                                    }

                                    HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)

                                    SettingItemRow(
                                        title = "Terminal Commands Execution",
                                        description = "Allows executing build tools, test suites, and custom scripts."
                                    ) {
                                        AestheticSwitch(
                                            checked = permissions.allowShellCommands,
                                            onCheckedChange = { permissions = permissions.copy(allowShellCommands = it) }
                                        )
                                    }
                                }
                            }
                        }

                        // ── Tab 3: Policies ───
                        ProjectConfigTab.DEBATE_POLICY -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionTitle("Policy Inheritance")
                                SettingGroupCard {
                                    SettingItemRow(
                                        title = "Inherit Global App Policies",
                                        description = "Uses the global debate defaults configured in App Settings."
                                    ) {
                                        AestheticSwitch(
                                            checked = inheritFromApp,
                                            onCheckedChange = { inheritFromApp = it }
                                        )
                                    }
                                }
                            }

                            if (!inheritFromApp) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SectionTitle("Participation Mode")
                                    SettingGroupCard {
                                        UserInterventionPolicy.entries.forEachIndexed { index, mode ->
                                            val isSel = debatePolicy.userInterventionPolicy == mode
                                            if (index > 0) {
                                                HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { debatePolicy = debatePolicy.copy(userInterventionPolicy = mode) }
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = isSel,
                                                    onClick = { debatePolicy = debatePolicy.copy(userInterventionPolicy = mode) },
                                                    colors = RadioButtonDefaults.colors(selectedColor = cc.accent)
                                                )
                                                Spacer(Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        mode.label,
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal,
                                                            fontSize = 13.sp
                                                        ),
                                                        color = if (isSel) cc.accent else cc.textPrimary
                                                    )
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        mode.description,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                                        color = cc.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SectionTitle("Debate Optimizations & Tone")
                                    val mem = debatePolicy.sharedMemory
                                    val mod = debatePolicy.moderation
                                    SettingGroupCard {
                                        SettingItemRow(
                                            title = "Human-Like Dialogue Mode (Anti-Fluff)",
                                            description = "Forces concise 2–4 sentence turns without robotic filler, headers, or monologues."
                                        ) {
                                            AestheticSwitch(
                                                checked = debatePolicy.humanDialogueMode,
                                                onCheckedChange = { debatePolicy = debatePolicy.copy(humanDialogueMode = it) }
                                            )
                                        }

                                        HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)

                                        SettingItemRow(
                                            title = "Topic Drift Guardrail (Anti-Rabbit-Hole)",
                                            description = "Keeps participants strictly anchored to the core question without digressing into tangents."
                                        ) {
                                            AestheticSwitch(
                                                checked = mod.detectTopicDrift,
                                                onCheckedChange = { debatePolicy = debatePolicy.copy(moderation = mod.copy(detectTopicDrift = it)) }
                                            )
                                        }

                                        HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)

                                        SettingItemRow(
                                            title = "Shared Memory Compression",
                                            description = "Maintains rolling knowledge summaries between turns (saves 60–80% tokens)."
                                        ) {
                                            AestheticSwitch(
                                                checked = mem.enabled,
                                                onCheckedChange = { debatePolicy = debatePolicy.copy(sharedMemory = mem.copy(enabled = it)) }
                                            )
                                        }

                                        HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)

                                        SettingItemRow(
                                            title = "Strict Convergence Moderation",
                                            description = "Detects argument repetition and prompts agents toward resolution."
                                        ) {
                                            AestheticSwitch(
                                                checked = mod.enabled,
                                                onCheckedChange = { debatePolicy = debatePolicy.copy(moderation = mod.copy(enabled = it)) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // ── Tab 4: Workspace ───
                        ProjectConfigTab.GENERAL -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionTitle("Project Details")
                                SettingGroupCard {
                                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("Project Name", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp), color = cc.textPrimary)
                                        OutlinedTextField(
                                            value = name,
                                            onValueChange = { name = it },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = cc.textPrimary),
                                            shape = RoundedCornerShape(10.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = cc.accent,
                                                unfocusedBorderColor = cc.border.copy(alpha = 0.4f),
                                                focusedContainerColor = cc.panel,
                                                unfocusedContainerColor = cc.panel
                                            )
                                        )
                                    }

                                    HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)

                                    SettingItemRow(
                                        title = "Consensus Target",
                                        description = "Agreement threshold required before concluding discussion automatically."
                                    ) {
                                        val consensusOptions = listOf(
                                            1.0 to "Unanimous",
                                            0.8 to "80%",
                                            0.6 to "60%",
                                            0.5 to "50%"
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            consensusOptions.forEach { (value, label) ->
                                                val isSelected = defaultConsensus == value
                                                Surface(
                                                    shape = RoundedCornerShape(7.dp),
                                                    color = if (isSelected) cc.panel else cc.panelAlt,
                                                    border = BorderStroke(1.dp, if (isSelected) cc.accent.copy(alpha = 0.6f) else cc.border.copy(alpha = 0.3f)),
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(7.dp))
                                                        .clickable { defaultConsensus = value }
                                                ) {
                                                    Text(
                                                        label,
                                                        style = MaterialTheme.typography.bodySmall.copy(
                                                            fontSize = 11.5.sp,
                                                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                                                        ),
                                                        color = if (isSelected) cc.accent else cc.textMuted,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    SectionTitle("Scoped Workspace Folders")
                                    OutlinedButton(
                                        onClick = { addFolderDialogOpen = true },
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Outlined.Add, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Add Folder", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp), color = cc.textMuted)
                                    }
                                }

                                SettingGroupCard {
                                    if (folders.isEmpty()) {
                                        Text(
                                            "No folders attached. Add folders to allow agents to inspect reference files.",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                            color = cc.textMuted,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    } else {
                                        folders.forEachIndexed { index, folder ->
                                            if (index > 0) {
                                                HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)
                                            }
                                            val isInvalid = invalidFolders.any { it.first.path == folder.path }
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.Folder,
                                                        contentDescription = null,
                                                        tint = if (isInvalid) MaterialTheme.colorScheme.error else cc.textMuted,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        folder.path,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                                        color = cc.textPrimary
                                                    )
                                                    ThemedTooltipBox(if (folder.isTrusted) "Workspace is Trusted. Headless CLI tools run smoothly without permission prompts." else "Workspace is Restricted.") {
                                                        Surface(
                                                            onClick = {
                                                                folders = folders.map {
                                                                    if (it.path == folder.path) it.copy(isTrusted = !it.isTrusted) else it
                                                                }
                                                            },
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = if (folder.isTrusted) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
                                                            border = BorderStroke(0.5.dp, if (folder.isTrusted) cc.accent.copy(alpha = 0.4f) else cc.border.copy(alpha = 0.5f))
                                                        ) {
                                                            Text(
                                                                if (folder.isTrusted) "🛡️ Trusted" else "⚠️ Restricted",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Medium),
                                                                color = if (folder.isTrusted) cc.accent else cc.textMuted,
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                            )
                                                        }
                                                    }
                                                    if (folder.isReadOnly) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = cc.panel,
                                                            border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.35f))
                                                        ) {
                                                            Text(
                                                                "Read-Only",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                                                color = cc.textMuted,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                IconButton(
                                                    onClick = { folders = folders.filter { it.path != folder.path } },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Outlined.Close,
                                                        contentDescription = "Remove folder",
                                                        tint = cc.textMuted,
                                                        modifier = Modifier.size(14.dp)
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
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.75.dp)
            Spacer(Modifier.height(14.dp))

            // ── Bottom Fixed Action Footer ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = cc.textMuted
                    )
                }
                Spacer(Modifier.width(12.dp))
                GradientButton(
                    text = "Save Changes",
                    onClick = ::handleSave,
                    enabled = name.isNotBlank() && invalidFolders.isEmpty(),
                    height = 36.dp,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
                )
            }
        }

        if (addFolderDialogOpen) {
            Dialog(onDismissRequest = { addFolderDialogOpen = false }) {
                var folderPathInput by remember { mutableStateOf("") }
                var folderReadOnlyInput by remember { mutableStateOf(true) }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cc.panel,
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            "Add Workspace Folder",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp),
                            color = cc.textPrimary
                        )
                        OutlinedTextField(
                            value = folderPathInput,
                            onValueChange = { folderPathInput = it },
                            placeholder = { Text("e.g. /Users/name/project", style = MaterialTheme.typography.bodySmall, color = cc.textMuted) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = cc.accent,
                                unfocusedBorderColor = cc.border.copy(alpha = 0.4f),
                                focusedContainerColor = cc.panelAlt,
                                unfocusedContainerColor = cc.panelAlt
                            )
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = folderReadOnlyInput,
                                onCheckedChange = { folderReadOnlyInput = it },
                                colors = CheckboxDefaults.colors(checkedColor = cc.accent)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Read-Only (Agents can inspect, not modify)", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = cc.textPrimary)
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { addFolderDialogOpen = false }) { Text("Cancel", color = cc.textMuted) }
                            Spacer(Modifier.width(8.dp))
                            GradientButton(
                                text = "Add Folder",
                                onClick = {
                                    val trimmed = folderPathInput.trim()
                                    if (trimmed.isNotBlank()) {
                                        if (folders.none { it.path == trimmed }) {
                                            folders = folders + FolderScope(path = trimmed, isReadOnly = folderReadOnlyInput)
                                        }
                                        addFolderDialogOpen = false
                                    }
                                },
                                enabled = folderPathInput.isNotBlank(),
                                height = 34.dp
                            )
                        }
                    }
                }
            }
        }
    }

    if (isCompact) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = cc.panel,
            dragHandle = { BottomSheetDefaults.DragHandle(color = cc.border.copy(alpha = 0.35f)) }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                ContentBody()
            }
        }
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .width(680.dp)
                    .height(620.dp)
                    .clip(RoundedCornerShape(16.dp)),
                color = cc.panel,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, cc.border.copy(alpha = 0.35f)),
                shadowElevation = 10.dp
            ) {
                ContentBody()
            }
        }
    }
}
