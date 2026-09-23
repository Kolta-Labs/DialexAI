package com.dialex.presentation.setup

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.Discussion
import com.dialex.model.GeneralDebatePersonas
import com.dialex.model.PredefinedPersona
import com.dialex.model.PersonaSelectionResult
import com.dialex.theme.LocalCcColors
import com.dialex.ui.PersonaIconView

/**
 * Full-screen persona picker with two tabs:
 *  - "Roles" (Tab 1, default): General-purpose debate roles everyone understands.
 *    Flat grid, plain-language names, no category headers.
 *  - "By Domain" (Tab 2): Profession-specific personas grouped by category accordion.
 *
 * Selecting a persona attaches it as a badge (no system-prompt dump).
 * A "Customise →" option on each card opens the [PersonaEditSheet] before applying.
 */
@Composable
fun PersonaPickerScreen(
    agentIndex: Int,
    currentPersonaId: String?,
    availablePersonas: List<PredefinedPersona>,
    onSelectPersona: (PersonaSelectionResult) -> Unit,
    onDismiss: () -> Unit,
    discussions: List<Discussion> = emptyList(),
    onOpenPersonaBuilder: (() -> Unit)? = null,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) } // 0 = Roles, 1 = By Domain
    var showAllRolesPage by remember { mutableStateOf(false) }
    var showGalleryDialog by remember { mutableStateOf(false) }

    // Edit sheet state
    var editSheetPersona by remember { mutableStateOf<PredefinedPersona?>(null) }
    var showEditSheet by remember { mutableStateOf(false) }

    // Master list of all personas deduplicated
    val allPersonas = remember(availablePersonas) {
        (GeneralDebatePersonas + availablePersonas).distinctBy { it.id }
    }

    // Frequency ranking: compute tally from past discussions, blended with curated defaults
    val rankedRoles = remember(allPersonas, discussions) {
        val counts = mutableMapOf<String, Int>()
        for (disc in discussions) {
            for (agent in disc.config.agents) {
                val pId = agent.personaId
                if (!pId.isNullOrBlank() && pId != "__none__") {
                    counts[pId] = (counts[pId] ?: 0) + 1
                }
            }
        }

        // Curated baseline priority for book writing & publishing and debate roles
        val defaultPriority = listOf(
            "bwp_dev_editor",
            "bwp_line_editor",
            "bwp_acquisitions_editor",
            "bwp_literary_agent",
            "bwp_character_psychologist",
            "bwp_worldbuilding_architect",
            "bwp_pacing_doctor",
            "bwp_nonfiction_architect",
            "bwp_copyeditor_sentinel",
            "bwp_reader_advocate",
            "sys_facilitator",
            "sys_devils_advocate",
            "sys_pragmatist",
            "sys_optimist",
            "sys_risk_analyst",
            "sys_ethicist",
            "sys_socratic",
            "se_lead_moderator",
            "prod_principal_pm",
            "sys_historian"
        )

        allPersonas.sortedWith(
            compareByDescending<PredefinedPersona> { counts[it.id] ?: 0 }
                .thenBy {
                    val idx = defaultPriority.indexOf(it.id)
                    if (idx != -1) idx else 999
                }
                .thenBy { it.name }
        )
    }

    val top10Roles = remember(rankedRoles) {
        rankedRoles.take(10)
    }

    // Profession-specific personas for Tab 2 (By Domain):
    // MUST contain General Debate personas as well, with "General Debate" guaranteed as the first category!
    val domainGroups: List<Pair<String, List<PredefinedPersona>>> = remember(allPersonas) {
        val grouped = allPersonas.groupBy {
            if (it.category.isBlank()) "General Debate" else it.category
        }
        val generalEntry = grouped["General Debate"]
        val otherEntries = grouped.filterKeys { it != "General Debate" }
            .entries
            .sortedBy { it.key }

        buildList {
            if (generalEntry != null) {
                add("General Debate" to generalEntry)
            }
            addAll(otherEntries.map { it.key to it.value })
        }
    }

    // Search query
    val query = searchQuery.trim().lowercase()
    val isSearching = query.isNotBlank()

    val filteredAllPersonas = remember(allPersonas, query) {
        if (query.isBlank()) allPersonas
        else allPersonas.filter { p ->
            p.name.lowercase().contains(query) ||
                p.description.lowercase().contains(query) ||
                p.role.lowercase().contains(query) ||
                p.category.lowercase().contains(query)
        }
    }

    // When searching, show combined results ignoring tab
    val showCombinedSearch = isSearching

    val columns = if (isCompact) 1 else 2
    val hPad = if (isCompact) 16.dp else 20.dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cc.bg)
    ) {
        // ── Top Bar ─────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(56.dp)
                .padding(horizontal = if (isCompact) 12.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconButton(
                    onClick = {
                        if (showAllRolesPage) {
                            showAllRolesPage = false
                        } else {
                            onDismiss()
                        }
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = if (showAllRolesPage) "Back to Top 10" else "Back",
                        tint = cc.textPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        if (showAllRolesPage) "All Roles & Personas" else "Pick a Role — Agent ${agentIndex + 1}",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (isCompact) 15.sp else 16.sp
                        ),
                        color = cc.textPrimary
                    )
                    Text(
                        if (showAllRolesPage) "Browse the complete role catalog or filter by search." else "Choose a voice for this seat. You can always tweak it after.",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = cc.textMuted
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showGalleryDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = cc.panelAlt, contentColor = cc.accent),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Outlined.Language, null, tint = cc.accent, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Gallery", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp))
                }

                if (onOpenPersonaBuilder != null) {
                    OutlinedButton(
                        onClick = onOpenPersonaBuilder,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, cc.border),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = cc.panelAlt, contentColor = cc.textPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Outlined.Add, null, tint = cc.textPrimary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Create Persona", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp))
                    }
                }
            }
        }

        HorizontalDivider(color = cc.border.copy(alpha = 0.4f), thickness = 0.75.dp)

        // ── Search + Tabs Bar ─────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(cc.panel)
                .padding(horizontal = if (isCompact) 16.dp else 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Search
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(cc.panelAlt)
                    .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Search, "Search", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cc.textPrimary, fontSize = 13.5.sp),
                        cursorBrush = SolidColor(cc.accent),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (searchQuery.isEmpty()) {
                                Text("Search roles and personas…", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = cc.textMuted.copy(alpha = 0.6f))
                            }
                            inner()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            Icons.Default.Clear,
                            "Clear",
                            tint = cc.textMuted,
                            modifier = Modifier.size(15.dp).clip(CircleShape).clickable { searchQuery = "" }
                        )
                    }
                }
            }

            // Tabs (hidden when searching or when on the dedicated All Roles page)
            if (!isSearching && !showAllRolesPage) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TabPill(
                        text = "🎭  Roles",
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        cc = cc
                    )
                    TabPill(
                        text = "🔬  By Domain",
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        cc = cc
                    )
                }
            } else if (isSearching) {
                // Search hint
                Text(
                    "Searching across all ${allPersonas.size} personas",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = cc.textMuted
                )
            } else if (showAllRolesPage) {
                // All roles page count indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "All ${allPersonas.size} available roles and personas",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = cc.textMuted
                    )
                    Text(
                        "← Back to Top 10",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                        color = cc.accent,
                        modifier = Modifier.clickable { showAllRolesPage = false }
                    )
                }
            }
        }

        HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)

        // ── Content Grid ─────────────────────────────────────────────────────
        if (showCombinedSearch) {
            // Search results: combined flat list across all personas
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().padding(horizontal = hPad),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (filteredAllPersonas.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptySearchState(query = searchQuery, cc = cc)
                    }
                } else {
                    items(filteredAllPersonas, key = { it.id }) { persona ->
                        RoleCard(
                            persona = persona,
                            isSelected = persona.id == currentPersonaId,
                            onSelect = { onSelectPersona(PersonaSelectionResult.Stock(persona)); onDismiss() },
                            onCustomise = { editSheetPersona = persona; showEditSheet = true }
                        )
                    }
                }
            }
        } else if (showAllRolesPage) {
            // ── All Roles Page (Reached via "See More >>") ───────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().padding(horizontal = hPad),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // "No persona" default card at top
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DefaultPersonaCard(
                        isSelected = currentPersonaId == null || currentPersonaId == "__none__",
                        onSelect = { onSelectPersona(PersonaSelectionResult.None); onDismiss() },
                        cc = cc
                    )
                }

                if (rankedRoles.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyCatalogState(
                            onOpenGallery = { showGalleryDialog = true },
                            onOpenPersonaBuilder = onOpenPersonaBuilder,
                            cc = cc
                        )
                    }
                } else {
                    items(rankedRoles, key = { it.id }) { persona ->
                        RoleCard(
                            persona = persona,
                            isSelected = persona.id == currentPersonaId,
                            onSelect = { onSelectPersona(PersonaSelectionResult.Stock(persona)); onDismiss() },
                            onCustomise = { editSheetPersona = persona; showEditSheet = true }
                        )
                    }
                }
            }
        } else if (selectedTab == 0) {
            // ── Tab 1: Roles (Top 10 by Frequency + "See More >>") ──────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().padding(horizontal = hPad),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // "No persona" default card
                item(span = { GridItemSpan(maxLineSpan) }) {
                    DefaultPersonaCard(
                        isSelected = currentPersonaId == null || currentPersonaId == "__none__",
                        onSelect = { onSelectPersona(PersonaSelectionResult.None); onDismiss() },
                        cc = cc
                    )
                }

                if (top10Roles.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyCatalogState(
                            onOpenGallery = { showGalleryDialog = true },
                            onOpenPersonaBuilder = onOpenPersonaBuilder,
                            cc = cc
                        )
                    }
                } else {
                    items(top10Roles, key = { it.id }) { persona ->
                        RoleCard(
                            persona = persona,
                            isSelected = persona.id == currentPersonaId,
                            onSelect = { onSelectPersona(PersonaSelectionResult.Stock(persona)); onDismiss() },
                            onCustomise = { editSheetPersona = persona; showEditSheet = true }
                        )
                    }

                    // Subtle text button "See More >>"
                    if (allPersonas.size > 10) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TextButton(
                                    onClick = { showAllRolesPage = true },
                                    colors = ButtonDefaults.textButtonColors(contentColor = cc.accent),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "See More (${allPersonas.size} roles) >>",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.5.sp
                                        ),
                                        color = cc.accent
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // ── Tab 2: By Domain (General Debate is FIRST!) ─────────────────
            var expandedCategory by remember { mutableStateOf<String?>(null) }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().padding(horizontal = hPad),
                contentPadding = PaddingValues(top = 16.dp, bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                if (domainGroups.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyCatalogState(
                            onOpenGallery = { showGalleryDialog = true },
                            onOpenPersonaBuilder = onOpenPersonaBuilder,
                            cc = cc
                        )
                    }
                } else {
                    domainGroups.forEach { (category, personas) ->
                        val isExpanded = expandedCategory == category
                        val containsSelected = personas.any { it.id == currentPersonaId }

                        // Category header — full-width always
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            CategoryHeader(
                                category = category,
                                count = personas.size,
                                isExpanded = isExpanded,
                                containsSelected = containsSelected,
                                onClick = { expandedCategory = if (isExpanded) null else category },
                                cc = cc
                            )
                        }

                        // Persona cards inside the expanded group
                        if (isExpanded) {
                            items(personas, key = { it.id }) { persona ->
                                RoleCard(
                                    persona = persona,
                                    isSelected = persona.id == currentPersonaId,
                                    onSelect = { onSelectPersona(PersonaSelectionResult.Stock(persona)); onDismiss() },
                                    onCustomise = { editSheetPersona = persona; showEditSheet = true }
                                )
                            }
                            // Spacer after group
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Edit sheet overlay
    if (showEditSheet) {
        PersonaEditSheet(
            basePersona = editSheetPersona,
            onApply = { result ->
                showEditSheet = false
                editSheetPersona = null
                onSelectPersona(result)
                onDismiss()
            },
            onDismiss = {
                showEditSheet = false
                editSheetPersona = null
            }
        )
    }

    // Community Gallery overlay
    if (showGalleryDialog) {
        val installedIds = remember(allPersonas) { allPersonas.map { it.id }.toSet() }
        com.dialex.presentation.settings.personas.gallery.PersonaGalleryDialog(
            installedPersonaIds = installedIds,
            onInstallPersona = { galleryPersona ->
                onSelectPersona(PersonaSelectionResult.Stock(galleryPersona))
                showGalleryDialog = false
                onDismiss()
            },
            onDismiss = { showGalleryDialog = false }
        )
    }
}

// ── Sub-components ───────────────────────────────────────────────────────────

@Composable
private fun TabPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    cc: com.dialex.theme.CcPalette,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (selected) cc.accent.copy(alpha = if (cc.isDark) 0.2f else 0.12f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.5f)),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { onClick() }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 13.sp
            ),
            color = if (selected) cc.accent else cc.textMuted,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun DefaultPersonaCard(
    isSelected: Boolean,
    onSelect: () -> Unit,
    cc: com.dialex.theme.CcPalette,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = cc.panel),
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) cc.accent else cc.border.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = cc.panelAlt, modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Psychology, null, tint = cc.textMuted, modifier = Modifier.size(18.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "No Role — Baseline",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                        color = cc.textPrimary
                    )
                    Text(
                        "Uses the model's default behaviour, no role injected.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = cc.textMuted
                    )
                }
            }
            if (isSelected) {
                Surface(shape = CircleShape, color = cc.accent, modifier = Modifier.size(20.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleCard(
    persona: PredefinedPersona,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onCustomise: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cc = LocalCcColors.current

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = cc.panel),
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) cc.accent else cc.border.copy(alpha = 0.5f)
        ),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = cc.panelAlt,
                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                        modifier = Modifier.size(32.dp)
                    ) {
                            PersonaIconView(
                                icon = persona.icon,
                                category = persona.category,
                                size = 16.dp,
                                tint = cc.textPrimary
                            )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = persona.name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                            color = cc.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sub = persona.role.ifBlank { persona.category }
                        if (sub.isNotBlank()) {
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = cc.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (isSelected) {
                    Surface(shape = CircleShape, color = cc.accent, modifier = Modifier.size(20.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, "Selected", tint = Color.White, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            // Description
            if (persona.description.isNotBlank()) {
                Text(
                    text = persona.description,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.5.sp),
                    color = cc.textMuted,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSelect,
                    shape = RoundedCornerShape(7.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) cc.accent else cc.panelAlt,
                        contentColor = if (isSelected) Color.White else cc.textPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(32.dp)
                ) {
                    Text(
                        text = if (isSelected) "✓ Selected" else "Select",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                TextButton(
                    onClick = onCustomise,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        "Customise →",
                        fontSize = 11.5.sp,
                        color = cc.textMuted,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    category: String,
    count: Int,
    isExpanded: Boolean,
    containsSelected: Boolean,
    onClick: () -> Unit,
    cc: com.dialex.theme.CcPalette,
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "CategoryChevron_$category"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (containsSelected && !isExpanded) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(cc.accent)
                )
            }
            Text(
                text = category,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                color = cc.textPrimary
            )
            Surface(color = cc.panelAlt, shape = RoundedCornerShape(10.dp)) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = cc.textMuted,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Icon(
            Icons.Default.ArrowDropDown,
            contentDescription = if (isExpanded) "Collapse" else "Expand",
            tint = cc.textMuted,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation }
        )
    }
    HorizontalDivider(color = cc.border.copy(alpha = 0.3f), thickness = 0.5.dp)
}

@Composable
private fun EmptySearchState(
    query: String,
    cc: com.dialex.theme.CcPalette,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.SearchOff, null, tint = cc.textMuted.copy(alpha = 0.5f), modifier = Modifier.size(34.dp))
            Text("No roles match \"$query\"", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = cc.textMuted)
        }
    }
}

@Composable
private fun EmptyCatalogState(
    onOpenGallery: () -> Unit,
    onOpenPersonaBuilder: (() -> Unit)?,
    cc: com.dialex.theme.CcPalette,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = cc.panelAlt,
                border = BorderStroke(1.dp, cc.border),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Person,
                        contentDescription = null,
                        tint = cc.accent,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Text(
                "No Personas Available",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                color = cc.textPrimary
            )

            Text(
                "No personas are bundled into this build. You can install personas from the community gallery or build custom personas.",
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                color = cc.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 400.dp)
            )

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onOpenGallery,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = cc.accent.copy(alpha = 0.08f),
                        contentColor = cc.accent
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse Gallery", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp))
                }

                if (onOpenPersonaBuilder != null) {
                    OutlinedButton(
                        onClick = onOpenPersonaBuilder,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, cc.border),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = cc.panelAlt,
                            contentColor = cc.textPrimary
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Create Persona", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp))
                    }
                }
            }
        }
    }
}

