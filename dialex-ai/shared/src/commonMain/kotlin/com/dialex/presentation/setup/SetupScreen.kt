@file:Suppress("DEPRECATION")

package com.dialex.presentation.setup

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.model.*
import com.dialex.orchestrator.DeliberationEstimator
import com.dialex.theme.LocalCcColors
import com.dialex.theme.accentColor
import com.dialex.ui.PersonaIconView
import com.dialex.ui.windowTitleBarDoubleClick
import com.dialex.ui.AestheticSlider
import com.dialex.ui.AestheticSwitch
import com.dialex.ui.CliCatalog
import com.dialex.ui.CliManagementDialog
import com.dialex.ui.GradientButton
import com.dialex.ui.NameDialog
import com.dialex.model.AttachedFile
import com.dialex.presentation.settings.SubtleSegmentedControl
import com.dialex.ui.FileChipItem
import com.dialex.ui.PersonaBadge
import com.dialex.ui.SubtleTextArea
import com.dialex.ui.ThemedDropdown
import com.dialex.ui.ThemedDropdownOption
import com.dialex.ui.ThemedTooltipBox
import com.dialex.ui.resolvePersonaIcon
import kotlin.math.roundToInt

/**
 * Aerated, High-Contrast Discussion Configuration Screen.
 * Design Highlights:
 * - Unified 48.dp top bar with Start Discussion CTA located in the top-right spot.
 * - High-contrast text throughout: explanations and labels are crystal clear and vivid.
 * - Minimum font size for small text is 13sp across all explanations, badges, and controls.
 * - Consistent vocabulary: "Discussion" and "Participant".
 * - Interactive consensus tolerance slider (min 51%) with live dynamic feedback.
 * - Shared Context folders with consistent dialog panels and dropdown styling.
 */
