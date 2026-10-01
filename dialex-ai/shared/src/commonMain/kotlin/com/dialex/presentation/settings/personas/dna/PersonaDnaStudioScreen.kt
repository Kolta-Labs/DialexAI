package com.dialex.presentation.settings.personas.dna

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.CombatStance
import com.dialex.domain.model.PersonaDNA
import com.dialex.domain.model.PrimaryReasoningMode
import com.dialex.domain.model.SynthesisStyle
import com.dialex.theme.LocalCcColors

@Composable
fun PersonaDnaStudioScreen(
    state: PersonaDnaState,
    onIntent: (PersonaDnaIntent) -> Unit,
    onBack: (() -> Unit)? = null,
    onApplyToPersona: ((PersonaDNA, String?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current

    // Import/Export Modal Dialog
    if (state.isImportExportDialogOpen) {
        DnaImportExportDialog(
            mode = state.importExportMode,
            format = state.importExportFormat,
            text = state.importExportText,
            error = state.importExportError,
            onTextChanged = { onIntent(PersonaDnaIntent.ImportExportTextChanged(it)) },
            onFormatChanged = { onIntent(PersonaDnaIntent.SetImportExportFormat(it)) },
            onConfirmImport = { onIntent(PersonaDnaIntent.ExecuteImport) },
            onDismiss = { onIntent(PersonaDnaIntent.DismissImportExportDialog) }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(cc.bg)
    ) {
        // ── Top Bar ─────────────────────────────────────────────────────────────
        Surface(
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cc.textPrimary)
                        }
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "8-LAYER COGNITIVE DNA STUDIO",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = cc.textPrimary
                            )
                            Surface(
                                color = cc.accent.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    "MMOS v1.0",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = cc.accent,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Text(
                            (state.dna.name.ifBlank { "Persona DNA" }) + " • " + state.dna.coreIdentity.title.ifBlank { state.dna.role },
                            style = MaterialTheme.typography.labelSmall,
                            color = cc.textMuted
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Import MMOS Button
                    OutlinedButton(
                        onClick = { onIntent(PersonaDnaIntent.OpenImportExportDialog(ImportExportMode.IMPORT, DnaFormat.YAML)) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = cc.textPrimary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Import MMOS", style = MaterialTheme.typography.labelSmall, color = cc.textPrimary)
                    }

                    // Export MMOS Button
                    OutlinedButton(
                        onClick = { onIntent(PersonaDnaIntent.OpenImportExportDialog(ImportExportMode.EXPORT, DnaFormat.YAML)) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = cc.textPrimary, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export MMOS", style = MaterialTheme.typography.labelSmall, color = cc.textPrimary)
                    }

                    // Compile Mandate Prompt Button
                    Button(
                        onClick = { onIntent(PersonaDnaIntent.CompilePrompt) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = cc.accent),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp),
                        enabled = !state.isCompiling
                    ) {
                        if (state.isCompiling) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = cc.bg, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Compile Mandate", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }

                    // Apply to Persona if callback provided
                    if (onApplyToPersona != null) {
                        Button(
                            onClick = { onApplyToPersona(state.dna, state.compiledPrompt) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = cc.agentThird),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Apply DNA", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // ── Tab Bar ─────────────────────────────────────────────────────────────
        TabRow(
            selectedTabIndex = state.activeTab.ordinal,
            containerColor = cc.panelAlt,
            contentColor = cc.accent,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[state.activeTab.ordinal]),
                    color = cc.accent,
                    height = 2.dp
                )
            }
        ) {
            DnaStudioTab.entries.forEach { tab ->
                Tab(
                    selected = state.activeTab == tab,
                    onClick = { onIntent(PersonaDnaIntent.SelectTab(tab)) },
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(tab.icon, fontSize = 14.sp)
                            Text(
                                tab.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (state.activeTab == tab) FontWeight.Bold else FontWeight.Normal,
                                color = if (state.activeTab == tab) cc.accent else cc.textMuted
                            )
                        }
                    }
                )
            }
        }

        // ── Tab Body Content ────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (state.activeTab) {
                DnaStudioTab.CORE_IDENTITY -> CoreIdentityTab(state = state, onIntent = onIntent)
                DnaStudioTab.EPISTEMIC_BIAS -> EpistemicBiasTab(state = state, onIntent = onIntent)
                DnaStudioTab.HEURISTICS_TABOOS -> HeuristicsTaboosTab(state = state, onIntent = onIntent)
                DnaStudioTab.POSTURE_SYNTHESIS -> AdversarialSynthesisTab(state = state, onIntent = onIntent)
                DnaStudioTab.COMPILED_PROMPT -> CompiledPromptTab(state = state, onIntent = onIntent)
            }
        }
    }
}
// 1. Core Identity & Domain Tab
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoreIdentityTab(
    state: PersonaDnaState,
    onIntent: (PersonaDnaIntent) -> Unit
) {
    val cc = LocalCcColors.current
    var newCredentialText by remember { mutableStateOf("") }
    var newStandardText by remember { mutableStateOf("") }
    var newRfcText by remember { mutableStateOf("") }
    var newLexiconText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            color = cc.panelAlt,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "LAYER 1: CORE COGNITIVE IDENTITY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = cc.accent
                )
                Text(
                    "Defines primal persona framing, authority background, credentials, and domain specialization.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )

                OutlinedTextField(
                    value = state.dna.coreIdentity.title,
                    onValueChange = { onIntent(PersonaDnaIntent.UpdateTitle(it)) },
                    label = { Text("Title / Professional Designation") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = state.dna.coreIdentity.domainAuthority,
                    onValueChange = { onIntent(PersonaDnaIntent.UpdateDomainAuthority(it)) },
                    label = { Text("Domain Authority (e.g., Distributed systems, fault tolerance, reliability engineering)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = state.dna.coreIdentity.background,
                    onValueChange = { onIntent(PersonaDnaIntent.UpdateBackground(it)) },
                    label = { Text("Intellectual & Engineering Background") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                // Credentials
                Text("Authoritative Credentials", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.dna.coreIdentity.credentials.forEach { cred ->
                        FilterChip(
                            selected = true,
                            onClick = { onIntent(PersonaDnaIntent.RemoveCredential(cred)) },
                            label = { Text(cred) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.accent.copy(alpha = 0.15f),
                                selectedLabelColor = cc.accent
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newCredentialText,
                        onValueChange = { newCredentialText = it },
                        placeholder = { Text("Add credential (e.g. PhD Distributed Systems, RFC Author)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newCredentialText.isNotBlank()) {
                                onIntent(PersonaDnaIntent.AddCredential(newCredentialText.trim()))
                                newCredentialText = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }
        }

        Surface(
            color = cc.panelAlt,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "LAYER 6: DOMAIN ONTOLOGY & SEMANTIC BOUNDS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = cc.accent
                )
                Text(
                    "Mandatory standards, RFCs, and specialized lexicon enforced during debate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )

                // Mandatory Standards
                Text("Mandatory Standards & Theorems", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.dna.domainOntology.mandatoryStandards.forEach { std ->
                        FilterChip(
                            selected = true,
                            onClick = { onIntent(PersonaDnaIntent.RemoveMandatoryStandard(std)) },
                            label = { Text(std) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.accent.copy(alpha = 0.15f),
                                selectedLabelColor = cc.accent
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newStandardText,
                        onValueChange = { newStandardText = it },
                        placeholder = { Text("Add standard (e.g. CAP Theorem, PACELC, ACID)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newStandardText.isNotBlank()) {
                                onIntent(PersonaDnaIntent.AddMandatoryStandard(newStandardText.trim()))
                                newStandardText = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Authoritative RFCs
                Text("Authoritative RFCs & Protocols", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.dna.domainOntology.authoritativeRFCs.forEach { rfc ->
                        FilterChip(
                            selected = true,
                            onClick = { onIntent(PersonaDnaIntent.RemoveAuthoritativeRFC(rfc)) },
                            label = { Text(rfc) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.agentThird.copy(alpha = 0.15f),
                                selectedLabelColor = cc.agentThird
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newRfcText,
                        onValueChange = { newRfcText = it },
                        placeholder = { Text("Add RFC (e.g. RFC-793, RFC-7540)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newRfcText.isNotBlank()) {
                                onIntent(PersonaDnaIntent.AddAuthoritativeRFC(newRfcText.trim()))
                                newRfcText = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Specialized Lexicon
                Text("Specialized Domain Lexicon", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.dna.domainOntology.specializedLexicon.forEach { term ->
                        FilterChip(
                            selected = true,
                            onClick = { onIntent(PersonaDnaIntent.RemoveSpecializedLexicon(term)) },
                            label = { Text(term) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.agentFifth.copy(alpha = 0.15f),
                                selectedLabelColor = cc.agentFifth
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newLexiconText,
                        onValueChange = { newLexiconText = it },
                        placeholder = { Text("Add term (e.g. failure-domain, split-brain)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newLexiconText.isNotBlank()) {
                                onIntent(PersonaDnaIntent.AddSpecializedLexicon(newLexiconText.trim()))
                                newLexiconText = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Enforce Formal Citations", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                        Text("Mandate explicit citation of RFCs/Theorems in all assertions", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                    }
                    Switch(
                        checked = state.dna.domainOntology.enforceFormalCitations,
                        onCheckedChange = { onIntent(PersonaDnaIntent.UpdateEnforceFormalCitations(it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = cc.accent, checkedTrackColor = cc.accent.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}
// 2. Epistemic Bias & Vector Tab
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EpistemicBiasTab(
    state: PersonaDnaState,
    onIntent: (PersonaDnaIntent) -> Unit
) {
    val cc = LocalCcColors.current
    var newDeviceText by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Radar Spider Visualizer
        Box(modifier = Modifier.weight(1.1f)) {
            DnaRadarVisualizer(
                bias = state.dna.epistemicBias,
                tenacityScore = state.dna.adversarialPosture.tenacityScore,
                onRigorChanged = { onIntent(PersonaDnaIntent.UpdateRigorThreshold(it)) },
                onPracticeChanged = { onIntent(PersonaDnaIntent.UpdateTheoryVsPractice(it)) },
                onProvenanceChanged = { onIntent(PersonaDnaIntent.UpdateNoveltyVsProvenance(it)) },
                onSafetyChanged = { onIntent(PersonaDnaIntent.UpdateSafetyVsVelocity(it)) },
                onTenacityChanged = { onIntent(PersonaDnaIntent.UpdateTenacityScore(it)) }
            )
        }

        // Epistemic Controls & Vector
        Column(
            modifier = Modifier.weight(1.3f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                color = cc.panelAlt,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, cc.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "LAYER 2: REASONING METHODOLOGY",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = cc.accent
                    )

                    Text("Primary Reasoning Mode", style = MaterialTheme.typography.labelSmall, color = cc.textMuted)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PrimaryReasoningMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.dna.epistemicBias.primaryMode == mode,
                                onClick = { onIntent(PersonaDnaIntent.UpdatePrimaryMode(mode)) },
                                label = { Text(mode.name.replace("_", " ")) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = cc.accent.copy(alpha = 0.2f),
                                    selectedLabelColor = cc.accent
                                )
                            )
                        }
                    }
                }
            }

            Surface(
                color = cc.panelAlt,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, cc.border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "LAYER 3: COMMUNICATION VECTOR",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = cc.accent
                    )

                    OutlinedTextField(
                        value = state.dna.communicationVector.tone,
                        onValueChange = { onIntent(PersonaDnaIntent.UpdateTone(it)) },
                        label = { Text("Tone (e.g. CONCISE_INCISIVE, SKEPTICAL, CALIBRATED)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Formality Level Slider (1 to 5)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Formality Level (1: Casual, 5: Academic RFC)", style = MaterialTheme.typography.bodySmall, color = cc.textPrimary)
                        Text("${state.dna.communicationVector.formalityLevel} / 5", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.accent)
                    }
                    Slider(
                        value = state.dna.communicationVector.formalityLevel.toFloat(),
                        onValueChange = { onIntent(PersonaDnaIntent.UpdateFormalityLevel(it.toInt())) },
                        valueRange = 1f..5f,
                        steps = 3,
                        colors = SliderDefaults.colors(thumbColor = cc.accent, activeTrackColor = cc.accent)
                    )

                    // Sentence Ceiling
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Target Sentence Ceiling Per Point", style = MaterialTheme.typography.bodySmall, color = cc.textPrimary)
                        Text("${state.dna.communicationVector.targetSentenceCeiling} sentences", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.accent)
                    }
                    Slider(
                        value = state.dna.communicationVector.targetSentenceCeiling.toFloat(),
                        onValueChange = { onIntent(PersonaDnaIntent.UpdateTargetSentenceCeiling(it.toInt())) },
                        valueRange = 1f..12f,
                        steps = 10,
                        colors = SliderDefaults.colors(thumbColor = cc.accent, activeTrackColor = cc.accent)
                    )

                    // Rhetorical Devices
                    Text("Rhetorical Devices", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.dna.communicationVector.rhetoricalDevices.forEach { dev ->
                            FilterChip(
                                selected = true,
                                onClick = { onIntent(PersonaDnaIntent.RemoveRhetoricalDevice(dev)) },
                                label = { Text(dev) },
                                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = cc.accent.copy(alpha = 0.15f),
                                    selectedLabelColor = cc.accent
                                )
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newDeviceText,
                            onValueChange = { newDeviceText = it },
                            placeholder = { Text("Add device (e.g. reductio-ad-absurdum, inversion)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                if (newDeviceText.isNotBlank()) {
                                    onIntent(PersonaDnaIntent.AddRhetoricalDevice(newDeviceText.trim()))
                                    newDeviceText = ""
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }

                    OutlinedTextField(
                        value = state.dna.communicationVector.syntaxPattern,
                        onValueChange = { onIntent(PersonaDnaIntent.UpdateSyntaxPattern(it)) },
                        label = { Text("Syntax Pattern (e.g., structured-bulleted, theorem-proof)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        }
    }
}
// 3. Heuristics & Taboos Tab
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeuristicsTaboosTab(
    state: PersonaDnaState,
    onIntent: (PersonaDnaIntent) -> Unit
) {
    val cc = LocalCcColors.current
    var newArgText by remember { mutableStateOf("") }
    var newFallacyText by remember { mutableStateOf("") }
    var newBuzzwordText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // LAYER 4: Builtin Heuristics Library
        Surface(
            color = cc.panelAlt,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "LAYER 4: HEURISTIC MENTAL MODELS",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = cc.accent
                        )
                        Text(
                            "Select cognitive heuristics and mental models enforced during reasoning.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cc.textMuted
                        )
                    }
                    Text(
                        "${state.dna.heuristicLibrary.size} Active",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = cc.accent
                    )
                }

                state.availableHeuristics.forEach { heuristic ->
                    val isActive = state.dna.heuristicLibrary.any { it.id == heuristic.id }
                    Surface(
                        color = if (isActive) cc.accent.copy(alpha = 0.08f) else cc.panel,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(
                            1.dp,
                            if (isActive) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onIntent(PersonaDnaIntent.ToggleBuiltinHeuristic(heuristic)) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isActive,
                                onCheckedChange = { onIntent(PersonaDnaIntent.ToggleBuiltinHeuristic(heuristic)) },
                                colors = CheckboxDefaults.colors(checkedColor = cc.accent)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    heuristic.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isActive) cc.accent else cc.textPrimary
                                )
                                Text(
                                    heuristic.formulaOrMaxime,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cc.textMuted
                                )
                                if (heuristic.triggerCondition.isNotBlank()) {
                                    Text(
                                        "Trigger: ${heuristic.triggerCondition}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = cc.accent.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // LAYER 5: Taboo Space
        Surface(
            color = cc.panelAlt,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "LAYER 5: TABOO SPACE & NEGATIVE CONSTRAINTS",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = cc.accent
                )
                Text(
                    "Negative cognitive constraints: forbidden arguments, rejected fallacies, and intolerable buzzwords.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )

                // Forbidden Arguments
                Text("Forbidden Arguments", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.dna.tabooSpace.forbiddenArguments.forEach { arg ->
                        FilterChip(
                            selected = true,
                            onClick = { onIntent(PersonaDnaIntent.RemoveForbiddenArgument(arg)) },
                            label = { Text(arg) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.agentFifth.copy(alpha = 0.15f),
                                selectedLabelColor = cc.agentFifth
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newArgText,
                        onValueChange = { newArgText = it },
                        placeholder = { Text("Add forbidden argument (e.g. appeal-to-authority, hand-waving)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newArgText.isNotBlank()) {
                                onIntent(PersonaDnaIntent.AddForbiddenArgument(newArgText.trim()))
                                newArgText = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Rejected Fallacies
                Text("Rejected Fallacies", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.dna.tabooSpace.rejectedFallacies.forEach { fal ->
                        FilterChip(
                            selected = true,
                            onClick = { onIntent(PersonaDnaIntent.RemoveRejectedFallacy(fal)) },
                            label = { Text(fal) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.agentFifth.copy(alpha = 0.15f),
                                selectedLabelColor = cc.agentFifth
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newFallacyText,
                        onValueChange = { newFallacyText = it },
                        placeholder = { Text("Add rejected fallacy (e.g. sunk-cost, false-dichotomy)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newFallacyText.isNotBlank()) {
                                onIntent(PersonaDnaIntent.AddRejectedFallacy(newFallacyText.trim()))
                                newFallacyText = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Intolerable Buzzwords
                Text("Intolerable Buzzwords", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.dna.tabooSpace.intolerableBuzzwords.forEach { bw ->
                        FilterChip(
                            selected = true,
                            onClick = { onIntent(PersonaDnaIntent.RemoveIntolerableBuzzword(bw)) },
                            label = { Text(bw) },
                            trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove", modifier = Modifier.size(12.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.agentFifth.copy(alpha = 0.15f),
                                selectedLabelColor = cc.agentFifth
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newBuzzwordText,
                        onValueChange = { newBuzzwordText = it },
                        placeholder = { Text("Add intolerable buzzword (e.g. synergy, paradigm-shift)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (newBuzzwordText.isNotBlank()) {
                                onIntent(PersonaDnaIntent.AddIntolerableBuzzword(newBuzzwordText.trim()))
                                newBuzzwordText = ""
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }

                OutlinedTextField(
                    value = state.dna.tabooSpace.penaltyAction,
                    onValueChange = { onIntent(PersonaDnaIntent.UpdatePenaltyAction(it)) },
                    label = { Text("Penalty Action (e.g., IMMEDIATE_REFUTATION, DISQUALIFICATION)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}
// 4. Adversarial Posture & Synthesis Preference Tab
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdversarialSynthesisTab(
    state: PersonaDnaState,
    onIntent: (PersonaDnaIntent) -> Unit
) {
    val cc = LocalCcColors.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // LAYER 7: Adversarial Posture
        Surface(
            color = cc.panelAlt,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "LAYER 7: ADVERSARIAL POSTURE & COMBAT STANCE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = cc.accent
                )
                Text(
                    "Dictates how this agent challenges opposing claims and responds when countered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )

                Text("Combat Stance", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CombatStance.entries.forEach { stance ->
                        FilterChip(
                            selected = state.dna.adversarialPosture.stance == stance,
                            onClick = { onIntent(PersonaDnaIntent.UpdateCombatStance(stance)) },
                            label = { Text(stance.name.replace("_", " ")) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.accent.copy(alpha = 0.2f),
                                selectedLabelColor = cc.accent
                            )
                        )
                    }
                }

                // Tenacity Score Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tenacity Score (Adversarial Resistance)", style = MaterialTheme.typography.bodySmall, color = cc.textPrimary)
                    Text("${(state.dna.adversarialPosture.tenacityScore * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.accent)
                }
                Slider(
                    value = state.dna.adversarialPosture.tenacityScore.toFloat(),
                    onValueChange = { onIntent(PersonaDnaIntent.UpdateTenacityScore(it.toDouble())) },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = cc.accent, activeTrackColor = cc.accent)
                )

                OutlinedTextField(
                    value = state.dna.adversarialPosture.counterAttackMethod,
                    onValueChange = { onIntent(PersonaDnaIntent.UpdateCounterAttackMethod(it)) },
                    label = { Text("Counter-Attack Method (e.g., IDENTIFY_HIDDEN_AXIOM_AND_DISPROVE)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = state.dna.adversarialPosture.concedeCondition,
                    onValueChange = { onIntent(PersonaDnaIntent.UpdateConcedeCondition(it)) },
                    label = { Text("Concede Condition (e.g., RIGOROUS_EMPIRICAL_OR_FORMAL_PROOF)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        // LAYER 8: Synthesis Preference
        Surface(
            color = cc.panelAlt,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "LAYER 8: SYNTHESIS & RESOLUTION PREFERENCE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = cc.accent
                )
                Text(
                    "Defines how this agent resolves debates, forms consensus, or preserves minority reports.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )

                Text("Synthesis Style", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SynthesisStyle.entries.forEach { style ->
                        FilterChip(
                            selected = state.dna.synthesisPreference.style == style,
                            onClick = { onIntent(PersonaDnaIntent.UpdateSynthesisStyle(style)) },
                            label = { Text(style.name.replace("_", " ")) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.accent.copy(alpha = 0.2f),
                                selectedLabelColor = cc.accent
                            )
                        )
                    }
                }

                // Allow Minority Report Switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Allow Minority Report", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = cc.textPrimary)
                        Text("Permit lodging formal dissenting opinion if unresolved risks remain", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                    }
                    Switch(
                        checked = state.dna.synthesisPreference.allowMinorityReport,
                        onCheckedChange = { onIntent(PersonaDnaIntent.UpdateAllowMinorityReport(it)) },
                        colors = SwitchDefaults.colors(checkedThumbColor = cc.accent, checkedTrackColor = cc.accent.copy(alpha = 0.3f))
                    )
                }

                OutlinedTextField(
                    value = state.dna.synthesisPreference.minorityReportCriteria,
                    onValueChange = { onIntent(PersonaDnaIntent.UpdateMinorityReportCriteria(it)) },
                    label = { Text("Minority Report Criteria (e.g., UNMITIGATED_CATASTROPHIC_FAILURE_MODE)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = state.dna.synthesisPreference.compromiseCondition,
                    onValueChange = { onIntent(PersonaDnaIntent.UpdateCompromiseCondition(it)) },
                    label = { Text("Compromise Condition (e.g., BOUNDED_RISK_ENVELOPE)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}
// 5. DNA Mandate Compiler Preview Tab
@Composable
private fun CompiledPromptTab(
    state: PersonaDnaState,
    onIntent: (PersonaDnaIntent) -> Unit
) {
    val cc = LocalCcColors.current
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            color = cc.panelAlt,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, cc.border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "COMPILED COGNITIVE DNA MANDATE",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = cc.accent
                        )
                        Text(
                            "Dense mathematical system prompt generated from the 8-layer DNA matrix.",
                            style = MaterialTheme.typography.bodySmall,
                            color = cc.textMuted
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onIntent(PersonaDnaIntent.CompilePrompt) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = cc.accent),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Recompile", style = MaterialTheme.typography.labelSmall)
                        }

                        if (state.compiledPrompt != null) {
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(state.compiledPrompt))
                                    copied = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                    contentDescription = null,
                                    tint = if (copied) cc.agentThird else cc.textPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(if (copied) "Copied!" else "Copy", style = MaterialTheme.typography.labelSmall, color = cc.textPrimary)
                            }
                        }
                    }
                }

                if (state.compiledPrompt == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .background(cc.bg, RoundedCornerShape(8.dp))
                            .border(1.dp, cc.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("DNA Mandate prompt not yet compiled.", color = cc.textMuted, style = MaterialTheme.typography.bodyMedium)
                            Button(
                                onClick = { onIntent(PersonaDnaIntent.CompilePrompt) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = cc.accent)
                            ) {
                                Text("Compile Now", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(cc.bg, RoundedCornerShape(8.dp))
                            .border(1.dp, cc.border.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = state.compiledPrompt,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = cc.textPrimary,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}
// Import / Export MMOS Dialog
@Composable
private fun DnaImportExportDialog(
    mode: ImportExportMode,
    format: DnaFormat,
    text: String,
    error: String?,
    onTextChanged: (String) -> Unit,
    onFormatChanged: (DnaFormat) -> Unit,
    onConfirmImport: () -> Unit,
    onDismiss: () -> Unit
) {
    val cc = LocalCcColors.current
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (mode == ImportExportMode.IMPORT) "Import Persona DNA" else "Export Persona DNA",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = cc.textPrimary
                )
                // Format Selector (YAML vs JSON)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DnaFormat.entries.forEach { f ->
                        FilterChip(
                            selected = format == f,
                            onClick = { onFormatChanged(f) },
                            label = { Text(f.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = cc.accent.copy(alpha = 0.2f),
                                selectedLabelColor = cc.accent
                            )
                        )
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (mode == ImportExportMode.IMPORT)
                        "Paste valid MMOS (Mind Matrix Open Standard) ${format.name} definition below:"
                    else
                        "Complete MMOS ${format.name} representation of this persona's 8-layer DNA:",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )

                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChanged,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    readOnly = mode == ImportExportMode.EXPORT,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    placeholder = { Text("Paste ${format.name} here...") }
                )
            }
        },
        confirmButton = {
            if (mode == ImportExportMode.IMPORT) {
                Button(
                    onClick = onConfirmImport,
                    colors = ButtonDefaults.buttonColors(containerColor = cc.accent)
                ) {
                    Text("Import DNA")
                }
            } else {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(text))
                        copied = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = cc.accent)
                ) {
                    Icon(
                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (copied) "Copied to Clipboard!" else "Copy MMOS Payload")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
