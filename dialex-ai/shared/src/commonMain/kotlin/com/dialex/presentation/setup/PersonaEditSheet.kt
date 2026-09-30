package com.dialex.presentation.setup

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.PredefinedPersona
import com.dialex.model.PersonaSelectionResult
import com.dialex.theme.CcPalette
import com.dialex.theme.LocalCcColors

/**
 * A bottom sheet that lets the user review and optionally edit a selected persona
 * before (or after) it is applied to an agent seat.
 *
 * - If the user changes nothing: emits [PersonaSelectionResult.Stock].
 * - If the user edits name or instructions: emits [PersonaSelectionResult.Custom].
 * - If the base persona is null, opens with blank fields for a fully custom persona.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaEditSheet(
    basePersona: PredefinedPersona?,
    onApply: (PersonaSelectionResult) -> Unit,
    onDismiss: () -> Unit,
) {
    val cc = LocalCcColors.current

    var editedName by remember(basePersona) { mutableStateOf(basePersona?.name ?: "") }
    var editedRole by remember(basePersona) { mutableStateOf(basePersona?.role ?: "") }
    var editedRoleAndPersona by remember(basePersona) { mutableStateOf(basePersona?.roleAndPersona ?: "") }
    var editedCoreExpertise by remember(basePersona) { mutableStateOf(basePersona?.coreExpertise ?: "") }
    var editedToneAndVoice by remember(basePersona) { mutableStateOf(basePersona?.toneAndVoice ?: "") }
    var editedObjective by remember(basePersona) { mutableStateOf(basePersona?.objective ?: "") }
    var editedSystemPrompt by remember(basePersona) { mutableStateOf(basePersona?.systemPrompt ?: "") }
    var editedPonytail by remember(basePersona) { mutableStateOf(basePersona?.ponytail ?: false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    val isModified = remember(
        editedName, editedRole, editedSystemPrompt,
        editedRoleAndPersona, editedCoreExpertise, editedToneAndVoice, editedObjective,
        basePersona
    ) {
        basePersona == null ||
            editedName.trim() != basePersona.name ||
            editedRole.trim() != basePersona.role ||
            editedSystemPrompt.trim() != basePersona.systemPrompt ||
            editedRoleAndPersona.trim() != basePersona.roleAndPersona ||
            editedCoreExpertise.trim() != basePersona.coreExpertise ||
            editedToneAndVoice.trim() != basePersona.toneAndVoice ||
            editedObjective.trim() != basePersona.objective
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = cc.panel,
        contentColor = cc.textPrimary,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Column {
                Text(
                    text = if (basePersona != null) "Edit Persona" else "Create Custom Persona",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = cc.textPrimary
                )
                Text(
                    text = if (isModified)
                        "Changes will be saved as a custom persona on this seat"
                    else
                        "Tap Apply to use this persona as-is, or edit anything below",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = cc.textMuted
                )
            }

            HorizontalDivider(color = cc.border.copy(alpha = 0.4f), thickness = 0.75.dp)

            // Name
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Name",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = cc.textPrimary
                )
                BasicTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = cc.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(cc.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cc.panelAlt)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) { inner ->
                    if (editedName.isEmpty()) Text("Role name…", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = cc.textMuted.copy(alpha = 0.6f))
                    inner()
                }
            }

            // Role label
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Role Label",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = cc.textPrimary
                )
                BasicTextField(
                    value = editedRole,
                    onValueChange = { editedRole = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(cc.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cc.panelAlt)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) { inner ->
                    if (editedRole.isEmpty()) Text("Short subtitle shown under the name…", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cc.textMuted.copy(alpha = 0.6f))
                    inner()
                }
            }

            // Optional Attributes
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Role & Persona (Optional)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = cc.textPrimary
                )
                BasicTextField(
                    value = editedRoleAndPersona,
                    onValueChange = { editedRoleAndPersona = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(cc.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cc.panelAlt)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) { inner ->
                    if (editedRoleAndPersona.isEmpty()) Text("Define role identity and fiduciary mandate…", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cc.textMuted.copy(alpha = 0.6f))
                    inner()
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Core Expertise (Optional)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = cc.textPrimary
                )
                BasicTextField(
                    value = editedCoreExpertise,
                    onValueChange = { editedCoreExpertise = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(cc.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cc.panelAlt)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) { inner ->
                    if (editedCoreExpertise.isEmpty()) Text("Specialty frameworks and domain knowledge…", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cc.textMuted.copy(alpha = 0.6f))
                    inner()
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Tone & Voice (Optional)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = cc.textPrimary
                )
                BasicTextField(
                    value = editedToneAndVoice,
                    onValueChange = { editedToneAndVoice = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(cc.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cc.panelAlt)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) { inner ->
                    if (editedToneAndVoice.isEmpty()) Text("Rhetorical style, tone, and mannerisms…", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cc.textMuted.copy(alpha = 0.6f))
                    inner()
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Objective (Optional)",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = cc.textPrimary
                )
                BasicTextField(
                    value = editedObjective,
                    onValueChange = { editedObjective = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary, fontSize = 13.sp),
                    cursorBrush = SolidColor(cc.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cc.panelAlt)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) { inner ->
                    if (editedObjective.isEmpty()) Text("Key outcome or debate objective…", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = cc.textMuted.copy(alpha = 0.6f))
                    inner()
                }
            }

            // System Context / Prompt
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "System Context / Prompt",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                        color = cc.textPrimary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val hasAttrs = editedRoleAndPersona.isNotBlank() || editedCoreExpertise.isNotBlank() ||
                            editedToneAndVoice.isNotBlank() || editedObjective.isNotBlank()
                        if (hasAttrs) {
                            Text(
                                "Auto-fill",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = cc.accent,
                                modifier = Modifier.clickable {
                                    editedSystemPrompt = com.dialex.model.buildComposedSystemPrompt(
                                        roleAndPersona = editedRoleAndPersona,
                                        coreExpertise = editedCoreExpertise,
                                        toneAndVoice = editedToneAndVoice,
                                        objective = editedObjective,
                                        systemContextPrompt = editedSystemPrompt
                                    )
                                }
                            )
                        }
                        if (basePersona != null && editedSystemPrompt.trim() != basePersona.systemPrompt) {
                            Text(
                                "Reset",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = cc.accent,
                                modifier = Modifier.clickable { editedSystemPrompt = basePersona.systemPrompt }
                            )
                        }
                    }
                }
                BasicTextField(
                    value = editedSystemPrompt,
                    onValueChange = { editedSystemPrompt = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = cc.textPrimary, fontSize = 12.sp, lineHeight = 17.sp
                    ),
                    cursorBrush = SolidColor(cc.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp, max = 280.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cc.panelAlt)
                        .padding(12.dp)
                ) { inner ->
                    if (editedSystemPrompt.isEmpty()) Text("Describe how this participant should think, speak, and respond…", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp), color = cc.textMuted.copy(alpha = 0.6f))
                    inner()
                }
            }

            // Style toggles
            Row(modifier = Modifier.fillMaxWidth()) {
                StyleToggleChip("Ponytail", "Structured executive communication style", editedPonytail, { editedPonytail = it }, Modifier.fillMaxWidth(), cc)
            }

            // Advanced (collapsible note)
            Row(
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { advancedExpanded = !advancedExpanded }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = cc.textMuted, modifier = Modifier.size(14.dp))
                Icon(Icons.Outlined.Tune, null, tint = cc.textMuted, modifier = Modifier.size(12.dp))
                Text("Advanced", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = cc.textMuted)
            }

            AnimatedVisibility(visible = advancedExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Sampling parameters and max tokens are set per-agent in the agent card's Sampling section.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                        color = cc.textMuted,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }

            // Action buttons
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, cc.border),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = cc.panelAlt, contentColor = cc.textPrimary),
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel", fontSize = 13.sp) }

                Button(
                    onClick = {
                        val effectivePrompt = if (editedSystemPrompt.isNotBlank()) {
                            editedSystemPrompt.trim()
                        } else {
                            com.dialex.model.buildComposedSystemPrompt(
                                roleAndPersona = editedRoleAndPersona,
                                coreExpertise = editedCoreExpertise,
                                toneAndVoice = editedToneAndVoice,
                                objective = editedObjective
                            )
                        }
                        val result = when {
                            basePersona == null || isModified -> PersonaSelectionResult.Custom(
                                basePersonaId = basePersona?.id ?: "custom",
                                displayName = editedName.trim().ifBlank { basePersona?.name ?: "Custom Role" },
                                role = editedRole.trim().ifBlank { editedRoleAndPersona.lines().firstOrNull { it.isNotBlank() }?.take(50).orEmpty() },
                                systemPrompt = effectivePrompt,
                                ponytail = editedPonytail,
                                roleAndPersona = editedRoleAndPersona.trim(),
                                coreExpertise = editedCoreExpertise.trim(),
                                toneAndVoice = editedToneAndVoice.trim(),
                                objective = editedObjective.trim(),
                            )
                            else -> PersonaSelectionResult.Stock(basePersona)
                        }
                        onApply(result)
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.White),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isModified) "Apply (Custom)" else "Apply", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun StyleToggleChip(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    cc: CcPalette = LocalCcColors.current,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (checked) cc.accent.copy(alpha = if (cc.isDark) 0.15f else 0.08f) else cc.panelAlt,
        border = BorderStroke(0.75.dp, if (checked) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.5f)),
        modifier = modifier.clip(RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier.clickable { onCheckedChange(!checked) }.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp), color = if (checked) cc.accent else cc.textPrimary)
                Text(subtitle, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = cc.textMuted)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.size(width = 36.dp, height = 20.dp),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = cc.accent,
                    checkedTrackColor = cc.accent.copy(alpha = 0.3f),
                    uncheckedThumbColor = cc.textMuted,
                    uncheckedTrackColor = cc.border
                )
            )
        }
    }
}