@Composable
fun SetupScreen(
    state: SetupState,
    onIntent: (SetupIntent) -> Unit,
    onToggleSidebar: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onManagePersonas: (() -> Unit)? = null,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val discussion = state.discussion ?: return
    val config = discussion.config

    var copyFromOpen by remember { mutableStateOf(false) }
    var newProjectDialogOpen by remember { mutableStateOf(false) }
    var addFolderDialogOpen by remember { mutableStateOf(false) }
    var savePresetDialogOpen by remember { mutableStateOf(false) }
    var missingFieldsBanner by remember { mutableStateOf<List<String>>(emptyList()) }

    val isEditing = discussion.id.isNotBlank()

    fun updateConfig(c: DebateConfig) = onIntent(SetupIntent.ConfigChanged(c))
    fun updatePrimary(a: Agent) = updateConfig(config.copy(primary = a))
    fun updateAdditionalAgents(agents: List<Agent>) {
        updateConfig(
            config.copy(
                secondary = agents.getOrNull(0),
                tertiary = agents.getOrNull(1),
                quaternary = agents.getOrNull(2),
                quinary = agents.getOrNull(3),
                senary = agents.getOrNull(4)
            )
        )
    }

    val additionalAgents = config.agents.drop(1)
    val filePicker = remember { FilePicker() }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val showToast: (String) -> Unit = { msg ->
        coroutineScope.launch {
            snackbarHostState.showSnackbar(msg)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(cc.bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        // ── 1. Top Canvas Header with Start Discussion CTA in top right ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(52.dp)
                .windowTitleBarDoubleClick()
                .padding(
                    start = if (!isCompact && onToggleSidebar != null) 0.dp else if (isCompact) 8.dp else 18.dp,
                    end = if (isCompact) 8.dp else 18.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Single Back button / Sidebar toggle + Project Dropdown + Copy Icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 8.dp)
            ) {
                // Reserve spacing for native macOS traffic lights (68.dp) + 6.dp left padding for show panel button
                if (!isCompact && onToggleSidebar != null) {
                    Spacer(Modifier.width(68.dp))
                    Spacer(Modifier.width(6.dp))
                }

                if (isEditing) {
                    ThemedTooltipBox("Back to discussion chat") {
                        IconButton(
                            onClick = { if (onBack != null) onBack() else onIntent(SetupIntent.NavigateBack) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back to Chat",
                                tint = cc.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    ThemedTooltipBox("Back to Templates") {
                        IconButton(
                            onClick = { onIntent(SetupIntent.NavigateToFrontPage) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Back to Templates",
                                tint = cc.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (onToggleSidebar != null) {
                    ThemedTooltipBox("Expand sidebar") {
                        IconButton(
                            onClick = onToggleSidebar,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ViewSidebar,
                                contentDescription = "Expand sidebar",
                                tint = cc.textPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Small left-side project dropdown
                val projectOptions = buildList {
                    state.projects.forEach { p ->
                        add(ThemedDropdownOption(id = p.id, title = p.name, icon = Icons.Outlined.Folder))
                    }
                    add(
                        ThemedDropdownOption(
                            id = "__new_project__",
                            title = "+ Create New Project",
                            icon = Icons.Outlined.CreateNewFolder,
                            isDividerBefore = true,
                            isAccent = true
                        )
                    )
                }

                ThemedDropdown(
                    selectedId = state.selectedProject?.id,
                    options = projectOptions,
                    onSelect = { opt ->
                        if (opt.id == "__new_project__") {
                            newProjectDialogOpen = true
                        } else {
                            onIntent(SetupIntent.SelectProject(opt.id))
                        }
                    },
                    placeholder = "Select Project",
                    leadingIcon = Icons.Outlined.Folder,
                    isMinimal = true,
                    minHeight = 34.dp,
                    isCompact = isCompact
                )

                if (!isEditing && state.otherDiscussions.isNotEmpty()) {
                    ThemedTooltipBox("Copy settings from another discussion") {
                        IconButton(
                            onClick = { copyFromOpen = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = "Copy from...",
                                tint = cc.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Center: Screen Title (hidden on ultra-narrow compact if project dropdown present)
            if (!isCompact) {
                Text(
                    if (isEditing) "Edit Discussion Setup" else "New Discussion Setup",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = cc.textPrimary,
                    maxLines = 1
                )
            }

            // Right: Secondary "Save Preset" and Primary "Start Discussion" CTA
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isEditing) {
                    ThemedTooltipBox("Save configuration as a reusable template") {
                        OutlinedButton(
                            onClick = { savePresetDialogOpen = true },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, cc.border),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = cc.panelAlt,
                                contentColor = cc.textPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                Icons.Outlined.BookmarkAdd,
                                contentDescription = "Save as Template",
                                tint = cc.accent,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                "Save as Template",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = cc.textPrimary
                            )
                        }
                    }
                }

                GradientButton(
                    text = if (isEditing) "Update" else if (state.mode == com.dialex.domain.model.DiscussionMode.SOCRATIC_INTERVIEW) "Begin Interview 🎯" else "Start Discussion",
                    height = 32.dp,
                    onClick = {
                    val missing = mutableListOf<String>()
                    if (discussion.name.isBlank()) {
                        missing.add("Discussion Title is required")
                    }
                    if (config.topic.isBlank()) {
                        missing.add("Discussion Objective & Topic is required")
                    }
                    if (state.mode == com.dialex.domain.model.DiscussionMode.SOCRATIC_INTERVIEW) {
                        if (config.primary.model.isBlank()) {
                            missing.add("Interviewer agent must have a model selected")
                        }
                    } else {
                        if (config.agents.size < 2) {
                            missing.add("At least 2 participant agents are required to start (currently ${config.agents.size})")
                        }
                    }

                    for (err in state.validationErrors) {
                        val clean = err.trim().trimEnd('.')
                        val isDuplicate = missing.any { existing ->
                            existing.equals(clean, ignoreCase = true) ||
                            (existing.contains("participant agent", ignoreCase = true) && clean.contains("participant agent", ignoreCase = true)) ||
                            (existing.contains("Discussion Title", ignoreCase = true) && clean.contains("Discussion Title", ignoreCase = true)) ||
                            (existing.contains("Objective", ignoreCase = true) && clean.contains("Objective", ignoreCase = true))
                        }
                        if (!isDuplicate) {
                            missing.add(clean)
                        }
                    }

                    if (missing.isNotEmpty()) {
                        missingFieldsBanner = missing
                    } else {
                        missingFieldsBanner = emptyList()
                        onIntent(SetupIntent.StartDiscussion)
                    }
                },
                enabled = true
            )
            }
        }

        HorizontalDivider(color = cc.border.copy(alpha = 0.4f), thickness = 0.75.dp)

        // ── 2. Aerated Scrollable Canvas with IME Keyboard Avoidance ──────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .padding(horizontal = if (isCompact) 16.dp else 28.dp, vertical = if (isCompact) 16.dp else 24.dp)
            ) {
                // Missing required items notification banner in copiable bullet list format
                AnimatedVisibility(
                    visible = missingFieldsBanner.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    var copied by remember { mutableStateOf(false) }
                    val clipboard = LocalClipboardManager.current

                    LaunchedEffect(copied) {
                        if (copied) {
                            delay(2000)
                            copied = false
                        }
                    }

                    Surface(
                        color = Color(0xFFEF4444).copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    missingFieldsBanner.forEach { errorItem ->
                                        Row(
                                            verticalAlignment = Alignment.Top,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                "•",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                color = Color(0xFFEF4444)
                                            )
                                            Text(
                                                errorItem,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = Color(0xFFEF4444)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ThemedTooltipBox(if (copied) "Copied to clipboard!" else "Copy errors") {
                                    IconButton(
                                        onClick = {
                                            val text = missingFieldsBanner.joinToString("\n") { "• $it" }
                                            clipboard.setText(AnnotatedString(text))
                                            copied = true
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            if (copied) Icons.Default.Check else Icons.Outlined.ContentCopy,
                                            contentDescription = if (copied) "Copied" else "Copy errors",
                                            tint = if (copied) cc.textPrimary else Color(0xFFEF4444).copy(alpha = 0.8f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                ThemedTooltipBox("Dismiss") {
                                    IconButton(
                                        onClick = { missingFieldsBanner = emptyList() },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Dismiss",
                                            tint = Color(0xFFEF4444).copy(alpha = 0.7f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Mode Switcher Pill: [ ⚔️ Council Debate | 🎯 Socratic Interview ] ──
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Council Debate Pill
                        val isCouncil = state.mode == com.dialex.domain.model.DiscussionMode.COUNCIL
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isCouncil) cc.panel else Color.Transparent,
                            border = if (isCouncil) BorderStroke(1.dp, cc.border) else null,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onIntent(SetupIntent.SetDiscussionMode(com.dialex.domain.model.DiscussionMode.COUNCIL)) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text("⚔️", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                                Spacer(Modifier.width(8.dp))
                                Column(verticalArrangement = Arrangement.Center) {
                                    Text(
                                        "Council Debate",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = if (isCouncil) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 12.5.sp
                                        ),
                                        color = if (isCouncil) cc.textPrimary else cc.textMuted
                                    )
                                    Text(
                                        "Multi-agent dialectic",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = cc.textMuted.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }

                        // Socratic Interview Pill
                        val isSocratic = state.mode == com.dialex.domain.model.DiscussionMode.SOCRATIC_INTERVIEW
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSocratic) (if (cc.isDark) Color(0xFF2E2616) else Color(0xFFFEF3C7)) else Color.Transparent,
                            border = if (isSocratic) BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f)) else null,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onIntent(SetupIntent.SetDiscussionMode(com.dialex.domain.model.DiscussionMode.SOCRATIC_INTERVIEW)) }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text("🎯", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp))
                                Spacer(Modifier.width(8.dp))
                                Column(verticalArrangement = Arrangement.Center) {
                                    Text(
                                        "Socratic Interview",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = if (isSocratic) FontWeight.SemiBold else FontWeight.Normal,
                                            fontSize = 12.5.sp
                                        ),
                                        color = if (isSocratic) (if (cc.isDark) Color(0xFFFBBF24) else Color(0xFFD97706)) else cc.textMuted
                                    )
                                    Text(
                                        "1-on-1 thesis stress-test",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = if (isSocratic) (if (cc.isDark) Color(0xFFFBBF24).copy(alpha = 0.8f) else Color(0xFFD97706).copy(alpha = 0.8f)) else cc.textMuted.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── LEVEL 1: Zero-Friction Setup Telemetry & Intent Presets ──
                state.runEstimate?.let { est ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cc.panel,
                        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("💰 Est. Cost:", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = cc.textMuted)
                                    Text("${est.costFormatted} (${est.tierLabel})", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = cc.textPrimary)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("⏱️ Est. Time:", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = cc.textMuted)
                                    Text(est.durationFormatted, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = cc.textPrimary)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("📊 Est. Tokens:", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = cc.textMuted)
                                    Text(est.tokensFormatted, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold), color = cc.textPrimary)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Intent Archetype Presets Selector
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Deliberation Intent Preset",
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                            color = cc.textPrimary
                        )
                        Text(
                            "Active: ${state.activeArchetype.displayName}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                            color = cc.accent
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val archetypes = listOf(
                            PresetArchetype.QUICK_TAKE to ("⚡ Quick Take" to "~$0.02 · ~30s"),
                            PresetArchetype.EXECUTIVE_DECISION to ("💼 Executive" to "~$0.15 · ~1.5m"),
                            PresetArchetype.DEEP_RESEARCH to ("🔬 Deep Research" to "~$0.55 · ~4m"),
                            PresetArchetype.RED_TEAM_STRESS_TEST to ("🥊 Red-Team" to "~$0.30 · ~2.5m")
                        )

                        for ((arch, info) in archetypes) {
                            val isSelected = state.activeArchetype == arch
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) cc.accent.copy(alpha = 0.12f) else cc.panel,
                                border = BorderStroke(
                                    if (isSelected) 1.5.dp else 0.75.dp,
                                    if (isSelected) cc.accent else cc.border.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onIntent(SetupIntent.SelectArchetype(arch)) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        info.first,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 12.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) cc.accent else cc.textPrimary
                                    )
                                    Text(
                                        info.second,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = cc.textMuted
                                    )
                                }
                            }
                        }

                        if (state.activeArchetype == PresetArchetype.CUSTOM) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(1.2.dp, Color(0xFFF59E0B).copy(alpha = 0.7f)),
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        "🛠️ Custom Tuning",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = Color(0xFFF59E0B)
                                    )
                                    Text(
                                        "User Overrides Active",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = cc.textMuted
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Discussion Title ───────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cc.panel)
                        .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Title",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary,
                        modifier = Modifier.width(50.dp)
                    )
                    BasicTextField(
                        value = discussion.name,
                        onValueChange = { onIntent(SetupIntent.DiscussionChanged(discussion.copy(name = it))) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            color = cc.textPrimary,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(cc.accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            if (discussion.name.isEmpty()) {
                                Text(
                                    "e.g. Microservices vs Modular Monolith",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                                    color = cc.textMuted.copy(alpha = 0.75f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ── Discussion Autopilot & Participation Mode ──────────────
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cc.panel,
                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (isCompact) 14.dp else 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val projPolicy = state.selectedProject?.debatePolicy
                        val isInherited = if (projPolicy != null) {
                            config.userInterventionPolicy == projPolicy.userInterventionPolicy
                        } else true

                        val modeOptions = listOf(
                            UserInterventionPolicy.AUTONOMOUS_AUTOPILOT to "Autonomous",
                            UserInterventionPolicy.OBSERVER_INTERACTIVE to "Interactive",
                            UserInterventionPolicy.HUMAN_GATEKEEPER to "Gatekeeper"
                        )

                        if (isCompact) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "Execution & Participation Mode",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = cc.textPrimary
                                    )
                                    Text(
                                        if (isInherited && projPolicy != null) "INHERITED FROM PROJECT" else if (isInherited) "INHERITED FROM SETTINGS" else "CUSTOM OVERRIDE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            letterSpacing = 0.4.sp
                                        ),
                                        color = cc.textMuted
                                    )
                                }
                                SubtleSegmentedControl(
                                    options = modeOptions,
                                    selected = config.userInterventionPolicy,
                                    onSelect = { updateConfig(config.copy(userInterventionPolicy = it)) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        "Execution & Participation Mode",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = cc.textPrimary
                                    )
                                    Text(
                                        if (isInherited && projPolicy != null) "· INHERITED FROM PROJECT" else if (isInherited) "· INHERITED FROM SETTINGS" else "· CUSTOM OVERRIDE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            letterSpacing = 0.4.sp
                                        ),
                                        color = cc.textMuted
                                    )
                                }

                                Spacer(Modifier.width(16.dp))

                                SubtleSegmentedControl(
                                    options = modeOptions,
                                    selected = config.userInterventionPolicy,
                                    onSelect = { updateConfig(config.copy(userInterventionPolicy = it)) }
                                )
                            }
                        }

                        HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.5.dp)

                        Text(
                            text = when (config.userInterventionPolicy) {
                                UserInterventionPolicy.AUTONOMOUS_AUTOPILOT ->
                                    "Autonomous Autopilot: The deliberation proceeds continuously from start to finish. Auto-moderates deadlocks and loops, synthesizes deliverables, and auto-saves artifacts without requiring human interaction."
                                UserInterventionPolicy.OBSERVER_INTERACTIVE ->
                                    "Observer with Comments: The deliberation proceeds automatically by default, but allows human observers to inject comments, objections, or guidance at any turn without halting the agenda."
                                UserInterventionPolicy.HUMAN_GATEKEEPER ->
                                    "Human Gatekeeper: The council pauses after each turn or round, requiring explicit human review and approval before agents commit actions or proceed."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = cc.textMuted
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // ── Deliberation Context Banner ──────────────────────────
                if (!isEditing) {
                    val activeTpl = state.selectedTemplate
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cc.panel,
                        border = BorderStroke(1.dp, if (activeTpl != null) cc.accent.copy(alpha = 0.35f) else cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (activeTpl != null) cc.accent.copy(alpha = 0.14f) else cc.panelAlt,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            if (activeTpl != null) Icons.Outlined.AutoAwesome else Icons.Outlined.Create,
                                            contentDescription = null,
                                            tint = if (activeTpl != null) cc.accent else cc.textMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            activeTpl?.title ?: "Custom Discussion Configuration",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            ),
                                            color = cc.textPrimary
                                        )

                                        if (activeTpl != null) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = cc.accent.copy(alpha = 0.12f),
                                                border = BorderStroke(0.6.dp, cc.accent.copy(alpha = 0.35f))
                                            ) {
                                                Text(
                                                    activeTpl.badgeLabel.uppercase(),
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 9.5.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        letterSpacing = 0.4.sp
                                                    ),
                                                    color = cc.accent,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        activeTpl?.subtitle ?: "Custom participant roster, models, and debate rules assembled from scratch.",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = cc.textMuted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(Modifier.width(8.dp))

                            OutlinedButton(
                                onClick = { onIntent(SetupIntent.NavigateToFrontPage) },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.75.dp, cc.border),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = cc.panelAlt,
                                    contentColor = cc.textPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.GridView,
                                    contentDescription = null,
                                    tint = cc.accent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if (activeTpl != null) "Change Template" else "Browse Templates",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = cc.textPrimary
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                }

                // ── Objective / Discussion Topic ─────────────────────────
                val openTopicFilePicker = filePicker.registerPicker { fileName, content ->
                    onIntent(SetupIntent.AttachFile(fileName, content, scope = "topic"))
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Discussion Objective & Topic",
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )

                    Surface(
                        onClick = { onIntent(SetupIntent.RequestProblemDecomposition) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (state.isDecomposing) cc.accent.copy(alpha = 0.2f) else cc.panel,
                        border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (state.isDecomposing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = cc.accent
                                )
                                Text(
                                    "Decomposing...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cc.accent
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = cc.accent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    "⚡ Decompose Problem",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = cc.accent
                                )
                            }
                        }
                    }
                }

                val topicChips = discussion.attachedFiles
                    .filter { it.scope == "topic" }
                    .map { FileChipItem(it.id, it.name, it.tokenEstimateLabel()) }

                SubtleTextArea(
                    value = config.topic,
                    onValueChange = { updateConfig(config.copy(topic = it)) },
                    placeholder = "Ask anything, @ to mention, or describe the topic to evaluate...",
                    minHeight = 115.dp,
                    minLines = 4,
                    onAttachFile = openTopicFilePicker,
                    fileChips = topicChips,
                    onRemoveChip = { fileId -> onIntent(SetupIntent.RemoveFile(fileId)) },
                    cc = cc
                )

                if (config.commonContext.contains("Structured Debate Agenda")) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color(0xFF10B981),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Structured Multi-Perspective Agenda Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF10B981)
                                )
                            }
                            Text(
                                "Review / Re-Decompose",
                                style = MaterialTheme.typography.labelSmall,
                                color = cc.accent,
                                modifier = Modifier.clickable { onIntent(SetupIntent.RequestProblemDecomposition) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))

                // ── Participants & Personas Section ───────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 2.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Participants & Personas",
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    Text(
                        "${config.agents.size} of 6 active · minimum 2 required",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                        color = cc.textMuted
                    )
                }

                val isAllLocal = config.agents.isNotEmpty() && config.agents.all { it.provider == Provider.OLLAMA }
                if (isAllLocal) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0EA5E9).copy(alpha = 0.1f),
                        border = BorderStroke(0.75.dp, Color(0xFF0EA5E9).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF0EA5E9),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                "100% Local & Air-Gapped Deliberation · All participants run locally on Ollama without cloud calls.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                color = Color(0xFF0EA5E9)
                            )
                        }
                    }
                }

                if (state.mode == com.dialex.domain.model.DiscussionMode.SOCRATIC_INTERVIEW) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Socratic Interrogator (Sole Opponent)
                        ParticipantCard(
                            seatIndex = 1,
                            seatLabel = "Socratic Interrogator",
                            agent = config.primary,
                            availablePersonas = state.availablePersonas,
                            availableModels = state.availableModels,
                            configuredApiProviders = state.configuredApiProviders,
                            availableCliProviders = state.availableCliProviders,
                            supportsCli = state.supportsCli,
                            onAgentChange = ::updatePrimary,
                            onOpenPersonaPicker = { onIntent(SetupIntent.OpenPersonaPicker(0)) },
                            filePicker = filePicker,
                            attachedFiles = discussion.attachedFiles,
                            onAttachFile = { fileName, content, scope -> onIntent(SetupIntent.AttachFile(fileName, content, scope)) },
                            onRemoveFile = { fileId -> onIntent(SetupIntent.RemoveFile(fileId)) },
                            onManagePersonas = onManagePersonas,
                            onOpenSettings = { onIntent(SetupIntent.OpenSettings) },
                            onShowToast = showToast
                        )

                        // Socratic Stance & Epistemic Method Card
                        Surface(
                            color = cc.panel,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                "🎯 Epistemic Stance",
                                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                                color = cc.textPrimary
                                            )
                                            Surface(
                                                color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(4.dp),
                                                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.3f))
                                            ) {
                                                Text(
                                                    "Brevis Interrogatio",
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                                    color = Color(0xFFF59E0B)
                                                )
                                            }
                                        }
                                        Text(
                                            "Governs probe style, brevity constraint (≤2 sentences), and concession tracking.",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                            color = cc.textMuted
                                        )
                                    }

                                    Surface(
                                        color = cc.panel,
                                        shape = RoundedCornerShape(6.dp),
                                        border = BorderStroke(0.75.dp, cc.border),
                                        modifier = Modifier.clickable {
                                            onIntent(SetupIntent.AutoSuggestSocraticSetup)
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                Icons.Outlined.AutoAwesome,
                                                contentDescription = null,
                                                tint = Color(0xFFF59E0B),
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                "Auto-Suggest",
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                                color = cc.textPrimary
                                            )
                                        }
                                    }
                                }

                                // 5 Stance Options
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    com.dialex.domain.model.SocraticStance.entries.forEach { stance ->
                                        val isSelected = state.socraticStance == stance
                                        Surface(
                                            color = if (isSelected) Color(0xFFF59E0B).copy(alpha = 0.08f) else cc.panel,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) Color(0xFFF59E0B) else cc.border.copy(alpha = 0.4f)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onIntent(SetupIntent.SelectSocraticStance(stance)) }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = { onIntent(SetupIntent.SelectSocraticStance(stance)) },
                                                    colors = RadioButtonDefaults.colors(
                                                        selectedColor = Color(0xFFF59E0B),
                                                        unselectedColor = cc.textMuted
                                                    ),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        stance.displayName,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                                                        color = if (isSelected) Color(0xFFF59E0B) else cc.textPrimary
                                                    )
                                                    Text(
                                                        stance.subtitle,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                                        color = cc.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Seat 1: Primary Moderator Participant Card
                        ParticipantCard(
                            seatIndex = 1,
                            seatLabel = "Primary Moderator",
                            agent = config.primary,
                            availablePersonas = state.availablePersonas,
                            availableModels = state.availableModels,
                            configuredApiProviders = state.configuredApiProviders,
                            availableCliProviders = state.availableCliProviders,
                            supportsCli = state.supportsCli,
                            onAgentChange = ::updatePrimary,
                            onOpenPersonaPicker = { onIntent(SetupIntent.OpenPersonaPicker(0)) },
                            filePicker = filePicker,
                            attachedFiles = discussion.attachedFiles,
                            onAttachFile = { fileName, content, scope -> onIntent(SetupIntent.AttachFile(fileName, content, scope)) },
                            onRemoveFile = { fileId -> onIntent(SetupIntent.RemoveFile(fileId)) },
                            onManagePersonas = onManagePersonas,
                            onOpenSettings = { onIntent(SetupIntent.OpenSettings) },
                            onShowToast = showToast
                        )

                        // Seats 2-6: Additional Participants
                        additionalAgents.forEachIndexed { idx, agent ->
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                ParticipantCard(
                                    seatIndex = idx + 2,
                                    seatLabel = "Participant ${idx + 2}",
                                    agent = agent,
                                    availablePersonas = state.availablePersonas,
                                    availableModels = state.availableModels,
                                    configuredApiProviders = state.configuredApiProviders,
                                    availableCliProviders = state.availableCliProviders,
                                    supportsCli = state.supportsCli,
                                    onAgentChange = { updated ->
                                        val list = additionalAgents.toMutableList()
                                        list[idx] = updated
                                        updateAdditionalAgents(list)
                                    },
                                    onOpenPersonaPicker = { onIntent(SetupIntent.OpenPersonaPicker(idx + 1)) },
                                    onRemove = {
                                        val list = additionalAgents.toMutableList()
                                        list.removeAt(idx)
                                        updateAdditionalAgents(list)
                                    },
                                    filePicker = filePicker,
                                    attachedFiles = discussion.attachedFiles,
                                    onAttachFile = { fileName, content, scope -> onIntent(SetupIntent.AttachFile(fileName, content, scope)) },
                                    onRemoveFile = { fileId -> onIntent(SetupIntent.RemoveFile(fileId)) },
                                    onManagePersonas = onManagePersonas,
                                    onOpenSettings = { onIntent(SetupIntent.OpenSettings) },
                                    onShowToast = showToast
                                )
                            }
                        }

                        // Add Participant Button
                        if (additionalAgents.size < 5) {
                            Surface(
                                color = cc.panel,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val nextProvider = Provider.entries.firstOrNull { p -> config.agents.none { it.provider == p } } ?: Provider.OPENAI
                                        val newAgent = Agent(
                                            provider = nextProvider,
                                            model = nextProvider.defaultModel(),
                                            runMode = if (state.supportsCli) RunMode.CLI else RunMode.API
                                        )
                                        updateAdditionalAgents(additionalAgents + newAgent)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Add Participant",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                                        color = cc.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))

                // ── Shared Context & Ground Rules ─────────────────────────
                Text(
                    "Shared Context & Constraints",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 10.dp)
                )

                val openSharedContextPicker = filePicker.registerPicker { fileName, content ->
                    onIntent(SetupIntent.AttachFile(fileName, content, scope = "common_context"))
                }

                val openGroundRulesPicker = filePicker.registerPicker { fileName, content ->
                    onIntent(SetupIntent.AttachFile(fileName, content, scope = "common_info"))
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    val contextChips = discussion.attachedFiles
                        .filter { it.scope == "common_context" }
                        .map { FileChipItem(it.id, it.name, it.tokenEstimateLabel()) }

                    // Shared Background Context
                    SubtleTextArea(
                        value = config.commonContext,
                        onValueChange = { updateConfig(config.copy(commonContext = it)) },
                        placeholder = "Shared background context all participants receive as initial setting...",
                        minHeight = 80.dp,
                        minLines = 2,
                        onAttachFile = openSharedContextPicker,
                        fileChips = contextChips,
                        onRemoveChip = { fileId -> onIntent(SetupIntent.RemoveFile(fileId)) },
                        cc = cc
                    )

                    val groundRulesChips = discussion.attachedFiles
                        .filter { it.scope == "common_info" }
                        .map { FileChipItem(it.id, it.name, it.tokenEstimateLabel()) }

                    // Ground Rules & Technical Constraints
                    SubtleTextArea(
                        value = config.commonInfo,
                        onValueChange = { updateConfig(config.copy(commonInfo = it)) },
                        placeholder = "Ground rules, technical constraints, benchmarks, or requirements...",
                        minHeight = 80.dp,
                        minLines = 2,
                        onAttachFile = openGroundRulesPicker,
                        fileChips = groundRulesChips,
                        onRemoveChip = { fileId -> onIntent(SetupIntent.RemoveFile(fileId)) },
                        cc = cc
                    )

                    // Shared Context Folders
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(cc.panel)
                            .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)), RoundedCornerShape(8.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.FolderSpecial, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Shared Context Folders",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                                    color = cc.textPrimary
                                )
                            }

                            ThemedTooltipBox("Add shared context folder") {
                                TextButton(
                                    onClick = { addFolderDialogOpen = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add Folder", tint = cc.textMuted, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add Folder", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                                }
                            }
                        }

                        if (state.invalidFolders.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Workspace Folder Removed or Invalid",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Text(
                                            "A configured folder was removed or cannot be found on disk. Starting the discussion is blocked until you act.",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    TextButton(
                                        onClick = { onIntent(SetupIntent.RetryFolderValidation) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Text("Retry Check", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        if (discussion.attachedFolders.isEmpty()) {
                            Text(
                                "No shared context folders attached yet. Add folders to provide local files/documents as ground context.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = cc.textMuted,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            Spacer(Modifier.height(10.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                discussion.attachedFolders.forEach { folder ->
                                    val invalidMatch = state.invalidFolders.find { it.first.path == folder.path }
                                    val isInvalid = invalidMatch != null

                                    Surface(
                                        color = if (isInvalid) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else cc.panelAlt.copy(alpha = 0.85f),
                                        shape = RoundedCornerShape(6.dp),
                                        border = if (isInvalid) BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)) else null,
                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    if (isInvalid) Icons.Outlined.FolderOff else Icons.Outlined.Folder,
                                                    contentDescription = null,
                                                    tint = if (isInvalid) MaterialTheme.colorScheme.error else cc.textMuted,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Column {
                                                    Text(
                                                        "${folder.path} ${if (folder.isReadOnly) "(Read-Only)" else ""}",
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            fontSize = 13.sp,
                                                            fontWeight = if (isInvalid) FontWeight.SemiBold else FontWeight.Normal
                                                        ),
                                                        color = if (isInvalid) MaterialTheme.colorScheme.error else cc.textPrimary
                                                    )
                                                    if (invalidMatch != null) {
                                                        Text(
                                                            invalidMatch.second,
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                                                            color = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                }
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                ThemedTooltipBox(if (folder.isTrusted) "Workspace is Trusted. Headless CLI tools run smoothly without permission prompts." else "Workspace is Restricted.") {
                                                    Surface(
                                                        onClick = { onIntent(SetupIntent.ToggleFolderTrust(folder.path)) },
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = if (folder.isTrusted) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
                                                        border = BorderStroke(0.5.dp, if (folder.isTrusted) cc.accent.copy(alpha = 0.4f) else cc.border.copy(alpha = 0.5f)),
                                                        modifier = Modifier.padding(end = 6.dp)
                                                    ) {
                                                        Text(
                                                            if (folder.isTrusted) "🛡️ Trusted" else "⚠️ Restricted",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                                                            color = if (folder.isTrusted) cc.accent else cc.textMuted,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                if (isInvalid) {
                                                    TextButton(
                                                        onClick = { onIntent(SetupIntent.RetryFolderValidation) },
                                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                                        modifier = Modifier.height(24.dp)
                                                    ) {
                                                        Text("Retry", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), color = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                                ThemedTooltipBox("Remove folder") {
                                                    IconButton(
                                                        onClick = { onIntent(SetupIntent.RemoveFolder(folder.path)) },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Close,
                                                            contentDescription = "Remove folder",
                                                            tint = if (isInvalid) MaterialTheme.colorScheme.error else cc.textMuted,
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

                Spacer(Modifier.height(32.dp))

                // ── Live Web Search & Grounding ───────────────────
                Text(
                    "Live Web Search & Fact Grounding",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 10.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cc.panel)
                        .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                            Text(
                                "Allow Council Models to Search the Web",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                                color = cc.textPrimary
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "When enabled, Gemini uses Google Search grounding and CLI/API models can browse and fact-check recent knowledge online.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = cc.textMuted
                            )
                        }
                        AestheticSwitch(
                            checked = config.permissions.allowWebSearch,
                            onCheckedChange = { onIntent(SetupIntent.ToggleDiscussionWebSearch(it)) }
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))

                // ── Discussion Rounds & Consensus Rules ───────────────────
                Text(
                    "Discussion Rounds & Consensus Rules",
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary,
                    modifier = Modifier.padding(start = 2.dp, bottom = 10.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cc.panel)
                        .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Fixed vs Unlimited Segmented Pill
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(cc.panelAlt)
                            .padding(2.dp)
                    ) {
                        val isFixed = config.roundMode == RoundMode.FIXED
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (isFixed) cc.panel else Color.Transparent)
                                .clickable { updateConfig(config.copy(roundMode = RoundMode.FIXED)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Fixed Rounds",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    fontWeight = if (isFixed) FontWeight.Medium else FontWeight.Normal,
                                    color = if (isFixed) cc.textPrimary else cc.textMuted
                                )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(if (!isFixed) cc.panel else Color.Transparent)
                                .clickable { updateConfig(config.copy(roundMode = RoundMode.UNLIMITED)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Unlimited (Consensus Driven)",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    fontWeight = if (!isFixed) FontWeight.Medium else FontWeight.Normal,
                                    color = if (!isFixed) cc.textPrimary else cc.textMuted
                                )
                            )
                        }
                    }

                    if (config.roundMode == RoundMode.FIXED) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Maximum Rounds", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp), color = cc.textPrimary)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(cc.panelAlt)
                                    .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)), RoundedCornerShape(6.dp))
                            ) {
                                ThemedTooltipBox("Decrease max rounds") {
                                    IconButton(
                                        onClick = { if (config.maxRounds > 1) updateConfig(config.copy(maxRounds = config.maxRounds - 1)) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "Decrease", tint = cc.textPrimary, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Text(
                                    "${config.maxRounds}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    color = cc.textPrimary
                                )
                                ThemedTooltipBox("Increase max rounds") {
                                    IconButton(
                                        onClick = { if (config.maxRounds < 20) updateConfig(config.copy(maxRounds = config.maxRounds + 1)) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "Increase", tint = cc.textPrimary, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Consensus Tolerance Slider (min consensus is 51%)
                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Consensus Early Stopping",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                                    color = cc.textPrimary
                                )
                                Text(
                                    "Halt deliberation early once council participants reach alignment.",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                                    color = cc.textMuted
                                )
                            }
                            ThemedDropdown(
                                selectedId = config.consensus.mode.name,
                                options = listOf(
                                    ThemedDropdownOption(id = ConsensusMode.UNANIMOUS.name, title = "Unanimous (100%)", subtitle = "Requires 100% of active seats to agree"),
                                    ThemedDropdownOption(id = ConsensusMode.SUPERMAJORITY.name, title = "2/3 Supermajority (66%)", subtitle = "Requires >= 66% of seats to agree"),
                                    ThemedDropdownOption(id = ConsensusMode.SIMPLE_MAJORITY.name, title = "Simple Majority (>50%)", subtitle = "Requires majority of seats to agree"),
                                    ThemedDropdownOption(id = ConsensusMode.DISABLED.name, title = "Disabled (Run Full)", subtitle = "Disable early stopping; run all rounds")
                                ),
                                onSelect = { opt ->
                                    val newMode = try {
                                        ConsensusMode.valueOf(opt.id)
                                    } catch (e: Throwable) {
                                        ConsensusMode.UNANIMOUS
                                    }
                                    val newConfig = config.consensus.copy(mode = newMode)
                                    updateConfig(config.copy(consensus = newConfig))
                                },
                                isMinimal = true,
                                minHeight = 34.dp
                            )
                        }

                        if (config.consensus.mode != ConsensusMode.DISABLED) {
                            // Stepper for Minimum Rounds Before Exit
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Minimum Rounds Before Exit",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                                        color = cc.textPrimary
                                    )
                                    Text(
                                        "Prevents premature 1-round bailout before thorough debate.",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                                        color = cc.textMuted
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            val current = config.consensus.minRoundsBeforeExit
                                            if (current > 1) {
                                                updateConfig(config.copy(consensus = config.consensus.copy(minRoundsBeforeExit = current - 1)))
                                            }
                                        },
                                        enabled = config.consensus.minRoundsBeforeExit > 1,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Remove, contentDescription = "Decrease", tint = cc.textPrimary)
                                    }
                                    Text(
                                        "${config.consensus.minRoundsBeforeExit}",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                                        color = cc.textPrimary
                                    )
                                    IconButton(
                                        onClick = {
                                            val current = config.consensus.minRoundsBeforeExit
                                            if (current < 5) {
                                                updateConfig(config.copy(consensus = config.consensus.copy(minRoundsBeforeExit = current + 1)))
                                            }
                                        },
                                        enabled = config.consensus.minRoundsBeforeExit < 5,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = "Increase", tint = cc.textPrimary)
                                    }
                                }
                            }

                            // Allow Mid-Round Exit Toggle
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Allow Mid-Round Early Exit",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                                        color = cc.textPrimary
                                    )
                                    Text(
                                        "Exit immediately when consensus is satisfied without waiting for remaining agents in round.",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                                        color = cc.textMuted
                                    )
                                }
                                AestheticSwitch(
                                    checked = config.consensus.allowMidRoundTermination,
                                    onCheckedChange = { checked ->
                                        updateConfig(config.copy(consensus = config.consensus.copy(allowMidRoundTermination = checked)))
                                    }
                                )
                            }
                        }
                    }

                    // Human Dialogue Mode Toggle Switch
                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Human-Like Dialogue Mode (Anti-Fluff)", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Forces concise 2–4 sentence turns without robotic filler, headers, or monologues.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = cc.textMuted
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        AestheticSwitch(
                            checked = config.humanDialogueMode,
                            onCheckedChange = { updateConfig(config.copy(humanDialogueMode = it)) }
                        )
                    }

                    // Topic Drift Guardrail (Anti-Rabbit-Hole)
                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Topic Drift Guardrail (Anti-Rabbit-Hole)", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Keeps participants strictly anchored to the core question without digressing into tangents.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = cc.textMuted
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        AestheticSwitch(
                            checked = config.moderation.detectTopicDrift,
                            onCheckedChange = { updateConfig(config.copy(moderation = config.moderation.copy(detectTopicDrift = it))) }
                        )
                    }

                    // Validate Objections Aesthetic Toggle Switch
                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Validate Objections", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Triggers an objection-checking review round before accepting consensus.",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = cc.textMuted
                            )
                        }
                        AestheticSwitch(
                            checked = config.validateObjections,
                            onCheckedChange = { updateConfig(config.copy(validateObjections = it)) }
                        )
                    }
                }

                // ── LEVEL 3: Power-User Drawer (Advanced Engine & Tuning) ──
                AdvancedEngineDrawer(
                    config = config,
                    isExpanded = state.advancedExpanded,
                    onToggleExpand = { onIntent(SetupIntent.ToggleAdvancedDrawer) },
                    onConfigChange = ::updateConfig,
                    activeArchetype = state.activeArchetype,
                    cc = cc
                )

                Spacer(Modifier.height(40.dp))
            }
        }

        // Add Shared Context Folder Dialog
        if (addFolderDialogOpen) {
            AddFolderScopeDialog(
                onDismiss = { addFolderDialogOpen = false },
                onAdd = { path, isReadOnly ->
                    addFolderDialogOpen = false
                    onIntent(SetupIntent.AddFolder(path, isReadOnly))
                }
            )
        }

        // Copy From Dialog
        if (copyFromOpen) {
            PickDiscussionDialog(
                title = "Copy Configuration From",
                options = state.otherDiscussions,
                projects = state.projects,
                onDismiss = { copyFromOpen = false },
                onPick = { disc ->
                    copyFromOpen = false
                    onIntent(SetupIntent.CopySettingsFrom(disc.id))
                }
            )
        }

        // New Project Dialog
        if (newProjectDialogOpen) {
            NameDialog(
                title = "Create New Project",
                onDismiss = { newProjectDialogOpen = false },
                onConfirm = { name ->
                    newProjectDialogOpen = false
                    onIntent(SetupIntent.CreateProject(name))
                }
            )
        }

        // Save Current Configuration as One-Click Preset Dialog
        if (savePresetDialogOpen) {
            SavePresetDialog(
                initialTitle = discussion.name.ifBlank { "Custom Council" },
                onSave = { title, subtitle, badge ->
                    savePresetDialogOpen = false
                    onIntent(SetupIntent.SaveCurrentAsTemplate(title, subtitle, badge))
                },
                onDismiss = { savePresetDialogOpen = false }
            )
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 24.dp)
    )
}
}

