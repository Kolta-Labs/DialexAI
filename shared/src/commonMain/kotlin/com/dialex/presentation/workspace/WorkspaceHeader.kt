package com.dialex.presentation.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.DiscussionStatus
import com.dialex.theme.LocalCcColors
import com.dialex.ui.ThemedTooltipBox
import com.dialex.ui.windowTitleBarDoubleClick

/**
 * Clean Antigravity-themed Top App Bar / Header for the workspace canvas.
 * Features:
 * - Square rounded topic icon badge with hairline border
 * - Medium-weight calm discussion title (double-click to rename)
 * - Rounded project pill badge with subtle container tint
 * - Soft status indicator pill
 * - Integrated collapsible chat search bar with match counter and navigation chevrons
 * - Antigravity styled action icon buttons & summary pill
 * - Hairline bottom divider (0.75dp, alpha = 0.25f)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkspaceHeader(
    projectName: String?,
    discussionName: String?,
    status: DiscussionStatus?,
    tokenWarningLevel: String? = null,
    onBack: (() -> Unit)? = null,
    onEditSetup: (() -> Unit)? = null,
    onExportMarkdown: (() -> Unit)? = null,
    onExportMemo: (() -> Unit)? = null,
    onShowUsageModal: (() -> Unit)? = null,
    readingSettings: com.dialex.presentation.chat.ChatTypographySettings? = null,
    onReadingSettingsChange: ((com.dialex.presentation.chat.ChatTypographySettings) -> Unit)? = null,
    onToggleSummary: (() -> Unit)? = null,
    onOpenArtifacts: (() -> Unit)? = null,
    artifactsCount: Int = 0,
    hasUnreadArtifacts: Boolean = false,
    onRenameDiscussion: ((String) -> Unit)? = null,
    onPairMobile: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onToggleSidebar: (() -> Unit)? = null,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    isSearchActive: Boolean = false,
    onToggleSearch: (Boolean) -> Unit = {},
    searchMatchCount: Int = 0,
    currentSearchMatchIndex: Int = 0,
    onNextSearchMatch: () -> Unit = {},
    onPrevSearchMatch: () -> Unit = {},
    openTensionCount: Int = 0,
    onOpenTensionDrawer: (() -> Unit)? = null,
    retrievedEvidenceCount: Int = 0,
    onOpenEvidenceDrawer: (() -> Unit)? = null,
    credenceLedger: com.dialex.domain.model.CredenceLedger? = null,
    onOpenCredenceDrawer: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current

    var isEditingTitle by remember { mutableStateOf(false) }
    var editTitleText by remember(discussionName) { mutableStateOf(discussionName ?: "") }
    val titleFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    var hasBeenFocused by remember { mutableStateOf(false) }

    fun commitTitleRename() {
        val trimmed = editTitleText.trim()
        if (trimmed.isNotBlank() && trimmed != discussionName) {
            onRenameDiscussion?.invoke(trimmed)
        }
        isEditingTitle = false
        hasBeenFocused = false
    }

    LaunchedEffect(isEditingTitle) {
        if (isEditingTitle) {
            hasBeenFocused = false
            editTitleText = discussionName ?: ""
            titleFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            searchFocusRequester.requestFocus()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(cc.bg)
    ) {
        val availableWidth = maxWidth
        // When search is active, collapse all direct actions into the overflow menu to give search maximum space
        val showSecondaryTools = !isSearchActive && availableWidth >= 880.dp
        val showPairMobileDirect = !isSearchActive && availableWidth >= 760.dp
        val showExportDirect = !isSearchActive && availableWidth >= 680.dp
        val showSummaryDirect = !isSearchActive && availableWidth >= 480.dp
        val showSummaryText = !isSearchActive && availableWidth >= 600.dp
        val showArtifactsDirect = !isSearchActive && availableWidth >= 520.dp

        val hasOverflowItems = isSearchActive || !showSecondaryTools || (onPairMobile != null && !showPairMobileDirect) || !showExportDirect || !showSummaryDirect || !showArtifactsDirect || onOpenSettings != null

        var overflowMenuOpen by remember { mutableStateOf(false) }
        var exportMenuOpen by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(48.dp)
                    .windowTitleBarDoubleClick()
                    .padding(
                        start = if (onToggleSidebar != null && onBack == null) 0.dp else 10.dp,
                        end = 10.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reserve spacing for native macOS traffic lights (68.dp) + 6.dp left padding for show panel button
                if (onToggleSidebar != null && onBack == null) {
                    Spacer(Modifier.width(68.dp))
                    Spacer(Modifier.width(6.dp))
                }

                // Mobile back button returning to master workspace
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back to Workspace",
                            tint = cc.textMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                }

                // Expand sidebar button when sidebar is hidden on wide screens
                if (onToggleSidebar != null && onBack == null) {
                    ThemedTooltipBox("Expand sidebar") {
                        IconButton(
                            onClick = onToggleSidebar,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ViewSidebar,
                                contentDescription = "Expand sidebar",
                                tint = cc.textMuted,
                                modifier = Modifier.size(16.5.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }

                // Left: Topic icon (rendered cleanly inline with no button-like background or border)
                Icon(
                    Icons.Outlined.Forum,
                    contentDescription = null,
                    tint = cc.textMuted.copy(alpha = 0.75f),
                    modifier = Modifier.size(16.dp)
                )

                Spacer(Modifier.width(8.dp))

                // Discussion Title (double-click to rename)
                if (isEditingTitle && onRenameDiscussion != null) {
                    BasicTextField(
                        value = editTitleText,
                        onValueChange = { editTitleText = it },
                        modifier = Modifier
                            .focusRequester(titleFocusRequester)
                            .widthIn(min = 80.dp, max = 320.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(cc.panelAlt)
                            .border(1.dp, cc.accent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    hasBeenFocused = true
                                } else if (hasBeenFocused && isEditingTitle) {
                                    commitTitleRename()
                                }
                            }
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    when (keyEvent.key) {
                                        Key.Enter -> {
                                            commitTitleRename()
                                            true
                                        }
                                        Key.Escape -> {
                                            isEditingTitle = false
                                            hasBeenFocused = false
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.5.sp,
                            color = cc.textPrimary
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(cc.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commitTitleRename() })
                    )
                } else if (onRenameDiscussion != null) {
                    ThemedTooltipBox("Double-click to rename") {
                        Text(
                            discussionName ?: "Dialex",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.5.sp
                            ),
                            color = cc.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .combinedClickable(
                                    onDoubleClick = {
                                        isEditingTitle = true
                                    },
                                    onClick = {}
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Text(
                        discussionName ?: "Dialex",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.5.sp
                        ),
                        color = cc.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                // Project Pill Badge
                if (projectName != null && availableWidth >= 520.dp) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f))
                    ) {
                        Text(
                            projectName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = cc.textMuted,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                        )
                    }
                }

                // Status Indicator Pill (Antigravity Style with Pulsating Glow Dot)
                if (status != null) {
                    Spacer(Modifier.width(8.dp))
                    val statusColor = when (status) {
                        DiscussionStatus.RUNNING -> Color(0xFF4CAF50)
                        DiscussionStatus.PAUSED -> Color(0xFFF59E0B)
                        DiscussionStatus.COMPLETED, DiscussionStatus.DONE -> Color(0xFF4CAF50)
                        DiscussionStatus.COMPLETED_WITH_WARNING -> Color(0xFFF59E0B)
                        DiscussionStatus.FAILED, DiscussionStatus.ERROR -> Color(0xFFEF4444)
                        DiscussionStatus.DRAFT -> cc.textMuted
                    }
                    val statusLabel = when (status) {
                        DiscussionStatus.RUNNING -> "Running"
                        DiscussionStatus.PAUSED -> "Paused"
                        DiscussionStatus.COMPLETED, DiscussionStatus.DONE -> "Completed"
                        DiscussionStatus.COMPLETED_WITH_WARNING -> "Completed with Warning"
                        DiscussionStatus.FAILED, DiscussionStatus.ERROR -> "Failed"
                        DiscussionStatus.DRAFT -> "Draft"
                    }

                    val infiniteTransition = rememberInfiniteTransition()
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 0.65f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        )
                    )
                    val isPulsing = status == DiscussionStatus.RUNNING || status == DiscussionStatus.PAUSED

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            // Glow dot with pulsating halo
                            Box(
                                modifier = Modifier.size(11.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isPulsing) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(statusColor.copy(alpha = pulseAlpha))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(statusColor.copy(alpha = 0.2f))
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(5.5.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                            }

                            Text(
                                statusLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                color = cc.textMuted
                            )
                        }
                    }
                }

                // Paraconsistent Tensions Pill Button (Touch Drawer Trigger)
                if (onOpenTensionDrawer != null && openTensionCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    ThemedTooltipBox("$openTensionCount Dialectic Tensions") {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = Color(0xFFFEF3C7),
                            border = BorderStroke(0.75.dp, Color(0xFFD97706).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable(onClick = onOpenTensionDrawer)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.ElectricBolt,
                                    contentDescription = "Tension Matrix",
                                    tint = Color(0xFFD97706),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    "$openTensionCount",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFFD97706)
                                )
                            }
                        }
                    }
                }

                // Dynamic Grounding Evidence Pill Button (Touch Drawer Trigger)
                if (onOpenEvidenceDrawer != null && retrievedEvidenceCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    ThemedTooltipBox("$retrievedEvidenceCount Injected Evidence Items") {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = Color(0xFFEDE7F6),
                            border = BorderStroke(0.75.dp, Color(0xFF673AB7).copy(alpha = 0.5f)),
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable(onClick = onOpenEvidenceDrawer)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.TravelExplore,
                                    contentDescription = "Dynamic Evidence",
                                    tint = Color(0xFF673AB7),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    "$retrievedEvidenceCount",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = Color(0xFF673AB7)
                                )
                            }
                        }
                    }
                }

                // Bayesian Credence Pill Button (Touch Drawer Trigger)
                if (onOpenCredenceDrawer != null && credenceLedger != null && credenceLedger.snapshots.isNotEmpty()) {
                    val latest = credenceLedger.latestSnapshot ?: credenceLedger.snapshots.last()
                    val dominantH = credenceLedger.hypotheses.find { it.id == latest.dominantHypothesis }
                        ?: credenceLedger.hypotheses.maxByOrNull { latest.probabilityFor(it.id) }
                    val dominantP = ((latest.probabilityFor(dominantH?.id ?: "")) * 100).toInt()
                    val entropy = latest.entropy
                    val entropyStr = ((entropy * 100).toInt() / 100.0).toString()

                    val (pillBg, pillBorder, pillTint) = when {
                        entropy < 0.8 -> Triple(Color(0xFFECFDF5), Color(0xFF10B981).copy(alpha = 0.5f), Color(0xFF059669))
                        entropy <= 1.4 -> Triple(Color(0xFFFEF3C7), Color(0xFFF59E0B).copy(alpha = 0.5f), Color(0xFFD97706))
                        else -> Triple(Color(0xFFEEF2FF), Color(0xFF6366F1).copy(alpha = 0.5f), Color(0xFF4F46E5))
                    }

                    Spacer(Modifier.width(8.dp))
                    ThemedTooltipBox("Bayesian Credence: $dominantP% ${dominantH?.label ?: "Dominant"} (Entropy: $entropyStr bits)") {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = pillBg,
                            border = BorderStroke(0.75.dp, pillBorder),
                            modifier = Modifier
                                .height(28.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable(onClick = onOpenCredenceDrawer)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Analytics,
                                    contentDescription = "Bayesian Credence",
                                    tint = pillTint,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    "$dominantP% ${dominantH?.label ?: "H1"}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = pillTint
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Summary Bubble / Button (Shown directly when there is room)
                if (onToggleSummary != null && showSummaryDirect) {
                    ThemedTooltipBox("Discussion Summary") {
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = cc.panelAlt,
                            border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .height(30.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .clickable(onClick = onToggleSummary)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = if (showSummaryText) 9.dp else 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Summarize,
                                    contentDescription = "Discussion Summary",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(13.5.dp)
                                )
                                if (showSummaryText) {
                                    Text(
                                        "Summary",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = cc.textPrimary
                                    )
                                }
                            }
                        }
                    }
                }

                // Artifacts Repository Button (Shown directly when there is room)
                if (onOpenArtifacts != null && showArtifactsDirect) {
                    val tooltip = if (artifactsCount > 0) "Artifacts Repository ($artifactsCount)" else "Artifacts Repository"
                    ThemedTooltipBox(tooltip) {
                        IconButton(onClick = onOpenArtifacts, modifier = Modifier.size(34.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Inventory2,
                                    contentDescription = "Artifacts Repository",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(16.5.dp)
                                )
                                if (hasUnreadArtifacts) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 4.dp, y = (-2).dp)
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF3B82F6))
                                    )
                                }
                            }
                        }
                    }
                }

                // Secondary Tools (Shown directly when wide >= 820dp)
                if (showSecondaryTools) {
                    if (onEditSetup != null) {
                        ThemedTooltipBox("Edit Discussion Setup") {
                            IconButton(onClick = onEditSetup, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Outlined.Tune,
                                    contentDescription = "Edit Setup",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(16.5.dp)
                                )
                            }
                        }
                    }

                    if (onShowUsageModal != null) {
                        ThemedTooltipBox("Usage Breakdown") {
                            IconButton(onClick = onShowUsageModal, modifier = Modifier.size(34.dp)) {
                                Icon(
                                    Icons.Outlined.Analytics,
                                    contentDescription = "Usage Breakdown",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(16.5.dp)
                                )
                            }
                        }
                    }

                    if (readingSettings != null && onReadingSettingsChange != null) {
                        ThemedTooltipBox("Reading Typography & Zoom") {
                            com.dialex.presentation.chat.ReadingTypographyButton(
                                settings = readingSettings,
                                onSettingsChange = onReadingSettingsChange
                            )
                        }
                    }
                }

                // Export Options (Shown directly when >= 640dp)
                if (showExportDirect && (onExportMarkdown != null || onExportMemo != null)) {
                    Box {
                        ThemedTooltipBox("Export Options") {
                            IconButton(
                                onClick = {
                                    if (onExportMemo != null && onExportMarkdown != null) {
                                        exportMenuOpen = true
                                    } else {
                                        onExportMemo?.invoke() ?: onExportMarkdown?.invoke()
                                    }
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.FileDownload,
                                    contentDescription = "Export Options",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(16.5.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = exportMenuOpen,
                            onDismissRequest = { exportMenuOpen = false },
                            modifier = Modifier
                                .background(cc.panelAlt)
                                .border(1.dp, cc.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        ) {
                            if (onExportMemo != null) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                "Executive Memorandum",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = cc.textPrimary
                                            )
                                            Text(
                                                "Self-contained HTML / Print to PDF",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = cc.textMuted
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.Article,
                                            contentDescription = null,
                                            tint = cc.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        exportMenuOpen = false
                                        onExportMemo.invoke()
                                    }
                                )
                            }
                            if (onExportMarkdown != null) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                "Full Transcript (.md)",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = cc.textPrimary
                                            )
                                            Text(
                                                "Standard Markdown format",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = cc.textMuted
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Description,
                                            contentDescription = null,
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    onClick = {
                                        exportMenuOpen = false
                                        onExportMarkdown.invoke()
                                    }
                                )
                            }
                        }
                    }
                }

                // Mobile Companion Pairing (Shown directly when >= 680dp)
                if (onPairMobile != null && showPairMobileDirect) {
                    ThemedTooltipBox("Pair Mobile Companion") {
                        IconButton(onClick = onPairMobile, modifier = Modifier.size(34.dp)) {
                            Icon(
                                Icons.Outlined.Smartphone,
                                contentDescription = "Pair Mobile Companion",
                                tint = cc.textMuted,
                                modifier = Modifier.size(16.5.dp)
                            )
                        }
                    }
                }

                // Search Bar / Button in Top Header
                if (isSearchActive) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .height(32.dp)
                            .widthIn(min = 210.dp, max = 340.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = cc.textMuted,
                                modifier = Modifier.size(13.5.dp)
                            )

                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Search in chat...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                        color = cc.textMuted.copy(alpha = 0.6f)
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        color = cc.textPrimary
                                    ),
                                    cursorBrush = SolidColor(cc.accent),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { onNextSearchMatch() }),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(searchFocusRequester)
                                        .onKeyEvent { keyEvent ->
                                            if (keyEvent.type == KeyEventType.KeyDown) {
                                                val isEnter = keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter
                                                when {
                                                    isEnter && keyEvent.isShiftPressed -> {
                                                        onPrevSearchMatch()
                                                        true
                                                    }
                                                    isEnter -> {
                                                        onNextSearchMatch()
                                                        true
                                                    }
                                                    keyEvent.key == Key.Escape -> {
                                                        onToggleSearch(false)
                                                        onSearchQueryChange("")
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            } else false
                                        }
                                )
                            }

                            if (searchQuery.isNotEmpty()) {
                                if (searchMatchCount > 0) {
                                    Text(
                                        "${currentSearchMatchIndex + 1}/$searchMatchCount",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = cc.accent
                                    )
                                } else {
                                    Text(
                                        "0 matches",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }

                                // Prev / Next navigation chevrons
                                IconButton(
                                    onClick = onPrevSearchMatch,
                                    enabled = searchMatchCount > 0,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.KeyboardArrowUp,
                                        contentDescription = "Previous match (Shift+Enter)",
                                        tint = if (searchMatchCount > 0) cc.textPrimary else cc.textMuted.copy(alpha = 0.3f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                IconButton(
                                    onClick = onNextSearchMatch,
                                    enabled = searchMatchCount > 0,
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = "Next match (Enter)",
                                        tint = if (searchMatchCount > 0) cc.textPrimary else cc.textMuted.copy(alpha = 0.3f),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    onToggleSearch(false)
                                    onSearchQueryChange("")
                                },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close search",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                } else {
                    ThemedTooltipBox("Search in discussion (Cmd+F)") {
                        IconButton(
                            onClick = { onToggleSearch(true) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = "Search in discussion",
                                tint = cc.textMuted,
                                modifier = Modifier.size(16.5.dp)
                            )
                        }
                    }
                }

                // 3-dot Overflow Menu (Displays items not shown directly in the app bar)
                if (hasOverflowItems) {
                    Box {
                        ThemedTooltipBox("More options") {
                            IconButton(
                                onClick = { overflowMenuOpen = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = "More options",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = overflowMenuOpen,
                            onDismissRequest = { overflowMenuOpen = false },
                            modifier = Modifier
                                .background(cc.panelAlt)
                                .border(1.dp, cc.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        ) {
                            if (!showSummaryDirect && onToggleSummary != null) {
                                DropdownMenuItem(
                                    modifier = Modifier.height(32.dp),
                                    text = { Text("Discussion Summary", color = cc.textPrimary, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Summarize, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        overflowMenuOpen = false
                                        onToggleSummary.invoke()
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }
                            if (!showArtifactsDirect && onOpenArtifacts != null) {
                                DropdownMenuItem(
                                    modifier = Modifier.height(32.dp),
                                    text = {
                                        Text(
                                            if (artifactsCount > 0) "Artifacts Repository ($artifactsCount)" else "Artifacts Repository",
                                            color = cc.textPrimary,
                                            fontSize = 13.sp
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        overflowMenuOpen = false
                                        onOpenArtifacts.invoke()
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }
                            if (!showSecondaryTools) {
                                if (onEditSetup != null) {
                                    DropdownMenuItem(
                                        modifier = Modifier.height(32.dp),
                                        text = { Text("Edit Setup", color = cc.textPrimary, fontSize = 13.sp) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Tune, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                        },
                                        onClick = {
                                            overflowMenuOpen = false
                                            onEditSetup.invoke()
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    )
                                }
                                if (onShowUsageModal != null) {
                                    DropdownMenuItem(
                                        modifier = Modifier.height(32.dp),
                                        text = { Text("Usage Breakdown", color = cc.textPrimary, fontSize = 13.sp) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Analytics, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                        },
                                        onClick = {
                                            overflowMenuOpen = false
                                            onShowUsageModal.invoke()
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    )
                                }
                            }
                            if (credenceLedger != null && onOpenCredenceDrawer != null) {
                                DropdownMenuItem(
                                    modifier = Modifier.height(32.dp),
                                    text = { Text("Bayesian Credence Network", color = cc.textPrimary, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Analytics, contentDescription = null, tint = cc.accent, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        overflowMenuOpen = false
                                        onOpenCredenceDrawer.invoke()
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }
                            if (!showExportDirect) {
                                if (onExportMemo != null) {
                                    DropdownMenuItem(
                                        modifier = Modifier.height(32.dp),
                                        text = { Text("Export Memorandum", color = cc.textPrimary, fontSize = 13.sp) },
                                        leadingIcon = {
                                            Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null, tint = cc.accent, modifier = Modifier.size(16.dp))
                                        },
                                        onClick = {
                                            overflowMenuOpen = false
                                            onExportMemo.invoke()
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    )
                                }
                                if (onExportMarkdown != null) {
                                    DropdownMenuItem(
                                        modifier = Modifier.height(32.dp),
                                        text = { Text("Export Markdown (.md)", color = cc.textPrimary, fontSize = 13.sp) },
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Description, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                        },
                                        onClick = {
                                            overflowMenuOpen = false
                                            onExportMarkdown.invoke()
                                        },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                    )
                                }
                            }
                            if (onPairMobile != null && !showPairMobileDirect) {
                                DropdownMenuItem(
                                    modifier = Modifier.height(32.dp),
                                    text = { Text("Pair Mobile Companion", color = cc.textPrimary, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Smartphone, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        overflowMenuOpen = false
                                        onPairMobile.invoke()
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }
                            if (onOpenSettings != null) {
                                HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)
                                DropdownMenuItem(
                                    modifier = Modifier.height(32.dp),
                                    text = { Text("Settings", color = cc.textPrimary, fontSize = 13.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Settings, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(16.dp))
                                    },
                                    onClick = {
                                        overflowMenuOpen = false
                                        onOpenSettings.invoke()
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)
        }
    }
}
