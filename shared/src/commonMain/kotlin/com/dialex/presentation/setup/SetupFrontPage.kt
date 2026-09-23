package com.dialex.presentation.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.CouncilTemplate
import com.dialex.model.DiscussionPresets
import com.dialex.model.PresetArchetype
import com.dialex.theme.LocalCcColors
import com.dialex.theme.accentColor
import com.dialex.ui.ConfirmDialog
import com.dialex.ui.GradientButton
import com.dialex.ui.ThemedDropdown
import com.dialex.ui.ThemedDropdownOption
import com.dialex.ui.ThemedTooltipBox
import com.dialex.ui.windowTitleBarDoubleClick

/**
 * Clean, modern Front Page for starting a discussion:
 * 1. "Create a new discussion" hero with description and primary CTA
 * 2. "or select from template" divider
 * 3. Responsive grid of small, compact template cards
 * 4. Ability to use and delete custom saved templates
 */
@Composable
fun SetupFrontPage(
    state: SetupState,
    onIntent: (SetupIntent) -> Unit,
    onToggleSidebar: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    var templatePendingDelete by remember { mutableStateOf<CouncilTemplate?>(null) }
    var copyFromOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cc.bg)
    ) {
        // ── 1. Top Bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowTitleBarDoubleClick()
                .height(if (isCompact) 56.dp else 64.dp)
                .padding(
                    start = if (!isCompact && onToggleSidebar != null) 0.dp else if (isCompact) 16.dp else 28.dp,
                    end = if (isCompact) 16.dp else 28.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Reserve spacing for native macOS traffic lights (68.dp) + 6.dp left padding for show panel button
                if (!isCompact && onToggleSidebar != null) {
                    Spacer(Modifier.width(68.dp))
                    Spacer(Modifier.width(6.dp))
                }

                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = cc.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else if (onToggleSidebar != null) {
                    ThemedTooltipBox("Expand sidebar") {
                        IconButton(
                            onClick = onToggleSidebar,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ViewSidebar,
                                contentDescription = "Expand sidebar",
                                tint = cc.textPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Project Selector Dropdown
                val projectOptions = buildList {
                    state.projects.forEach { p ->
                        add(ThemedDropdownOption(id = p.id, title = p.name, icon = Icons.Outlined.Folder))
                    }
                }

                ThemedDropdown(
                    selectedId = state.selectedProject?.id,
                    options = projectOptions,
                    onSelect = { opt -> onIntent(SetupIntent.SelectProject(opt.id)) },
                    placeholder = "Select Project",
                    leadingIcon = Icons.Outlined.Folder,
                    isMinimal = true,
                    minHeight = 34.dp,
                    isCompact = isCompact
                )
            }
        }

        HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

        // ── 2. Scrollable Body: Hero + Divider + Responsive Templates Grid ─────
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (isCompact) 260.dp else 290.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 980.dp)
                    .padding(horizontal = if (isCompact) 16.dp else 32.dp),
                contentPadding = PaddingValues(top = 28.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Span 1: "Create a new discussion" Hero Box + "or select from template" Divider
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // ── "Create a new discussion" Hero Card ────────────────────────
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = cc.panel,
                            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.55f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(if (isCompact) 16.dp else 22.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
                                        Text(
                                            "Choose Deliberation Intent",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = if (isCompact) 17.sp else 20.sp
                                            ),
                                            color = cc.textPrimary
                                        )
                                        Text(
                                            "One-click calibrated presets for fast, predictable council deliberations.",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 12.5.sp,
                                                lineHeight = 17.sp
                                            ),
                                            color = cc.textMuted
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (state.otherDiscussions.isNotEmpty()) {
                                            OutlinedButton(
                                                onClick = { copyFromOpen = true },
                                                shape = RoundedCornerShape(8.dp),
                                                border = BorderStroke(0.75.dp, cc.border),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy from Discussion", tint = cc.textPrimary, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Copy from Discussion", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { onIntent(SetupIntent.SelectBlankConfig) },
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(0.75.dp, cc.border),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Icon(Icons.Outlined.Add, contentDescription = null, tint = cc.textPrimary, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text("Blank Setup", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                                        }
                                    }
                                }

                                // 4 Preset Archetype Cards
                                val presets = DiscussionPresets.all
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    presets.chunked(2).forEach { rowPresets ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            rowPresets.forEach { preset ->
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = cc.panelAlt,
                                                    border = BorderStroke(
                                                        1.dp,
                                                        if (preset.archetype == PresetArchetype.EXECUTIVE_DECISION) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.4f)
                                                    ),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable { onIntent(SetupIntent.SelectArchetype(preset.archetype)) }
                                                ) {
                                                    Column(
                                                        modifier = Modifier.padding(14.dp),
                                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                val icon = when (preset.archetype) {
                                                                    PresetArchetype.QUICK_TAKE -> "⚡"
                                                                    PresetArchetype.EXECUTIVE_DECISION -> "💼"
                                                                    PresetArchetype.DEEP_RESEARCH -> "🔬"
                                                                    PresetArchetype.RED_TEAM_STRESS_TEST -> "🥊"
                                                                    else -> "⚙️"
                                                                }
                                                                Text(icon, fontSize = 14.sp)
                                                                Text(
                                                                    preset.displayName,
                                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                                        fontSize = 13.sp,
                                                                        fontWeight = FontWeight.SemiBold
                                                                    ),
                                                                    color = cc.textPrimary
                                                                )
                                                            }
                                                            if (preset.archetype == PresetArchetype.EXECUTIVE_DECISION) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = cc.accent.copy(alpha = 0.15f)
                                                                ) {
                                                                    Text(
                                                                        "DEFAULT",
                                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                                        color = cc.accent,
                                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        Text(
                                                            preset.description,
                                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                                            color = cc.textMuted,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )

                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                "💰 ${preset.estimatedCostLabel}",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                                                color = cc.textPrimary
                                                            )
                                                            Text(
                                                                "⏱️ ${preset.estimatedTimeLabel}",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
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
                        }

                        // ── "or select from template" Divider ─────────────────────────
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = cc.border.copy(alpha = 0.35f),
                                thickness = 0.75.dp
                            )
                            Text(
                                "or select from template",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = cc.textMuted,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = cc.border.copy(alpha = 0.35f),
                                thickness = 0.75.dp
                            )
                        }
                    }
                }

                // Small Template Cards in Responsive Grid
                items(state.templates, key = { it.id }) { template ->
                    SmallTemplateCard(
                        template = template,
                        onClick = { onIntent(SetupIntent.SelectTemplate(template)) },
                        onDelete = if (template.isCustom) {
                            { templatePendingDelete = template }
                        } else null
                    )
                }
            }
        }
    }

    // Delete Confirmation Dialog for Custom Templates
    if (templatePendingDelete != null) {
        val tpl = templatePendingDelete!!
        ConfirmDialog(
            title = "Delete Template",
            message = "Are you sure you want to delete '${tpl.title}'? This action cannot be undone.",
            confirmLabel = "Delete Template",
            onConfirm = {
                onIntent(SetupIntent.DeleteCustomTemplate(tpl.id))
                templatePendingDelete = null
            },
            onDismiss = { templatePendingDelete = null }
        )
    }

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
}