// ─────────────────────────────────────────────────────────────────────────────
// High-Accessibility Participant Card with Vertical Accent Division & Zoned Structure
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ParticipantCard(
    seatIndex: Int,
    seatLabel: String,
    agent: Agent,
    availablePersonas: List<PredefinedPersona>,
    availableModels: Map<String, List<String>>,
    configuredApiProviders: Set<Provider>,
    availableCliProviders: Set<Provider>,
    supportsCli: Boolean,
    onAgentChange: (Agent) -> Unit,
    onOpenPersonaPicker: (() -> Unit)? = null,
    filePicker: FilePicker,
    attachedFiles: List<AttachedFile> = emptyList(),
    onAttachFile: ((fileName: String, content: String, scope: String) -> Unit)? = null,
    onRemoveFile: ((fileId: String) -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onManagePersonas: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onShowToast: ((String) -> Unit)? = null,
    isCompact: Boolean = false
) {
    val cc = LocalCcColors.current
    val agentColor = agent.provider.accentColor(cc)
    val providerSupportsCli = com.dialex.service.hasDedicatedCli(agent.provider) ||
        (agent.provider == Provider.CUSTOM && agent.cliCommand?.isNotBlank() == true)

    // Ensure non-CLI providers (e.g. DeepSeek, Grok, Mistral) default to API mode and notify user
    LaunchedEffect(agent.provider) {
        if (!providerSupportsCli && agent.runMode == RunMode.CLI) {
            onAgentChange(agent.copy(runMode = RunMode.API))
            onShowToast?.invoke("${agent.provider.brandName()} doesn't have a CLI tool. Defaulted to API mode.")
        }
    }

    val isApiKeyMissing = agent.runMode == RunMode.API &&
        agent.provider != Provider.CUSTOM &&
        !configuredApiProviders.contains(agent.provider)
    val isCliMissing = agent.runMode == RunMode.CLI && providerSupportsCli &&
        (!supportsCli || !availableCliProviders.contains(agent.provider))
    val hasModeError = (agent.runMode == RunMode.CLI && isCliMissing) ||
        (agent.runMode == RunMode.API && isApiKeyMissing)

    val agentScope = "agent_${agent.provider.name}"
    val openAgentFilePicker = filePicker.registerPicker { fileName, content ->
        onAttachFile?.invoke(fileName, content, agentScope)
    }
    val agentChips = attachedFiles
        .filter { it.scope == agentScope }
        .map { FileChipItem(it.id, it.name, it.tokenEstimateLabel()) }

    var expandedInstructions by remember { mutableStateOf(agent.context.isNotBlank() || agentChips.isNotEmpty()) }
    var expandedParameters by remember { mutableStateOf(agent.temperature != null || agent.topP != null || agent.maxTokens != null) }
    var customModelInputOpen by remember { mutableStateOf(false) }
    var showPersonaEditSheet by remember { mutableStateOf(false) }

    val selectedPersona = availablePersonas.find { it.id == agent.personaId }
        ?: if (agent.personaId?.endsWith("_custom") == true) {
            val baseId = agent.personaId.removeSuffix("_custom")
            availablePersonas.find { it.id == baseId }?.copy(
                name = agent.displayName.ifBlank { "Custom Role" },
                role = agent.role,
                systemPrompt = agent.systemPrompt,
                ponytail = agent.ponytail
            ) ?: PredefinedPersona(
                id = agent.personaId,
                name = agent.displayName.ifBlank { "Custom Role" },
                role = agent.role,
                systemPrompt = agent.systemPrompt,
                category = "Custom",
                description = "Custom role configured for this debate"
            )
        } else if (agent.role.isNotBlank() || agent.systemPrompt.isNotBlank()) {
            PredefinedPersona(
                id = "custom_$seatIndex",
                name = agent.displayName.ifBlank { "Custom Role" },
                role = agent.role,
                systemPrompt = agent.systemPrompt,
                category = "Custom",
                description = "Custom role configured for this debate"
            )
        } else null

    val isCustomised = agent.personaId?.endsWith("_custom") == true ||
        agent.displayName.endsWith("(Custom)") ||
        (agent.systemPrompt.isNotBlank() && agent.personaId == null)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cc.panel),
        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        // ── Card Content ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isCompact) 14.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Division 1: Semantic Identity & Mode Header ────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(agentColor)
                    )

                    Text(
                        text = "Agent $seatIndex · ${agent.displayName.ifBlank { agent.provider.brandName() }}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp
                        ),
                        color = cc.textPrimary
                    )

                    Text(
                        text = "($seatLabel)",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = cc.textMuted
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Mode Toggle (CLI vs API) - only for providers that support CLI
                    if (supportsCli && providerSupportsCli) {
                        val tooltipText = if (agent.runMode == RunMode.CLI) {
                            if (isCliMissing) "CLI tool is not available on engine system for ${agent.provider.brandName()} — click to switch to API Mode"
                            else "Switch to API Mode"
                        } else {
                            if (isApiKeyMissing) "API key missing for ${agent.provider.brandName()} — click to switch to CLI Mode"
                            else "Switch to CLI Mode"
                        }

                        ThemedTooltipBox(tooltipText) {
                            Surface(
                                color = if (hasModeError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                else cc.panelAlt,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(
                                    0.75.dp,
                                    if (hasModeError) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                    else cc.border.copy(alpha = 0.6f)
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        onAgentChange(agent.copy(runMode = if (agent.runMode == RunMode.CLI) RunMode.API else RunMode.CLI))
                                    }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    if (hasModeError) {
                                        Icon(
                                            Icons.Outlined.WarningAmber,
                                            contentDescription = "Configuration error",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                    Text(
                                        if (agent.runMode == RunMode.CLI) "CLI Mode" else "API Mode",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = if (hasModeError) MaterialTheme.colorScheme.error
                                        else cc.textPrimary
                                    )
                                }
                            }
                        }
                    }

                    if (onRemove != null) {
                        ThemedTooltipBox("Remove Participant") {
                            IconButton(
                                onClick = onRemove,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove Agent $seatIndex",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            val providerOptions = remember {
                Provider.entries.map { p ->
                    ThemedDropdownOption(id = p.name, title = p.brandName())
                }
            }

            val providerModels = (availableModels[agent.provider.name] ?: agent.provider.knownModels())
            val modelOptions = remember(agent.provider, agent.model, providerModels) {
                buildList {
                    providerModels.forEach { m ->
                        add(ThemedDropdownOption(id = m, title = m))
                    }
                    if (agent.model.isNotBlank() && !providerModels.contains(agent.model)) {
                        add(0, ThemedDropdownOption(id = agent.model, title = agent.model))
                    }
                    add(ThemedDropdownOption(id = "__custom__", title = "+ Custom Model...", isDividerBefore = true, isAccent = true))
                }
            }

            // ── Division 2: Provider & Model (Subtle Greyish Section) ───────────────
            Surface(
                color = cc.panelAlt,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Provider Column
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "Provider",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    letterSpacing = 0.2.sp
                                ),
                                color = cc.textMuted.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 6.dp)
                            )
                            ThemedDropdown(
                                selectedId = agent.provider.name,
                                options = providerOptions,
                                onSelect = { opt ->
                                    val p = Provider.valueOf(opt.id)
                                    val hasCli = com.dialex.service.hasDedicatedCli(p) || (p == Provider.CUSTOM && agent.cliCommand?.isNotBlank() == true)
                                    val targetRunMode = if (!hasCli && agent.runMode == RunMode.CLI) {
                                        onShowToast?.invoke("${p.brandName()} doesn't have a CLI tool. Defaulted to API mode.")
                                        RunMode.API
                                    } else {
                                        agent.runMode
                                    }
                                    onAgentChange(agent.copy(
                                        provider = p,
                                        model = if (p == Provider.CUSTOM) "" else p.defaultModel(),
                                        runMode = targetRunMode
                                    ))
                                },
                                isMinimal = true,
                                containerColor = Color.Transparent,
                                borderColor = null,
                                fontSize = 12.5.sp,
                                minHeight = 36.dp,
                                isCompact = isCompact
                            )
                        }

                        // Model Column
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "Model",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal,
                                    letterSpacing = 0.2.sp
                                ),
                                color = cc.textMuted.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 6.dp)
                            )
                            ThemedDropdown(
                                selectedId = agent.model.ifBlank { null },
                                options = modelOptions,
                                onSelect = { opt ->
                                    if (opt.id == "__custom__") {
                                        customModelInputOpen = true
                                    } else {
                                        onAgentChange(agent.copy(model = opt.id))
                                    }
                                },
                                placeholder = "Select Model...",
                                isMinimal = true,
                                containerColor = Color.Transparent,
                                borderColor = null,
                                fontSize = 12.5.sp,
                                minHeight = 36.dp,
                                isCompact = isCompact
                            )
                        }
                    }

                    // Custom Model input row if selected
                    if (customModelInputOpen) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = agent.model,
                                onValueChange = { onAgentChange(agent.copy(model = it)) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cc.textPrimary, fontSize = 12.5.sp),
                                cursorBrush = SolidColor(cc.textPrimary),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(cc.panel)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                decorationBox = { innerTextField ->
                                    if (agent.model.isEmpty()) {
                                        Text("Type custom model identifier...", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp), color = cc.textMuted.copy(alpha = 0.6f))
                                    }
                                    innerTextField()
                                }
                            )
                            ThemedTooltipBox("Confirm custom model") {
                                IconButton(onClick = { customModelInputOpen = false }, modifier = Modifier.size(22.dp)) {
                                    Icon(Icons.Default.Check, contentDescription = "Done", tint = cc.textPrimary, modifier = Modifier.size(13.dp))
                                }
                            }
                        }
                    }
                }
            }

            // ── API Key Missing Warning Banner ──────────────────────────────
            if (isApiKeyMissing) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.85.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.KeyOff,
                                contentDescription = "API key missing",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(17.dp)
                            )
                            Column {
                                Text(
                                    text = "API key hasn't been set for ${agent.provider.brandName()}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Configure your API key in Settings to run this agent in API mode.",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                        }

                        if (onOpenSettings != null) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.error,
                                shape = RoundedCornerShape(5.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(5.dp))
                                    .clickable { onOpenSettings() }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Settings,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Set API Key",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.5.sp
                                        ),
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── CLI Tool Missing Warning Banner (Only for CLI-supported providers) ──
            if (supportsCli && providerSupportsCli && isCliMissing) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.85.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = "CLI tool missing",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(17.dp)
                            )
                            Column {
                                Text(
                                    text = "CLI is not available on engine system for ${agent.provider.brandName()}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = if (!supportsCli) "The connected engine platform does not support CLI tools. Please switch to API mode."
                                    else "The CLI tool is not installed or not on the engine host PATH.",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                                )
                            }
                        }

                        Surface(
                            color = cc.panelAlt,
                            border = BorderStroke(0.75.dp, cc.border),
                            shape = RoundedCornerShape(percent = 50),
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .clickable { onAgentChange(agent.copy(runMode = RunMode.API)) }
                        ) {
                            Text(
                                text = "Use API Mode",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 10.5.sp
                                ),
                                color = cc.textPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // ── Division 3: Left-Bottom Persona Label with Icon & Extra Info Toggle ─
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left bottom: PersonaBadge and Toggles
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    PersonaBadge(
                        persona = selectedPersona,
                        customName = if (isCustomised) agent.displayName else null,
                        isCustomised = isCustomised,
                        accentColor = agentColor,
                        onEdit = {
                            if (selectedPersona != null) {
                                showPersonaEditSheet = true
                            } else {
                                onOpenPersonaPicker?.invoke()
                            }
                        },
                        onChange = if (selectedPersona != null) { { onOpenPersonaPicker?.invoke() } } else null,
                        onClear = {
                            onAgentChange(agent.copy(
                                personaId = null,
                                role = "",
                                systemPrompt = "",
                                ponytail = false,
                                displayName = "Agent $seatIndex"
                            ))
                        }
                    )

                    // Web Search Toggle Chip
                    val isWebSearchActive = agent.allowWebSearch ?: true
                    ThemedTooltipBox(if (isWebSearchActive) "Live web search enabled for this seat" else "Web search disabled for this seat") {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isWebSearchActive) cc.accent.copy(alpha = 0.14f) else cc.panelAlt,
                            border = BorderStroke(0.75.dp, if (isWebSearchActive) cc.accent else cc.border.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onAgentChange(agent.copy(allowWebSearch = !isWebSearchActive)) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    if (isWebSearchActive) "🌐 Web" else "🌐 No Web",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (isWebSearchActive) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (isWebSearchActive) cc.accent else cc.textMuted
                                )
                            }
                        }
                    }
                }

                // Right bottom: Extra info (Directives & Context) and Parameters toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val hasCustomParams = agent.temperature != null || agent.topP != null || agent.maxTokens != null

                    // Parameters Toggle Button
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (expandedParameters) cc.panelAlt else Color.Transparent,
                        border = BorderStroke(
                            0.75.dp,
                            if (expandedParameters) cc.accent.copy(alpha = 0.5f)
                            else if (hasCustomParams) cc.accent.copy(alpha = 0.35f)
                            else cc.border.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expandedParameters = !expandedParameters }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = null,
                                tint = if (hasCustomParams || expandedParameters) cc.accent else cc.textMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (hasCustomParams) "Params (Custom)" else "Sampling",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = if (hasCustomParams || expandedParameters) FontWeight.Medium else FontWeight.Normal
                                ),
                                color = if (hasCustomParams || expandedParameters) cc.textPrimary else cc.textMuted
                            )
                        }
                    }

                    // Directives Toggle Button
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (expandedInstructions) cc.panelAlt else Color.Transparent,
                        border = BorderStroke(
                            0.75.dp,
                            if (expandedInstructions) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { expandedInstructions = !expandedInstructions }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                if (expandedInstructions) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = cc.textMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (expandedInstructions) "Hide Context" else "Context",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = if (expandedInstructions) FontWeight.Medium else FontWeight.Normal
                                ),
                                color = if (expandedInstructions) cc.textPrimary else cc.textMuted
                            )
                        }
                    }
                }
            }

            // ── Division 4: Individual Extra Context & Files (Expandable) ──────────
            AnimatedVisibility(
                visible = expandedInstructions,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Extra Context & Files
                    SubtleTextArea(
                        value = agent.context,
                        onValueChange = { onAgentChange(agent.copy(context = it)) },
                        placeholder = "Extra context known only to this participant...",
                        minHeight = 65.dp,
                        minLines = 2,
                        onAttachFile = openAgentFilePicker,
                        fileChips = agentChips,
                        onRemoveChip = onRemoveFile
                    )
                }
            }

            // ── Division 5: Sampling & Model Parameters (Expandable) ───────────
            AnimatedVisibility(
                visible = expandedParameters,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    color = cc.panelAlt.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val hasCustomParams = agent.temperature != null || agent.topP != null || agent.maxTokens != null
                        val currentTemp = agent.temperature ?: 0.7
                        val currentTopP = agent.topP ?: 0.95
                        val currentMaxTokens = agent.maxTokens ?: 4096

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Tune,
                                    contentDescription = null,
                                    tint = cc.accent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    "Model Parameters & Sampling",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = cc.textPrimary
                                )
                            }

                            if (hasCustomParams) {
                                Text(
                                    "Reset to Global Defaults",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = cc.accent
                                    ),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            onAgentChange(agent.copy(temperature = null, topP = null, maxTokens = null))
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            } else {
                                Text(
                                    "Inheriting Global Defaults",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        color = cc.textMuted
                                    )
                                )
                            }
                        }

                        // Temperature Slider
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Temperature",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = cc.textPrimary
                                )
                                val tempFormatted = ((currentTemp * 100).roundToInt() / 100.0).toString()
                                val tempNote = when {
                                    currentTemp < 0.35 -> "Deterministic"
                                    currentTemp < 0.85 -> "Balanced"
                                    else -> "Creative"
                                }
                                Text(
                                    "$tempFormatted ($tempNote)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = cc.accent
                                )
                            }
                            AestheticSlider(
                                value = currentTemp.toFloat(),
                                onValueChange = { newTemp ->
                                    val rounded = (newTemp * 20).roundToInt() / 20.0
                                    onAgentChange(agent.copy(temperature = rounded))
                                },
                                valueRange = 0.0f..2.0f
                            )
                        }

                        // Top-P Slider
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Top-P (Nucleus Sampling)",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = cc.textPrimary
                                )
                                val topPFormatted = ((currentTopP * 100).roundToInt() / 100.0).toString()
                                Text(
                                    topPFormatted,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = cc.accent
                                )
                            }
                            AestheticSlider(
                                value = currentTopP.toFloat(),
                                onValueChange = { newTopP ->
                                    val rounded = (newTopP * 20).roundToInt() / 20.0
                                    onAgentChange(agent.copy(topP = rounded))
                                },
                                valueRange = 0.0f..1.0f
                            )
                        }

                        // Max Output Tokens
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Max Output Tokens",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = cc.textPrimary
                                )
                                Text(
                                    "$currentMaxTokens tokens",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = cc.accent
                                )
                            }

                            // Preset chips for quick token selection
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val tokenPresets = listOf(1024, 2048, 4096, 8192, 16384)
                                tokenPresets.forEach { preset ->
                                    val isSelected = currentMaxTokens == preset
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isSelected) cc.accent.copy(alpha = 0.15f) else cc.panel,
                                        border = BorderStroke(
                                            0.5.dp,
                                            if (isSelected) cc.accent else cc.border.copy(alpha = 0.4f)
                                        ),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .clickable { onAgentChange(agent.copy(maxTokens = preset)) }
                                    ) {
                                        Text(
                                            text = if (preset >= 1024) "${preset / 1024}k" else "$preset",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.5.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) cc.accent else cc.textPrimary,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
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

    if (showPersonaEditSheet) {
        val baseForSheet = selectedPersona

        PersonaEditSheet(
            basePersona = baseForSheet,
            onApply = { result ->
                showPersonaEditSheet = false
                when (result) {
                    is PersonaSelectionResult.None -> onAgentChange(agent.copy(
                        personaId = null,
                        role = "",
                        systemPrompt = "",
                        ponytail = false,
                        displayName = "Agent $seatIndex"
                    ))
                    is PersonaSelectionResult.Stock -> onAgentChange(agent.copy(
                        personaId = result.persona.id,
                        role = result.persona.role,
                        ponytail = result.persona.ponytail,
                        systemPrompt = "",
                        displayName = result.persona.name
                    ))
                    is PersonaSelectionResult.Custom -> onAgentChange(agent.copy(
                        personaId = "${result.basePersonaId}_custom",
                        role = result.role,
                        ponytail = result.ponytail,
                        systemPrompt = result.systemPrompt,
                        displayName = if (result.displayName.endsWith("(Custom)")) result.displayName else "${result.displayName} (Custom)"
                    ))
                }
            },
            onDismiss = { showPersonaEditSheet = false }
        )
    }
}

