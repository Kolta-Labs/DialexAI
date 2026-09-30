package com.dialex.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.PredefinedPersona
import com.dialex.theme.CcPalette
import com.dialex.theme.LocalCcColors

/**
 * Enhanced Persona selection dropdown for agent cards.
 *
 * Features:
 * - Shows expandable category groupings first (accordion style, strictly one category expanded at a time).
 * - Integrated top search bar filtering across persona name, role, category, and description.
 * - When searching, automatically expands all matching groups.
 * - Responsive: Desktop popup menu and Mobile ModalBottomSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaDropdown(
    selectedPersonaId: String?,
    availablePersonas: List<PredefinedPersona>,
    onSelectPersona: (PredefinedPersona?) -> Unit,
    modifier: Modifier = Modifier,
    onManagePersonas: (() -> Unit)? = null,
    isCompact: Boolean = false,
    fontSize: TextUnit = 11.5.sp,
    minHeight: Dp = 36.dp,
    cc: CcPalette = LocalCcColors.current,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var expandedCategory by remember { mutableStateOf<String?>(null) }

    val selectedPersona = remember(selectedPersonaId, availablePersonas) {
        availablePersonas.find { it.id == selectedPersonaId }
    }

    val selectedIcon = if (selectedPersona != null) {
        resolvePersonaIcon(selectedPersona.icon, selectedPersona.category)
    } else {
        Icons.Outlined.Psychology
    }

    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "PersonaChevronRotation"
    )

    // When dropdown opens, initialize expanded category to current persona's category
    LaunchedEffect(expanded) {
        if (expanded) {
            searchQuery = ""
            expandedCategory = selectedPersona?.category?.ifBlank { "General Debate" }
        }
    }

    val groupedPersonas = remember(availablePersonas) {
        availablePersonas.groupBy { it.category.ifBlank { "General Debate" } }
    }

    val filteredGroups = remember(groupedPersonas, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            groupedPersonas.entries.map { it.key to it.value }
        } else {
            groupedPersonas.mapNotNull { (category, personas) ->
                val matching = personas.filter { p ->
                    p.name.lowercase().contains(query) ||
                    p.role.lowercase().contains(query) ||
                    p.description.lowercase().contains(query) ||
                    category.lowercase().contains(query)
                }
                if (matching.isNotEmpty()) category to matching else null
            }
        }
    }

    Box(modifier = modifier) {
        // ── Dropdown Trigger Pill ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .heightIn(min = if (isCompact) 44.dp else minHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (selectedPersona != null) {
                PersonaIconView(
                    icon = selectedPersona.icon,
                    category = selectedPersona.category,
                    size = 15.dp,
                    tint = cc.textMuted
                )
            } else {
                Icon(
                    imageVector = selectedIcon,
                    contentDescription = null,
                    tint = cc.textMuted,
                    modifier = Modifier.size(15.dp)
                )
            }

            Text(
                text = selectedPersona?.name ?: "Default Persona",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (selectedPersona != null) FontWeight.Medium else FontWeight.Normal,
                    fontSize = fontSize
                ),
                color = if (selectedPersona != null) cc.textPrimary else cc.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = "Select Persona",
                tint = cc.textMuted.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }

        // ── Responsive Dropdown Surface ───────────────────────────────────────
        if (isCompact && expanded) {
            ModalBottomSheet(
                onDismissRequest = { expanded = false },
                containerColor = cc.panel,
                contentColor = cc.textPrimary,
                scrimColor = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(cc.border)
                    )
                }
            ) {
                PersonaDropdownContent(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    expandedCategory = expandedCategory,
                    onToggleCategory = { cat ->
                        expandedCategory = if (expandedCategory == cat) null else cat
                    },
                    filteredGroups = filteredGroups,
                    selectedPersonaId = selectedPersonaId,
                    onSelectPersona = {
                        expanded = false
                        onSelectPersona(it)
                    },
                    onManagePersonas = onManagePersonas?.let { action ->
                        {
                            expanded = false
                            action()
                        }
                    },
                    cc = cc,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 32.dp)
                )
            }
        } else if (!isCompact) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .widthIn(min = 340.dp, max = 390.dp)
                    .heightIn(max = 480.dp)
                    .background(cc.panel)
                    .border(BorderStroke(1.dp, cc.border), RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
                    .padding(vertical = 6.dp)
            ) {
                PersonaDropdownContent(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    expandedCategory = expandedCategory,
                    onToggleCategory = { cat ->
                        expandedCategory = if (expandedCategory == cat) null else cat
                    },
                    filteredGroups = filteredGroups,
                    selectedPersonaId = selectedPersonaId,
                    onSelectPersona = {
                        expanded = false
                        onSelectPersona(it)
                    },
                    onManagePersonas = onManagePersonas?.let { action ->
                        {
                            expanded = false
                            action()
                        }
                    },
                    cc = cc,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Reusable dropdown menu content displaying:
 * 1. Top Search Bar
 * 2. Default Persona option
 * 3. Expandable Grouping Accordion (or fully expanded matching groups during search)
 * 4. Manage Personas footer action
 */