/**
 * Small, compact template card for the responsive grid.
 * Aerated, greyish, and uncluttered.
 */
@Composable
private fun SmallTemplateCard(
    template: CouncilTemplate,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val agents = listOfNotNull(
        template.primaryAgent,
        template.secondaryAgent,
        template.tertiaryAgent,
        template.quaternaryAgent,
        template.quinaryAgent,
        template.senaryAgent
    )

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = cc.panel,
        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Category Badge + Delete Icon (if custom)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (template.isCustom) "CUSTOM TEMPLATE" else template.badgeLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp
                    ),
                    color = cc.textMuted
                )

                if (onDelete != null) {
                    ThemedTooltipBox("Delete custom template") {
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "Delete template",
                                tint = cc.textMuted.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // Title & Subtitle (Compact)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = template.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.5.sp
                    ),
                    color = cc.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = template.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    ),
                    color = cc.textMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = cc.border.copy(alpha = 0.2f), thickness = 0.5.dp)

            // Footer: Participant dots + "Use →"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Agent indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    agents.take(3).forEach { agent ->
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(agent.provider.accentColor(cc))
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "${agents.size} agents",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = cc.textMuted
                    )
                }

                // Use action
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        "Use",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = cc.textPrimary
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = null,
                        tint = cc.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}