/** Modifier helper for intrinsic height on row layouts */
private fun Modifier.intrinsicHeight(): Modifier = this.height(IntrinsicSize.Min)

@Composable
private fun AddFolderScopeDialog(
    onDismiss: () -> Unit,
    onAdd: (path: String, isReadOnly: Boolean) -> Unit
) {
    val cc = LocalCcColors.current
    var path by remember { mutableStateOf("") }
    var isReadOnly by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cc.panel)
                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
                .padding(22.dp)
        ) {
            Text(
                "Add Shared Context Folder",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                color = cc.textPrimary
            )
            Spacer(Modifier.height(14.dp))

            Text("Directory Path", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
            Spacer(Modifier.height(6.dp))
            BasicTextField(
                value = path,
                onValueChange = { path = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = cc.textPrimary, fontSize = 13.5.sp),
                cursorBrush = SolidColor(cc.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(cc.panelAlt)
                    .padding(10.dp),
                decorationBox = { innerTextField ->
                    if (path.isEmpty()) {
                        Text("e.g. /Users/project/docs", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp), color = cc.textMuted.copy(alpha = 0.75f))
                    }
                    innerTextField()
                }
            )

            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isReadOnly,
                    onCheckedChange = { isReadOnly = it },
                    colors = CheckboxDefaults.colors(checkedColor = cc.accent)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Read-Only (Participants can inspect, but not modify files)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = cc.textPrimary
                )
            }

            Spacer(Modifier.height(20.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                }
                Spacer(Modifier.width(8.dp))
                GradientButton(
                    text = "Add Folder",
                    onClick = { if (path.isNotBlank()) onAdd(path.trim(), isReadOnly) },
                    enabled = path.isNotBlank()
                )
            }
        }
    }
}

