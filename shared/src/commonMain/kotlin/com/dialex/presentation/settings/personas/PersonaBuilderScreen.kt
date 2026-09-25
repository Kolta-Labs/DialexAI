package com.dialex.presentation.settings.personas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dialex.domain.model.PersonaChatMessage
import com.dialex.model.PredefinedPersona
import com.dialex.model.Provider
import com.dialex.model.RunMode
import com.dialex.model.buildComposedSystemPrompt
import com.dialex.presentation.setup.FilePicker
import com.dialex.theme.LocalCcColors
import com.dialex.ui.GradientButton
import com.dialex.ui.PersonaIconView
import com.dialex.ui.PredefinedPersonaIcons
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonaBuilderScreen(
    state: PersonaBuilderState,
    onIntent: (PersonaBuilderIntent) -> Unit,
    onBack: (() -> Unit)? = null,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cc = LocalCcColors.current

    // Dialog for JSON import
    if (state.isImportDialogOpen) {
        PersonaImportJsonDialog(
            importInputText = state.importInputText,
            importError = state.importError,
            onInputChanged = { onIntent(PersonaBuilderIntent.ImportInputChanged(it)) },
            onImport = { onIntent(PersonaBuilderIntent.ImportJson()) },
            onDismiss = { onIntent(PersonaBuilderIntent.DismissImportDialog) }
        )
    }

    if (!isCompact && state.isChatDrawerOpen) {
        // Wide screen: Side-by-side Form + AI Persona Assistant Chat
        Row(modifier = modifier.fillMaxSize().background(cc.bg)) {
            Box(modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                PersonaBuilderFormContent(
                    state = state,
                    onIntent = onIntent,
                    onBack = onBack,
                    isCompact = false,
                    modifier = Modifier.fillMaxSize()
                )
            }
            VerticalDivider(color = cc.border.copy(alpha = 0.5f), thickness = 1.dp)
            Box(modifier = Modifier.width(440.dp).fillMaxHeight().background(cc.panel)) {
                PersonaAiChatPanel(
                    state = state,
                    onIntent = onIntent,
                    isCompact = false,
                    onClose = { onIntent(PersonaBuilderIntent.ToggleChatDrawer(false)) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    } else {
        // Single pane form
        Box(modifier = modifier.fillMaxSize().background(cc.bg)) {
            PersonaBuilderFormContent(
                state = state,
                onIntent = onIntent,
                onBack = onBack,
                isCompact = isCompact,
                modifier = Modifier.fillMaxSize()
            )

            // Compact screen modal sheet for AI Persona Assistant Chat
            if (isCompact && state.isChatDrawerOpen) {
                Dialog(
                    onDismissRequest = { onIntent(PersonaBuilderIntent.ToggleChatDrawer(false)) },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(cc.bg)
                            .imePadding()
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        PersonaAiChatPanel(
                            state = state,
                            onIntent = onIntent,
                            isCompact = true,
                            onClose = { onIntent(PersonaBuilderIntent.ToggleChatDrawer(false)) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaBuilderFormContent(
    state: PersonaBuilderState,
    onIntent: (PersonaBuilderIntent) -> Unit,
    onBack: (() -> Unit)?,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
) {
    val cc = LocalCcColors.current
    val draft = state.draft
    val filePicker = remember { FilePicker() }
    val pickImage = filePicker.registerImagePicker { _, base64DataUrl ->
        onIntent(PersonaBuilderIntent.IconChanged(base64DataUrl))
    }

    val standardCategories = listOf(
        "Software Engineering",
        "Scientific Research",
        "Writing & Journalism",
        "Product & Strategy",
        "Legal & Governance",
        "Healthcare & Medicine",
        "General Debate"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isCompact) 16.dp else 24.dp, vertical = 16.dp)
    ) {
        // ── Top Bar ─────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (onBack != null || isCompact) {
                    IconButton(
                        onClick = onBack ?: { onIntent(PersonaBuilderIntent.Discard) },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cc.textPrimary
                        )
                    }
                }
                Text(
                    if (state.isEditing) "EDIT PERSONA" else "CREATE PERSONA",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cc.textPrimary,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Import JSON Button
                OutlinedButton(
                    onClick = { onIntent(PersonaBuilderIntent.OpenImportDialog) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        Icons.Outlined.FileDownload,
                        contentDescription = null,
                        tint = cc.textPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Import JSON", style = MaterialTheme.typography.labelSmall, color = cc.textPrimary)
                }

                // AI Persona Assistant Toggle Button
                Button(
                    onClick = { onIntent(PersonaBuilderIntent.ToggleChatDrawer()) },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isChatDrawerOpen) cc.accent else cc.panelAlt,
                        contentColor = if (state.isChatDrawerOpen) cc.textPrimary else cc.accent
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("AI Assistant", style = MaterialTheme.typography.labelSmall)
                }

                TextButton(
                    onClick = { onIntent(PersonaBuilderIntent.Discard) },
                    modifier = Modifier.heightIn(min = 34.dp)
                ) {
                    Text("Cancel")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (state.validationError != null) {
            Text(
                state.validationError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }

        // ── 1. Persona Icon & Avatar Assignment ──────────────────────────────────
        Surface(
            color = cc.panelAlt,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "PERSONA ICON & AVATAR",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                    color = cc.textMuted
                )
                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(cc.panelAlt)
                            .border(1.dp, cc.border, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        PersonaIconView(
                            icon = draft.icon,
                            category = draft.category,
                            size = 36.dp,
                            tint = cc.textMuted
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = pickImage,
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp), tint = cc.textPrimary)
                                Spacer(Modifier.width(6.dp))
                                Text("Upload Image", style = MaterialTheme.typography.labelMedium, color = cc.textPrimary)
                            }

                            if (draft.icon.startsWith("data:image") || draft.icon.length > 80) {
                                TextButton(
                                    onClick = { onIntent(PersonaBuilderIntent.IconChanged("")) },
                                    modifier = Modifier.height(36.dp)
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Clear Image", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Choose from the predefined set below or upload any PNG, JPG, or SVG image.",
                            style = MaterialTheme.typography.labelSmall,
                            color = cc.textMuted
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    "PREDEFINED ICONS",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                    color = cc.textMuted
                )
                Spacer(Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PredefinedPersonaIcons.forEach { opt ->
                        val isSelected = draft.icon.equals(opt.key, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) cc.panelAlt else cc.panel)
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (isSelected) cc.border else cc.border.copy(alpha = 0.5f)
                                    ),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onIntent(PersonaBuilderIntent.IconChanged(opt.key)) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = opt.icon,
                                contentDescription = opt.label,
                                tint = if (isSelected) cc.textPrimary else cc.textMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 2. Category / Profession ─────────────────────────────────────────────
        Text(
            "PROFESSION / DOMAIN CATEGORY",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            color = cc.textMuted
        )
        Spacer(Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            standardCategories.forEach { cat ->
                val isSelected = draft.category.equals(cat, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = { onIntent(PersonaBuilderIntent.CategoryChanged(cat)) },
                    label = { Text(cat, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = cc.panelAlt,
                        selectedLabelColor = cc.textPrimary,
                        containerColor = cc.panel,
                        labelColor = cc.textMuted
                    )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = draft.category,
            onValueChange = { onIntent(PersonaBuilderIntent.CategoryChanged(it)) },
            label = { Text("Category (Custom)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        // ── 3. Basic Details ─────────────────────────────────────────────────────
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onIntent(PersonaBuilderIntent.NameChanged(it)) },
            label = { Text("Persona Name (e.g. Socratic Skeptic) *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft.role,
            onValueChange = { onIntent(PersonaBuilderIntent.RoleChanged(it)) },
            label = { Text("Role Label (e.g. Devil's Advocate)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft.description,
            onValueChange = { onIntent(PersonaBuilderIntent.DescriptionChanged(it)) },
            label = { Text("Short Description") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        // ── 4. Persona Attributes (Optional) ───────────────────────────────────
        Text(
            "PERSONA ATTRIBUTES (OPTIONAL)",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            color = cc.textMuted
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = draft.roleAndPersona,
            onValueChange = { onIntent(PersonaBuilderIntent.RoleAndPersonaChanged(it)) },
            label = { Text("Role & Persona (Optional)") },
            placeholder = { Text("Define who this persona is and their high-level role...", color = cc.textMuted.copy(alpha = 0.6f)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft.coreExpertise,
            onValueChange = { onIntent(PersonaBuilderIntent.CoreExpertiseChanged(it)) },
            label = { Text("Core Expertise (Optional)") },
            placeholder = { Text("Specific domain knowledge, frameworks, and methodologies...", color = cc.textMuted.copy(alpha = 0.6f)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft.toneAndVoice,
            onValueChange = { onIntent(PersonaBuilderIntent.ToneAndVoiceChanged(it)) },
            label = { Text("Tone & Voice (Optional)") },
            placeholder = { Text("Manner of speaking, skepticism, formality, and rhetorical style...", color = cc.textMuted.copy(alpha = 0.6f)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = draft.objective,
            onValueChange = { onIntent(PersonaBuilderIntent.ObjectiveChanged(it)) },
            label = { Text("Objective (Optional)") },
            placeholder = { Text("Key fiduciary goal, debate mandate, or desired outcome...", color = cc.textMuted.copy(alpha = 0.6f)) },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        // ── 5. System Context / Prompt ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "SYSTEM CONTEXT / PROMPT",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
                color = cc.textMuted
            )
            val hasAttributes = draft.roleAndPersona.isNotBlank() || draft.coreExpertise.isNotBlank() ||
                draft.toneAndVoice.isNotBlank() || draft.objective.isNotBlank()
            if (hasAttributes) {
                TextButton(
                    onClick = { onIntent(PersonaBuilderIntent.AutoComposePrompt) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Auto-fill from Attributes", style = MaterialTheme.typography.labelSmall, color = cc.accent)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = draft.systemPrompt,
            onValueChange = { onIntent(PersonaBuilderIntent.SystemPromptChanged(it)) },
            label = { Text("System Context / Prompt") },
            placeholder = { Text("Full system instructions. If left blank, will auto-compose from above attributes.", color = cc.textMuted.copy(alpha = 0.6f)) },
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "DEFAULT STYLE MODIFIERS",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = cc.textMuted,
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = draft.ponytail,
                onCheckedChange = { onIntent(PersonaBuilderIntent.PonytailToggled(it)) },
                colors = CheckboxDefaults.colors(checkedColor = cc.accent),
            )
            Column {
                Text("Ponytail Mode", style = MaterialTheme.typography.bodyMedium, color = cc.textPrimary)
                Text("Structured, formal executive style by default", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
            }
        }

        Spacer(Modifier.height(16.dp))

        val hasValidPrompt = draft.systemPrompt.isNotBlank() ||
            draft.roleAndPersona.isNotBlank() ||
            draft.coreExpertise.isNotBlank() ||
            draft.toneAndVoice.isNotBlank() ||
            draft.objective.isNotBlank()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val clipboard = LocalClipboardManager.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val exportDraft = if (draft.systemPrompt.isBlank()) {
                            draft.copy(systemPrompt = buildComposedSystemPrompt(
                                roleAndPersona = draft.roleAndPersona,
                                coreExpertise = draft.coreExpertise,
                                toneAndVoice = draft.toneAndVoice,
                                objective = draft.objective
                            ))
                        } else draft
                        val jsonStr = Json {
                            prettyPrint = true
                            encodeDefaults = true
                        }.encodeToString(PredefinedPersona.serializer(), exportDraft)
                        clipboard.setText(AnnotatedString(jsonStr))
                    },
                    enabled = draft.name.isNotBlank(),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy JSON", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = { onIntent(PersonaBuilderIntent.OpenImportDialog) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import JSON", style = MaterialTheme.typography.labelMedium)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onIntent(PersonaBuilderIntent.Discard) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Discard", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(12.dp))
                GradientButton(
                    text = if (state.saveAsync.isLoading) "Saving…" else "Save Persona",
                    onClick = { onIntent(PersonaBuilderIntent.Save) },
                    enabled = draft.name.isNotBlank() && hasValidPrompt && !state.saveAsync.isLoading,
                    height = 36.dp
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// JSON Import Modal Dialog
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun PersonaImportJsonDialog(
    importInputText: String,
    importError: String?,
    onInputChanged: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cc = LocalCcColors.current
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Download, contentDescription = null, tint = cc.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Import Persona JSON", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Paste persona JSON directly, or paste output from any AI assistant. Code blocks (```json) are automatically detected and cleaned.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            val clipText = clipboard.getText()?.text.orEmpty()
                            if (clipText.isNotBlank()) onInputChanged(clipText)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp), tint = cc.accent)
                        Spacer(Modifier.width(4.dp))
                        Text("Paste from Clipboard", style = MaterialTheme.typography.labelSmall, color = cc.accent)
                    }
                }

                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = importInputText,
                    onValueChange = onInputChanged,
                    placeholder = {
                        Text(
                            "{\n  \"name\": \"Socratic Skeptic\",\n  \"role\": \"Epistemic Auditor\",\n  \"systemPrompt\": \"...\"\n}",
                            style = MaterialTheme.typography.bodySmall,
                            color = cc.textMuted.copy(alpha = 0.5f)
                        )
                    },
                    minLines = 8,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth()
                )

                if (importError != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        importError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            GradientButton(
                text = "Load into Editor",
                onClick = onImport,
                enabled = importInputText.isNotBlank(),
                height = 34.dp
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ──────────────────────────────────────────────────────────────────────────────
// AI Persona Assistant Chat Panel
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaAiChatPanel(
    state: PersonaBuilderState,
    onIntent: (PersonaBuilderIntent) -> Unit,
    isCompact: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cc = LocalCcColors.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.chatMessages.size) {
        if (state.chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(state.chatMessages.size - 1)
        }
    }

    val suggestionPrompts = listOf(
        "Adversarial IAM Penetration Tester",
        "Pragmatic Distributed Systems Lead",
        "Socratic Epistemologist",
        "Contrarian Venture Capitalist"
    )

    Column(modifier = modifier.fillMaxSize().padding(14.dp)) {
        // ── Chat Header ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = cc.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "AI PERSONA ASSISTANT",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = cc.textPrimary
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.chatMessages.isNotEmpty()) {
                    IconButton(
                        onClick = { onIntent(PersonaBuilderIntent.ClearChat) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Clear Chat", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Provider / Model / Mode Selection Bar ────────────────────────────────
        Surface(
            color = cc.panelAlt,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mode toggle chips (API vs CLI)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(
                            selected = state.selectedRunMode == RunMode.API,
                            onClick = { onIntent(PersonaBuilderIntent.SelectRunMode(RunMode.API)) },
                            label = { Text("API", fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                        FilterChip(
                            selected = state.selectedRunMode == RunMode.CLI,
                            onClick = { onIntent(PersonaBuilderIntent.SelectRunMode(RunMode.CLI)) },
                            label = { Text("CLI", fontSize = 11.sp) },
                            modifier = Modifier.height(28.dp)
                        )
                    }

                    // Provider Dropdown
                    Box {
                        OutlinedButton(
                            onClick = { providerMenuExpanded = true },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(state.selectedProvider.name, fontSize = 11.sp, color = cc.textPrimary)
                        }
                        DropdownMenu(
                            expanded = providerMenuExpanded,
                            onDismissRequest = { providerMenuExpanded = false }
                        ) {
                            Provider.entries.filter { it != Provider.CUSTOM }.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name, fontSize = 12.sp) },
                                    onClick = {
                                        onIntent(PersonaBuilderIntent.SelectProvider(p))
                                        providerMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Model Dropdown
                    Box {
                        OutlinedButton(
                            onClick = { modelMenuExpanded = true },
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                state.selectedModel.take(16),
                                fontSize = 11.sp,
                                color = cc.accent
                            )
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            state.availableModels.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m, fontSize = 12.sp) },
                                    onClick = {
                                        onIntent(PersonaBuilderIntent.SelectModel(m))
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // If CLI mode, command override
                if (state.selectedRunMode == RunMode.CLI) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.selectedCliCommand,
                        onValueChange = { onIntent(PersonaBuilderIntent.SelectCliCommand(it)) },
                        label = { Text("CLI Command (e.g. claude, gemini, ollama)", fontSize = 10.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ── Chat Transcript Area ────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.chatMessages.isEmpty()) {
                // Empty state guidance & suggestion chips
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = cc.accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Design Dialectic Personas with AI",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = cc.textPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tell me what role you want in your debate. I will generate full persona specifications, tone directives, and style toggles. You can load it directly into the editor with one click!",
                        style = MaterialTheme.typography.bodySmall,
                        color = cc.textMuted,
                        lineHeight = 16.sp
                    )
                    Spacer(Modifier.height(16.dp))

                    Text(
                        "TRY A PROMPT:",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                        color = cc.textMuted
                    )
                    Spacer(Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        suggestionPrompts.forEach { prompt ->
                            FilterChip(
                                selected = false,
                                onClick = { onIntent(PersonaBuilderIntent.SendChatMessage("Create a persona: $prompt")) },
                                label = { Text(prompt, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = cc.panelAlt,
                                    labelColor = cc.textPrimary
                                )
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.chatMessages, key = { it.id }) { msg ->
                        ChatMessageBubble(
                            message = msg,
                            onApplyPersona = { onIntent(PersonaBuilderIntent.ApplyPersonaFromChat(it)) }
                        )
                    }
                    if (state.isChatGenerating) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cc.accent)
                                Text("Crafting persona with ${state.selectedModel}…", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                            }
                        }
                    }
                }
            }
        }

        if (state.chatError != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                state.chatError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(8.dp))

        // Quick action: Review current draft
        if (state.draft.name.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { onIntent(PersonaBuilderIntent.InjectCurrentDraftToChat) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(13.dp), tint = cc.accent)
                    Spacer(Modifier.width(4.dp))
                    Text("Refine current editor draft with AI", fontSize = 11.sp, color = cc.accent)
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // ── Chat Input Bar ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("E.g. Make them skeptical and analytical...", fontSize = 12.sp, color = cc.textMuted.copy(alpha = 0.6f)) },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )

            Button(
                onClick = {
                    val textToSend = inputText
                    inputText = ""
                    onIntent(PersonaBuilderIntent.SendChatMessage(textToSend))
                },
                enabled = inputText.isNotBlank() && !state.isChatGenerating,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = cc.accent),
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Chat Bubble & Parsed Persona Preview Card
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ChatMessageBubble(
    message: PersonaChatMessage,
    onApplyPersona: (PredefinedPersona) -> Unit,
) {
    val cc = LocalCcColors.current
    val isUser = message.role.equals("user", ignoreCase = true)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Surface(
            color = if (isUser) cc.accent.copy(alpha = 0.15f) else cc.panelAlt,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, if (isUser) cc.accent.copy(alpha = 0.3f) else cc.border.copy(alpha = 0.6f)),
            modifier = Modifier.widthIn(max = 380.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isUser) "You" else "Persona Architect",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                    color = if (isUser) cc.accent else cc.textMuted
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textPrimary,
                    lineHeight = 18.sp
                )
            }
        }

        // Render Persona card if parsed
        if (message.parsedPersona != null) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = cc.panel,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, cc.accent),
                modifier = Modifier.widthIn(max = 380.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            message.parsedPersona.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = cc.textPrimary
                        )
                        Surface(
                            color = cc.panelAlt,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(2.dp)
                        ) {
                            Text(
                                message.parsedPersona.category,
                                fontSize = 10.sp,
                                color = cc.textMuted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (message.parsedPersona.role.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Role: ${message.parsedPersona.role}",
                            style = MaterialTheme.typography.labelSmall,
                            color = cc.accent
                        )
                    }

                    if (message.parsedPersona.description.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            message.parsedPersona.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = cc.textMuted,
                            maxLines = 3
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.parsedPersona.ponytail) {
                            Surface(
                                color = cc.accent.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("[PONYTAIL]", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = cc.accent, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    GradientButton(
                        text = "📥 Load into Editor",
                        onClick = { onApplyPersona(message.parsedPersona) },
                        height = 32.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