@Composable
private fun PersonaDropdownContent(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    expandedCategory: String?,
    onToggleCategory: (String) -> Unit,
    filteredGroups: List<Pair<String, List<PredefinedPersona>>>,
    selectedPersonaId: String?,
    onSelectPersona: (PredefinedPersona?) -> Unit,
    onManagePersonas: (() -> Unit)?,
    cc: CcPalette,
    modifier: Modifier = Modifier
) {
    val isSearching = searchQuery.isNotBlank()
    val scrollState = rememberScrollState()

    Column(modifier = modifier) {
        // ── 1. Top Search Bar ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cc.panelAlt)
                .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.6f)), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = "Search",
                    tint = cc.textMuted,
                    modifier = Modifier.size(16.dp)
                )

                BasicTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = cc.textPrimary,
                        fontSize = 12.5.sp
                    ),
                    cursorBrush = SolidColor(cc.accent),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Search personas...",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                                color = cc.textMuted.copy(alpha = 0.6f)
                            )
                        }
                        innerTextField()
                    }
                )

                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = cc.textMuted,
                        modifier = Modifier
                            .size(15.dp)
                            .clip(CircleShape)
                            .clickable { onSearchQueryChange("") }
                    )
                }
            }
        }

        HorizontalDivider(color = cc.border.copy(alpha = 0.4f), thickness = 0.75.dp)

        // ── 2. Scrollable Body ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
        ) {
            // Option: Default Persona (always visible when not searching, or if matches query)
            val showDefault = !isSearching || "default persona".contains(searchQuery.trim().lowercase())
            if (showDefault) {
                val isDefaultSelected = selectedPersonaId == null || selectedPersonaId == "__none__"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDefaultSelected) cc.panelAlt else Color.Transparent)
                        .clickable { onSelectPersona(null) }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Psychology,
                            contentDescription = null,
                            tint = cc.textMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = "Default Persona",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (isDefaultSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    fontSize = 12.5.sp
                                ),
                                color = cc.textPrimary
                            )
                            Text(
                                text = "Standard debate demeanor, no persona injected",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    if (isDefaultSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = cc.textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                HorizontalDivider(color = cc.border.copy(alpha = 0.3f), thickness = 0.5.dp)
            }

            // Empty search state
            if (isSearching && filteredGroups.isEmpty() && !showDefault) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.SearchOff,
                            contentDescription = null,
                            tint = cc.textMuted.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No personas match \"$searchQuery\"",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = cc.textMuted
                        )
                    }
                }
            }

            // ── 3. Category Groupings ─────────────────────────────────────────
            filteredGroups.forEach { (category, personas) ->
                // During search: all matching groups are expanded!
                // Without search: only one group is expanded at a time (accordion)!
                val isGroupExpanded = isSearching || (expandedCategory == category)
                val containsSelected = personas.any { it.id == selectedPersonaId }

                val chevronRotation by animateFloatAsState(
                    targetValue = if (isGroupExpanded) 180f else 0f,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "GroupChevronRotation_$category"
                )

                // Category Header (Accordion clickable row)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .background(if (isGroupExpanded) cc.panelAlt.copy(alpha = 0.6f) else Color.Transparent)
                        .clickable(enabled = !isSearching) { onToggleCategory(category) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        // Category indicator dot if contains selected persona and collapsed
                        if (containsSelected && !isGroupExpanded) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(cc.textMuted)
                            )
                        }

                        Text(
                            text = category.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            ),
                            color = cc.textPrimary.copy(alpha = 0.85f)
                        )

                        // Count badge
                        Surface(
                            color = cc.panelAlt,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "${personas.size}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = cc.textMuted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }

                    // Chevron (hidden when searching since all are expanded)
                    if (!isSearching) {
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = if (isGroupExpanded) "Collapse" else "Expand",
                            tint = cc.textMuted,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer { rotationZ = chevronRotation }
                        )
                    }
                }

                // Category Items (Personas inside this group)
                AnimatedVisibility(
                    visible = isGroupExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(cc.panelAlt.copy(alpha = 0.25f))
                    ) {
                        personas.forEach { persona ->
                            val isSelected = persona.id == selectedPersonaId

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isSelected) cc.panelAlt else Color.Transparent)
                                    .clickable { onSelectPersona(persona) }
                                    .padding(start = 22.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    PersonaIconView(
                                        icon = persona.icon,
                                        category = persona.category,
                                        size = 20.dp,
                                        tint = cc.textMuted
                                    )

                                    Column {
                                        Text(
                                            text = persona.name,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                fontSize = 12.sp
                                            ),
                                            color = cc.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        val subtitle = persona.role.ifBlank { persona.description }
                                        if (subtitle.isNotBlank()) {
                                            Text(
                                                text = subtitle,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = cc.textMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = cc.textPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.5.dp)
            }

            // ── 4. Manage Personas Action Footer ─────────────────────────────
            if (onManagePersonas != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onManagePersonas() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = cc.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Manage & Edit Personas…",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.5.sp
                        ),
                        color = cc.textPrimary
                    )
                }
            }
        }
    }
}