@Composable
internal fun PickDiscussionDialog(
    title: String,
    options: List<Discussion>,
    projects: List<com.dialex.model.Project> = emptyList(),
    onDismiss: () -> Unit,
    onPick: (Discussion) -> Unit,
) {
    val cc = LocalCcColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cc.panel)
                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)), RoundedCornerShape(12.dp))
                .padding(22.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp), color = cc.textPrimary)
            Spacer(Modifier.height(14.dp))
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                options.forEach { d ->
                    val projName = projects.find { it.id == d.projectId }?.name
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(cc.panelAlt)
                            .clickable { onPick(d) }
                            .padding(12.dp)
                    ) {
                        Text(d.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp), color = cc.textPrimary)
                        Spacer(Modifier.height(2.dp))
                        val meta = buildString {
                            if (!projName.isNullOrBlank()) {
                                append(projName)
                                append(" · ")
                            }
                            append("${d.config.agents.size} participants · ${d.config.roundMode.name.lowercase()}")
                        }
                        Text(meta, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = cc.textMuted)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Deliberation Depth & Cognitive Load (TASK-04)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeliberationDepthSection(
    config: DebateConfig,
    onConfigChange: (DebateConfig) -> Unit,
    cc: com.dialex.theme.CcPalette,
    modifier: Modifier = Modifier
) {
    val depth = config.depth
    var showCustomSettings by remember { mutableStateOf(depth.mode == com.dialex.model.DepthMode.CUSTOM) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cc.panel)
            .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Deliberation Depth & Audience",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                color = cc.textPrimary
            )
            Text(
                "Adapts vocabulary, structural rigor, and word counts across all agents to fit target reading level and time constraints.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = cc.textMuted
            )
        }

        // 3-Tier Mode Card Selector
        val modes = listOf(
            Triple(
                com.dialex.model.DepthMode.CASUAL,
                "⚡ CASUAL",
                listOf("Quick Take & Everyday Language", "• Strictly plain English (no LaTeX/math)", "• 1–2 Rounds (~30s)", "• ~150 words per turn")
            ),
            Triple(
                com.dialex.model.DepthMode.EXECUTIVE,
                "💼 EXECUTIVE",
                listOf("Strategic Decision & Actionable", "• Pragmatic ROI, risks & trade-off table", "• 2–3 Rounds (~2m)", "• ~350 words per turn")
            ),
            Triple(
                com.dialex.model.DepthMode.ACADEMIC,
                "🔬 ACADEMIC",
                listOf("First-Principles & Theory", "• Formal derivations, limits & LaTeX", "• 4–5 Rounds (~6m)", "• Up to 750 words per turn")
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            modes.forEach { (mode, title, details) ->
                val isSelected = depth.mode == mode
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
                    border = BorderStroke(
                        if (isSelected) 1.5.dp else 0.75.dp,
                        if (isSelected) cc.accent else cc.border.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val preset = com.dialex.model.DepthConfig.preset(mode)
                            onConfigChange(
                                config.copy(
                                    depth = preset,
                                    maxRounds = preset.recommendedRounds
                                )
                            )
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp
                            ),
                            color = if (isSelected) cc.accent else cc.textPrimary
                        )
                        Text(
                            details.first(),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 11.5.sp
                            ),
                            color = cc.textPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        details.drop(1).forEach { bullet ->
                            Text(
                                bullet,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }
                }
            }
        }

        // Advanced / Custom toggle
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { showCustomSettings = !showCustomSettings }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                if (showCustomSettings) Icons.Default.ArrowDropDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = cc.accent,
                modifier = Modifier.size(16.dp)
            )
            Text(
                "Advanced Custom Depth Settings...",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                ),
                color = cc.accent
            )
        }

        if (showCustomSettings) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(cc.panelAlt)
                    .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Target word count
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Target Word Count Per Turn",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = cc.textPrimary
                        )
                        Text(
                            "Limits maximum generated length per agent turn (~${depth.targetWordCountPerTurn} words)",
                            style = MaterialTheme.typography.labelSmall,
                            color = cc.textMuted
                        )
                    }
                    Text(
                        "${depth.targetWordCountPerTurn} words",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        color = cc.textPrimary
                    )
                }
                AestheticSlider(
                    value = depth.targetWordCountPerTurn.toFloat().coerceIn(100f, 1000f),
                    onValueChange = {
                        onConfigChange(config.copy(depth = depth.copy(mode = com.dialex.model.DepthMode.CUSTOM, targetWordCountPerTurn = it.roundToInt())))
                    },
                    valueRange = 100f..1000f,
                    modifier = Modifier.fillMaxWidth().height(22.dp)
                )

                HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.5.dp)

                // Math & LaTeX formulas
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Allow Math & LaTeX Formulas",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = cc.textPrimary
                        )
                        Text(
                            "Permits mathematical syntax, equations, and LaTeX delimiters ($...$, $$...$$)",
                            style = MaterialTheme.typography.labelSmall,
                            color = cc.textMuted
                        )
                    }
                    AestheticSwitch(
                        checked = depth.allowMathFormulas,
                        onCheckedChange = {
                            onConfigChange(config.copy(depth = depth.copy(mode = com.dialex.model.DepthMode.CUSTOM, allowMathFormulas = it)))
                        }
                    )
                }

                // Academic Jargon
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Permit Specialized Academic Jargon",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = cc.textPrimary
                        )
                        Text(
                            "Allows domain-specific terminology without mandatory lay explanations",
                            style = MaterialTheme.typography.labelSmall,
                            color = cc.textMuted
                        )
                    }
                    AestheticSwitch(
                        checked = depth.allowAcademicJargon,
                        onCheckedChange = {
                            onConfigChange(config.copy(depth = depth.copy(mode = com.dialex.model.DepthMode.CUSTOM, allowAcademicJargon = it)))
                        }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Feature 1: Token Strategy & Shared Memory
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TokenStrategySection(
    config: DebateConfig,
    onConfigChange: (DebateConfig) -> Unit,
    cc: com.dialex.theme.CcPalette,
    modifier: Modifier = Modifier
) {
    val mem = config.sharedMemory
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cc.panel)
            .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Shared Memory Compression",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Maintains a rolling summary of debate knowledge instead of passing the entire transcript history, saving 60–80% tokens on long debates.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = cc.textMuted
                )
            }
            Spacer(Modifier.width(16.dp))
            AestheticSwitch(
                checked = mem.enabled,
                onCheckedChange = { onConfigChange(config.copy(sharedMemory = mem.copy(enabled = it))) }
            )
        }

        if (mem.enabled) {
            HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Summarization Model",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ModelSource.COMPACTION_MODEL to "Compaction Model (Default)",
                        ModelSource.MODERATOR_MODEL to "Moderator Model",
                        ModelSource.CUSTOM to "Custom Model"
                    ).forEach { (src, label) ->
                        val selected = mem.modelSource == src
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
                            border = BorderStroke(0.75.dp, if (selected) cc.accent else cc.border.copy(alpha = 0.45f)),
                            modifier = Modifier.weight(1f).clickable {
                                onConfigChange(config.copy(sharedMemory = mem.copy(modelSource = src)))
                            }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (selected) cc.accent else cc.textPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Anchor Round 1 Verbatim",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    Text(
                        "Always passes initial opening arguments in full to anchor topic grounding.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = cc.textMuted
                    )
                }
                AestheticSwitch(
                    checked = mem.includeFullRound1,
                    onCheckedChange = { onConfigChange(config.copy(sharedMemory = mem.copy(includeFullRound1 = it))) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Include Agent's Own Prior Turn",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    Text(
                        "Ensures each agent remembers their own exact statements for coherent rebuttals.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = cc.textMuted
                    )
                }
                AestheticSwitch(
                    checked = mem.includeOwnLastTurn,
                    onCheckedChange = { onConfigChange(config.copy(sharedMemory = mem.copy(includeOwnLastTurn = it))) }
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Max Summary Tokens Cap",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    val formattedTokens = if (mem.maxSummaryTokens >= 1000) {
                        val k = (mem.maxSummaryTokens / 100) / 10.0
                        "${k}k tokens"
                    } else "${mem.maxSummaryTokens} tokens"
                    Text(
                        formattedTokens,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        color = cc.textPrimary
                    )
                }
                AestheticSlider(
                    value = mem.maxSummaryTokens.toFloat().coerceIn(300f, 10000f),
                    onValueChange = { onConfigChange(config.copy(sharedMemory = mem.copy(maxSummaryTokens = it.roundToInt()))) },
                    valueRange = 300f..10000f,
                    modifier = Modifier.fillMaxWidth().height(22.dp)
                )
                // Quick preset pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(600 to "600", 1500 to "1.5k", 3000 to "3k", 6000 to "6k", 10000 to "10k (Max)").forEach { (tok, lbl) ->
                        val isSel = mem.maxSummaryTokens == tok
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = if (isSel) cc.accent.copy(alpha = 0.15f) else cc.panelAlt,
                            border = BorderStroke(0.6.dp, if (isSel) cc.accent else cc.border.copy(alpha = 0.4f)),
                            modifier = Modifier.clip(RoundedCornerShape(percent = 50)).clickable {
                                onConfigChange(config.copy(sharedMemory = mem.copy(maxSummaryTokens = tok)))
                            }
                        ) {
                            Text(
                                lbl,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal),
                                color = if (isSel) cc.accent else cc.textMuted,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }

        // Dynamic Token Compaction Trigger (TASK-03 & TASK-08)
        val costEff = config.costEfficiency
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Memory Compaction Frequency",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    ThemedTooltipBox("How often older messages are compressed into a summary to keep costs low and responses fast.") {
                        Icon(Icons.Outlined.Info, contentDescription = "Memory Compaction tooltip", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                    }
                }
                Text(
                    "${costEff.triggerTokenThreshold / 1000}k tokens threshold",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                    color = cc.accent
                )
            }
            AestheticSlider(
                value = costEff.triggerTokenThreshold.toFloat().coerceIn(2000f, 16000f),
                onValueChange = { onConfigChange(config.copy(costEfficiency = costEff.copy(triggerTokenThreshold = it.roundToInt()))) },
                valueRange = 2000f..16000f,
                modifier = Modifier.fillMaxWidth().height(22.dp)
            )
        }

        HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

        // Token Limit Action Policy
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Token Limit Ceiling Policy",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when (config.tokenBudgetAction) {
                            com.dialex.model.TokenBudgetAction.WARNING ->
                                "Warning Alert: Shows progressive warning levels when reaching the token limit without abruptly halting deliberation."
                            com.dialex.model.TokenBudgetAction.HARD_STOP ->
                                "Hard Stop: Immediately halts deliberation when reaching the token budget ceiling to strictly cap spend."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = cc.textMuted
                    )
                }
                Spacer(Modifier.width(16.dp))
                SubtleSegmentedControl(
                    options = listOf(
                        com.dialex.model.TokenBudgetAction.WARNING to "Warning (Default)",
                        com.dialex.model.TokenBudgetAction.HARD_STOP to "Hard Stop"
                    ),
                    selected = config.tokenBudgetAction,
                    onSelect = { onConfigChange(config.copy(tokenBudgetAction = it)) }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Feature 2: Moderation & Dialectic Steerage (TASK-06)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModerationSection(
    config: DebateConfig,
    onConfigChange: (DebateConfig) -> Unit,
    cc: com.dialex.theme.CcPalette,
    modifier: Modifier = Modifier
) {
    val mod = config.moderation
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cc.panel)
            .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Dynamic Moderator & Dialectic Steerage",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Employs an active AI Moderator Chair to break circular loops, enforce topical fidelity via semantic drift detection, and guide dialectic convergence.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = cc.textMuted
                )
            }
            Spacer(Modifier.width(16.dp))
            AestheticSwitch(
                checked = mod.enabled,
                onCheckedChange = { onConfigChange(config.copy(moderation = mod.copy(enabled = it))) }
            )
        }

        if (mod.enabled) {
            HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

            // Moderation Style Selection
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Moderation Style",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val styles = listOf(
                        com.dialex.model.ModerationStyle.DYNAMIC_ACTIVE_STEERAGE to "Dynamic Drift",
                        com.dialex.model.ModerationStyle.PERIODIC_CHECKPOINT to "Checkpoints",
                        com.dialex.model.ModerationStyle.STRICT_ARBITRATION to "Strict Arbiter",
                        com.dialex.model.ModerationStyle.PASSIVE_WRAPUP_ONLY to "Wrap-Up Only"
                    )
                    styles.forEach { (st, label) ->
                        val isSelected = mod.style == st
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
                            border = BorderStroke(0.75.dp, if (isSelected) cc.accent else cc.border.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onConfigChange(config.copy(moderation = mod.copy(style = st))) }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) cc.accent else cc.textPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Moderator Persona Selection
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Council Moderator Persona",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val personas = listOf(
                        com.dialex.model.ModeratorPersona.DELIBERATION_CHAIR to "Deliberation Chair",
                        com.dialex.model.ModeratorPersona.EXECUTIVE_ARBITER to "Executive Arbiter",
                        com.dialex.model.ModeratorPersona.SOCRATIC_PROBE to "Socratic Inquirer",
                        com.dialex.model.ModeratorPersona.DEVILS_ADVOCATE_CHAIR to "Devil's Advocate"
                    )
                    personas.forEach { (pers, label) ->
                        val isSelected = mod.persona == pers
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
                            border = BorderStroke(0.75.dp, if (isSelected) cc.accent else cc.border.copy(alpha = 0.45f)),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onConfigChange(config.copy(moderation = mod.copy(persona = pers))) }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (isSelected) cc.accent else cc.textPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Steerage Directive Injection Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Enforce Steerage Directives in Next Turn",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    Text(
                        "Injects moderator's focus questions directly into agents' next prompt as mandatory instructions.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = cc.textMuted
                    )
                }
                AestheticSwitch(
                    checked = mod.enforceSteerageDirectives,
                    onCheckedChange = { onConfigChange(config.copy(moderation = mod.copy(enforceSteerageDirectives = it))) }
                )
            }

            // Semantic Drift Threshold Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Topical Drift Guard",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = cc.textPrimary
                        )
                        ThemedTooltipBox("Controls how strictly the Moderator steps in if participants start wandering off into unrelated tangents.") {
                            Icon(Icons.Outlined.Info, contentDescription = "Topical Drift Guard tooltip", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                        }
                    }
                    Text(
                        "${(mod.driftThreshold * 100).toInt()}% minimum topicality",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                        color = cc.textPrimary
                    )
                }
                AestheticSlider(
                    value = mod.driftThreshold.toFloat().coerceIn(0.2f, 0.8f),
                    onValueChange = {
                        onConfigChange(config.copy(moderation = mod.copy(driftThreshold = (it * 100).roundToInt() / 100.0)))
                    },
                    valueRange = 0.2f..0.8f,
                    modifier = Modifier.fillMaxWidth().height(22.dp)
                )
            }

            // Checkpoint Frequency
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Periodic Checkpoint Frequency",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    Text(
                        "Every ${mod.checkpointFrequencyRounds} rounds",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                        color = cc.textPrimary
                    )
                }
                AestheticSlider(
                    value = mod.checkpointFrequencyRounds.toFloat().coerceIn(1f, 5f),
                    onValueChange = {
                        onConfigChange(config.copy(moderation = mod.copy(checkpointFrequencyRounds = it.roundToInt())))
                    },
                    valueRange = 1f..5f,
                    modifier = Modifier.fillMaxWidth().height(22.dp)
                )
            }

            // Custom Directives
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Custom Moderator Directives (Optional)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary
                )
                BasicTextField(
                    value = mod.customDirectives,
                    onValueChange = { onConfigChange(config.copy(moderation = mod.copy(customDirectives = it))) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary, lineHeight = 18.sp),
                    cursorBrush = SolidColor(cc.accent),
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(cc.panelAlt)
                        .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)), RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    decorationBox = { inner ->
                        if (mod.customDirectives.isBlank()) {
                            Text(
                                "e.g. Act as a critical inquisitor. Prioritize pragmatic implementation hurdles over abstract philosophy...",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = cc.textMuted.copy(alpha = 0.6f)
                            )
                        }
                        inner()
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Feature 3: Conclusion & Deliverable
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeliverableSection(
    config: DebateConfig,
    onConfigChange: (DebateConfig) -> Unit,
    cc: com.dialex.theme.CcPalette,
    modifier: Modifier = Modifier
) {
    val del = config.deliverable
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cc.panel)
            .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text(
                "Deliverable Format",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                color = cc.textPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Select the primary actionable artifact produced upon conclusion (recap summary is always viewable in the summary panel).",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                color = cc.textMuted
            )
        }

        // Format grid / chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                DeliverableFormat.DECISION_SUMMARY to "Decision Summary",
                DeliverableFormat.ACTION_PLAN to "Action Plan",
                DeliverableFormat.DECISION_MATRIX to "Decision Matrix",
            ).forEach { (fmt, label) ->
                val selected = del.format == fmt
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
                    border = BorderStroke(0.75.dp, if (selected) cc.accent else cc.border.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onConfigChange(config.copy(deliverable = del.copy(format = fmt))) }
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (selected) cc.accent else cc.textPrimary
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                DeliverableFormat.PRO_CON_LIST to "Pro / Con List",
                DeliverableFormat.EXECUTIVE_BRIEF to "Executive Brief",
                DeliverableFormat.CUSTOM to "Custom Prompt",
            ).forEach { (fmt, label) ->
                val selected = del.format == fmt
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
                    border = BorderStroke(0.75.dp, if (selected) cc.accent else cc.border.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onConfigChange(config.copy(deliverable = del.copy(format = fmt))) }
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (selected) cc.accent else cc.textPrimary
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Conclusion & Synthesis Model",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = cc.textPrimary
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    ModelSource.COMPACTION_MODEL to "Compaction Model (Default)",
                    ModelSource.MODERATOR_MODEL to "Moderator Model",
                    ModelSource.CUSTOM to "Custom Model"
                ).forEach { (src, label) ->
                    val selected = del.modelSource == src
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) cc.accent.copy(alpha = 0.12f) else cc.panelAlt,
                        border = BorderStroke(0.75.dp, if (selected) cc.accent else cc.border.copy(alpha = 0.45f)),
                        modifier = Modifier.weight(1f).clickable {
                            onConfigChange(config.copy(deliverable = del.copy(modelSource = src)))
                        }
                    ) {
                        Box(modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = if (selected) cc.accent else cc.textPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        if (del.format == DeliverableFormat.CUSTOM || del.customInstructions.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Deliverable Instructions Prompt",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary
                )
                BasicTextField(
                    value = del.customInstructions,
                    onValueChange = { onConfigChange(config.copy(deliverable = del.copy(customInstructions = it))) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = cc.textPrimary),
                    cursorBrush = SolidColor(cc.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(cc.panelAlt)
                        .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.4f)), RoundedCornerShape(6.dp))
                        .padding(10.dp),
                    decorationBox = { inner ->
                        if (del.customInstructions.isEmpty()) {
                            Text(
                                "e.g. Synthesize the debate into a 3-column comparative pricing table with recommendation...",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = cc.textMuted.copy(alpha = 0.7f)
                            )
                        }
                        inner()
                    }
                )
            }
        }

        HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

        // Hard Spending Ceiling (TASK-07 & TASK-08)
        val costEff = config.costEfficiency
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Hard Spending Ceiling",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    ThemedTooltipBox("A safety cutoff: the discussion will automatically wrap up and summarize if it ever reaches this cost.") {
                        Icon(Icons.Outlined.Info, contentDescription = "Hard Spending Ceiling tooltip", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                    }
                }
                Text(
                    if (costEff.maxDollarSpendBudget > 0.0) "$${(costEff.maxDollarSpendBudget * 100).roundToInt() / 100.0} USD" else "Unlimited",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                    color = cc.accent
                )
            }
            AestheticSlider(
                value = costEff.maxDollarSpendBudget.toFloat().coerceIn(0.50f, 10.0f),
                onValueChange = {
                    val rounded = (it * 2).roundToInt() / 2.0
                    onConfigChange(config.copy(costEfficiency = costEff.copy(maxDollarSpendBudget = rounded)))
                },
                valueRange = 0.50f..10.0f,
                modifier = Modifier.fillMaxWidth().height(22.dp)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Feature 4: Auto-Save & Output Files
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AutoSaveSection(
    config: DebateConfig,
    onConfigChange: (DebateConfig) -> Unit,
    cc: com.dialex.theme.CcPalette,
    modifier: Modifier = Modifier
) {
    val out = config.output
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cc.panel)
            .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Save Debate Artifacts",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Automatically saves deliverable, summary, and transcripts when the debate completes. Stored in the engine repository when no local folder is configured.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cc.textMuted
                )
            }
            AestheticSwitch(
                checked = out.saveArtifacts,
                onCheckedChange = { onConfigChange(config.copy(output = out.copy(saveArtifacts = it))) }
            )
        }

        if (out.saveArtifacts) {
            HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

            Column {
                Text(
                    "Custom Destination Folder (Optional)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = cc.textPrimary
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "If left empty (or on mobile), artifacts are safely stored on the engine side and accessible via the Artifacts button in chat.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cc.textMuted
                )
            }

            // Folder Path Input
            BasicTextField(
                value = out.outputFolder,
                onValueChange = { onConfigChange(config.copy(output = out.copy(outputFolder = it.trim()))) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = cc.textPrimary),
                cursorBrush = SolidColor(cc.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(cc.panelAlt)
                    .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.4f)), RoundedCornerShape(6.dp))
                    .padding(10.dp),
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f)) {
                            if (out.outputFolder.isEmpty()) {
                                Text(
                                    "Save to Engine repository (or enter folder e.g. /path/to/debates)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = cc.textMuted.copy(alpha = 0.7f)
                                )
                            }
                            inner()
                        }
                    }
                }
            )

            HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

            // Checkboxes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Auto-save Deliverable Artifact (.md)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = cc.textPrimary
                )
                Checkbox(
                    checked = out.autoSaveDeliverable,
                    onCheckedChange = { onConfigChange(config.copy(output = out.copy(autoSaveDeliverable = it))) },
                    colors = CheckboxDefaults.colors(checkedColor = cc.accent)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Auto-save Discussion Summary (.md)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = cc.textPrimary
                )
                Checkbox(
                    checked = out.autoSaveSummary,
                    onCheckedChange = { onConfigChange(config.copy(output = out.copy(autoSaveSummary = it))) },
                    colors = CheckboxDefaults.colors(checkedColor = cc.accent)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Auto-save Full Transcript (.md)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = cc.textPrimary
                )
                Checkbox(
                    checked = out.autoSaveFullTranscript,
                    onCheckedChange = { onConfigChange(config.copy(output = out.copy(autoSaveFullTranscript = it))) },
                    colors = CheckboxDefaults.colors(checkedColor = cc.accent)
                )
            }
        }
    }
}

/**
 * Dialog to save current discussion configuration as a reusable one-click council preset.
 */
@Composable
private fun SavePresetDialog(
    initialTitle: String,
    onSave: (title: String, subtitle: String, badge: String) -> Unit,
    onDismiss: () -> Unit
) {
    val cc = LocalCcColors.current
    var title by remember { mutableStateOf(initialTitle.ifBlank { "Custom Council" }) }
    var subtitle by remember { mutableStateOf("") }
    var badge by remember { mutableStateOf("Custom") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.widthIn(min = 320.dp, max = 450.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.BookmarkAdd,
                        contentDescription = null,
                        tint = cc.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Save as Template",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        ),
                        color = cc.textPrimary
                    )
                }

                Text(
                    "Save this discussion configuration (agents, models, moderation strictness, deliverable format) as a template for quick reuse.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cc.textMuted
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Template Title", style = MaterialTheme.typography.labelSmall, color = cc.textMuted)
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        singleLine = true,
                        placeholder = { Text("e.g. Architecture Design Council") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Subtitle / Tagline", style = MaterialTheme.typography.labelSmall, color = cc.textMuted)
                    OutlinedTextField(
                        value = subtitle,
                        onValueChange = { subtitle = it },
                        singleLine = true,
                        placeholder = { Text("e.g. Scalability vs Cost vs Delivery") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Category / Badge", style = MaterialTheme.typography.labelSmall, color = cc.textMuted)
                    OutlinedTextField(
                        value = badge,
                        onValueChange = { badge = it },
                        singleLine = true,
                        placeholder = { Text("e.g. Engineering, Product, Strategy") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = cc.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotBlank()) {
                                onSave(title, subtitle, badge)
                            }
                        },
                        enabled = title.isNotBlank()
                    ) {
                        Text("Save Template")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LEVEL 3: Power-User Drawer (Advanced Engine & Tuning)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AdvancedEngineDrawer(
    config: DebateConfig,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onConfigChange: (DebateConfig) -> Unit,
    activeArchetype: PresetArchetype,
    cc: com.dialex.theme.CcPalette,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cc.panel,
        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.55f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Accordion Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onToggleExpand() }
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = cc.accent,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Advanced Engine & Tuning (Level 3)",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = cc.textPrimary
                        )
                        Text(
                            "Granular controls: Creativity cooling schedules, repetition sensitivity, memory compaction, and topical drift guards.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = cc.textMuted
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (activeArchetype == PresetArchetype.CUSTOM) Color(0xFFF59E0B).copy(alpha = 0.15f) else cc.panelAlt,
                    border = BorderStroke(0.6.dp, if (activeArchetype == PresetArchetype.CUSTOM) Color(0xFFF59E0B).copy(alpha = 0.4f) else cc.border.copy(alpha = 0.35f))
                ) {
                    Text(
                        if (activeArchetype == PresetArchetype.CUSTOM) "CUSTOM TUNING" else activeArchetype.displayName.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = if (activeArchetype == PresetArchetype.CUSTOM) Color(0xFFF59E0B) else cc.textMuted,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp)
                ) {
                    HorizontalDivider(color = cc.border.copy(alpha = 0.3f), thickness = 0.75.dp)

                    // 1. Sampling & Creativity Levers
                    SamplingAndCreativitySection(config, onConfigChange, cc)

                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    // 2. Anti-Looping & Deduplication Levers
                    AntiLoopSection(config, onConfigChange, cc)

                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    // 3. Deliberation Depth & Jargon (TASK-04)
                    DeliberationDepthSection(config, onConfigChange, cc)

                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    // 4. Token Strategy & Compaction Frequency (TASK-03)
                    TokenStrategySection(config, onConfigChange, cc)

                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    // 5. Moderation & Topical Steerage (TASK-06)
                    ModerationSection(config, onConfigChange, cc)

                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    // 6. Conclusion, Deliverable & Spending Ceiling (TASK-07)
                    DeliverableSection(config, onConfigChange, cc)

                    HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                    // 7. Auto-Save Files
                    AutoSaveSection(config, onConfigChange, cc)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sampling & Creativity Section with Plain-English Labels & Tooltips
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SamplingAndCreativitySection(
    config: DebateConfig,
    onConfigChange: (DebateConfig) -> Unit,
    cc: com.dialex.theme.CcPalette,
    modifier: Modifier = Modifier
) {
    val sampling = config.sampling

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Creativity & Tone",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                        color = cc.textPrimary
                    )
                    ThemedTooltipBox("Lower values make answers precise and factual; higher values make answers creative and diverse.") {
                        Icon(Icons.Outlined.Info, contentDescription = "Creativity & Tone tooltip", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                    }
                }
                Text(
                    "Calibrates temperature, nucleus sampling, and presence penalties across turns.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cc.textMuted
                )
            }
            Text(
                "${((sampling.temperature * 100).roundToInt() / 100.0)}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                color = cc.accent
            )
        }

        // Temperature Slider
        AestheticSlider(
            value = sampling.temperature.toFloat(),
            onValueChange = { onConfigChange(config.copy(sampling = sampling.copy(temperature = it.toDouble()))) },
            valueRange = 0.0f..1.5f,
            modifier = Modifier.fillMaxWidth()
        )

        // Creativity Cooling Schedule Dropdown
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Creativity Cooling",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    ThemedTooltipBox("Starts with open, creative brainstorming in early rounds, then cools down to focus on facts and agreement.") {
                        Icon(Icons.Outlined.Info, contentDescription = "Creativity Cooling tooltip", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                    }
                }
                Text(
                    "Dynamic temperature scheduling schedule across debate rounds.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cc.textMuted
                )
            }

            ThemedDropdown(
                selectedId = sampling.schedule.name,
                options = listOf(
                    ThemedDropdownOption(id = TemperatureSchedule.STATIC.name, title = "Static (Fixed Temp)"),
                    ThemedDropdownOption(id = TemperatureSchedule.LINEAR_COOLING.name, title = "Linear Cooling (Brainstorm -> Focus)"),
                    ThemedDropdownOption(id = TemperatureSchedule.THREE_STAGE_DELIBERATION.name, title = "Three-Stage Deliberation"),
                    ThemedDropdownOption(id = TemperatureSchedule.ADAPTIVE_CONSENSUS_COOLED.name, title = "Adaptive Consensus Cooled")
                ),
                onSelect = { opt ->
                    val newSched = try { TemperatureSchedule.valueOf(opt.id) } catch (_: Exception) { TemperatureSchedule.STATIC }
                    onConfigChange(config.copy(sampling = sampling.copy(schedule = newSched)))
                },
                isMinimal = true,
                minHeight = 32.dp
            )
        }

        // Frequency and Presence Penalties Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Frequency Penalty", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = cc.textPrimary)
                    Text("${((sampling.frequencyPenalty * 100).roundToInt() / 100.0)}", style = MaterialTheme.typography.labelSmall, color = cc.textMuted)
                }
                AestheticSlider(
                    value = sampling.frequencyPenalty.toFloat(),
                    onValueChange = { onConfigChange(config.copy(sampling = sampling.copy(frequencyPenalty = it.toDouble()))) },
                    valueRange = 0.0f..2.0f
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Presence Penalty", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = cc.textPrimary)
                    Text("${((sampling.presencePenalty * 100).roundToInt() / 100.0)}", style = MaterialTheme.typography.labelSmall, color = cc.textMuted)
                }
                AestheticSlider(
                    value = sampling.presencePenalty.toFloat(),
                    onValueChange = { onConfigChange(config.copy(sampling = sampling.copy(presencePenalty = it.toDouble()))) },
                    valueRange = 0.0f..2.0f
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Anti-Looping & Deduplication Section with Plain-English Labels & Tooltips
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AntiLoopSection(
    config: DebateConfig,
    onConfigChange: (DebateConfig) -> Unit,
    cc: com.dialex.theme.CcPalette,
    modifier: Modifier = Modifier
) {
    val antiLoop = config.antiLoop

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Anti-Loop & Deduplication Guard",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    color = cc.textPrimary
                )
                Text(
                    "Prevents participants from repeating arguments or falling into infinite circular exchanges.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cc.textMuted
                )
            }
            AestheticSwitch(
                checked = antiLoop.enabled,
                onCheckedChange = { onConfigChange(config.copy(antiLoop = antiLoop.copy(enabled = it))) }
            )
        }

        if (antiLoop.enabled) {
            // Repetition Sensitivity Slider
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Repetition Sensitivity",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = cc.textPrimary
                        )
                        ThemedTooltipBox("How strictly the app watches for and stops participants from repeating points they already made.") {
                            Icon(Icons.Outlined.Info, contentDescription = "Repetition Sensitivity tooltip", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                        }
                    }
                    val sensLabel = when {
                        antiLoop.maxSimilarityThreshold <= 0.52 -> "Ultra-Strict"
                        antiLoop.maxSimilarityThreshold <= 0.60 -> "Strict"
                        antiLoop.maxSimilarityThreshold <= 0.70 -> "Balanced"
                        else -> "Lenient"
                    }
                    Text(
                        "${((antiLoop.maxSimilarityThreshold * 100).roundToInt() / 100.0)} ($sensLabel)",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                        color = cc.accent
                    )
                }

                AestheticSlider(
                    value = antiLoop.maxSimilarityThreshold.toFloat(),
                    onValueChange = { onConfigChange(config.copy(antiLoop = antiLoop.copy(maxSimilarityThreshold = it.toDouble()))) },
                    valueRange = 0.30f..0.95f,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Loop Fallback Action Dropdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Loop Breaker Action",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary
                    )
                    Text(
                        "Action triggered if repetitive statements persist after initial warning.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = cc.textMuted
                    )
                }

                ThemedDropdown(
                    selectedId = antiLoop.fallbackAction.name,
                    options = listOf(
                        ThemedDropdownOption(id = LoopAction.CONVERT_TO_CONCESSION.name, title = "Concede Stance (Auto-Concession)"),
                        ThemedDropdownOption(id = LoopAction.RETRY_WITH_DIRECTIVE.name, title = "Retry with Creative Bump (+0.25 T)"),
                        ThemedDropdownOption(id = LoopAction.MODERATOR_INTERVENE.name, title = "Moderator Intervene & Pivot"),
                        ThemedDropdownOption(id = LoopAction.HALT_OR_ADVANCE.name, title = "Halt & Advance Round")
                    ),
                    onSelect = { opt ->
                        val newAction = try { LoopAction.valueOf(opt.id) } catch (_: Exception) { LoopAction.CONVERT_TO_CONCESSION }
                        onConfigChange(config.copy(antiLoop = antiLoop.copy(fallbackAction = newAction)))
                    },
                    isMinimal = true,
                    minHeight = 32.dp
                )
            }
        }
    }
}



