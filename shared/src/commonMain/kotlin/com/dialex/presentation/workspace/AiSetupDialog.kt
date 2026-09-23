package com.dialex.presentation.workspace

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.audio.SpeechRecognitionState
import com.dialex.audio.SpeechTextMerger
import com.dialex.audio.rememberPlatformSpeechRecognizer
import com.dialex.model.*
import com.dialex.theme.LocalCcColors
import com.dialex.ui.GradientButton
import com.dialex.ui.ThemedDropdown
import com.dialex.ui.ThemedDropdownOption
import com.dialex.ui.ThemedTooltipBox
import kotlinx.coroutines.launch

/**
 * Two-Step AI Deliberation Setup:
 *
 * Page 1: Provide topic query / voice dictation, choose council size (2–5 agents),
 *         and select the fast architect model.
 * Page 2: Review synthesized topic, context, and the full AI seat distribution
 *         (personas, roles, assigned models, and sovereign CLI/API run modes).
 *         User can confirm & launch, or open full setup.
 */
@Composable
fun AiSetupDialog(
    projects: List<Project>,
    initialProjectId: String?,
    defaultModel: String = "claude-haiku-4-5-20251001",
    availableModels: Map<String, List<String>> = emptyMap(),
    apiKeys: ApiKeys = ApiKeys(),
    cliCommands: CliCommands = CliCommands(),
    cliStatus: Map<String, Boolean> = emptyMap(),
    cliLogins: Map<String, Boolean> = emptyMap(),
    onDismiss: () -> Unit,
    onArchitect: suspend (prompt: String, projectId: String, model: String?, numAgents: Int) -> Discussion,
    onConfirmLaunch: suspend (discussion: Discussion) -> Unit,
    onViewFullSetup: (discussion: Discussion) -> Unit
) {
    val cc = LocalCcColors.current
    val coroutineScope = rememberCoroutineScope()

    // ── Step State: 1 = Define Dilemma, 2 = Review & Confirm Seat Distribution
    var currentStep by remember { mutableStateOf(1) }
    var architectedDiscussion by remember { mutableStateOf<Discussion?>(null) }
    var isArchitecting by remember { mutableStateOf(false) }
    var stepErrorMessage by remember { mutableStateOf<String?>(null) }

    // ── Page 1 Inputs
    var promptInput by remember { mutableStateOf("") }
    var selectedProjectId by remember(initialProjectId, projects) {
        val initial = initialProjectId
            ?: projects.firstOrNull { it.name.equals("Ungrouped", ignoreCase = true) }?.id
            ?: projects.firstOrNull()?.id
            ?: ""
        mutableStateOf(initial)
    }
    var selectedArchitectModel by remember(defaultModel) { mutableStateOf(defaultModel) }
    var architectModelMenuOpen by remember { mutableStateOf(false) }
    var councilSize by remember { mutableStateOf(3) }

    // ── Inspect availability on host machine (requires both installed + authenticated)
    fun isCliReady(p: Provider): Boolean {
        if (cliStatus.isEmpty() && cliLogins.isEmpty()) return false
        val keys = when (p) {
            Provider.ANTHROPIC -> listOf("ANTHROPIC", "anthropic", "Claude", "claude", "Claude Code")
            Provider.GEMINI -> listOf("GEMINI", "gemini", "Antigravity", "antigravity", "agy", "Antigravity (Gemini)")
            Provider.OPENAI -> listOf("OPENAI", "openai", "Codex", "codex", "ChatGPT")
            Provider.OLLAMA -> listOf("OLLAMA", "ollama")
            Provider.DEEPSEEK -> listOf("DEEPSEEK", "deepseek")
            Provider.GROK -> listOf("GROK", "grok")
            Provider.MISTRAL -> listOf("MISTRAL", "mistral")
            Provider.CUSTOM -> emptyList()
        }
        val isAvail = if (cliStatus.isNotEmpty()) keys.any { cliStatus[it] == true } else true
        val isLogged = if (cliLogins.isNotEmpty()) keys.any { cliLogins[it] == true } else isAvail
        return isAvail && isLogged
    }

    fun isApiReady(p: Provider): Boolean {
        if (p == Provider.OLLAMA) return false
        return apiKeys.forProvider(p).isNotBlank()
    }

    fun providerDisplayName(p: Provider): String {
        return when (p) {
            Provider.GEMINI -> if (isCliReady(p)) "Antigravity" else "Gemini"
            Provider.ANTHROPIC -> "Claude"
            Provider.OPENAI -> "ChatGPT"
            Provider.OLLAMA -> "Ollama"
            Provider.DEEPSEEK -> "DeepSeek"
            Provider.GROK -> "Grok"
            Provider.MISTRAL -> "Mistral"
            Provider.CUSTOM -> "Custom"
        }
    }

    val standardProviders = remember {
        listOf(
            Provider.ANTHROPIC,
            Provider.GEMINI,
            Provider.OPENAI,
            Provider.DEEPSEEK,
            Provider.GROK,
            Provider.MISTRAL,
            Provider.OLLAMA
        )
    }

    val activeProviders = remember(cliStatus, cliLogins, apiKeys) {
        val list = standardProviders.filter { isCliReady(it) || isApiReady(it) }
        list.ifEmpty { listOf(Provider.ANTHROPIC, Provider.GEMINI) }
    }

    val anyCliReady = remember(cliStatus, cliLogins) {
        standardProviders.any { isCliReady(it) }
    }

    // Speech-to-Text Recognizer integration
    val recognizer = rememberPlatformSpeechRecognizer()
    val speechState by recognizer.state.collectAsState()
    val isListening = speechState is SpeechRecognitionState.Listening || speechState is SpeechRecognitionState.Initializing
    val rmsLevel = (speechState as? SpeechRecognitionState.Listening)?.rmsDb ?: 0f

    LaunchedEffect(speechState) {
        val state = speechState
        if (state is SpeechRecognitionState.Result && state.text.isNotBlank()) {
            promptInput = SpeechTextMerger.merge(promptInput, state.text)
        }
    }

    // Dynamic pulsating animation for microphone button
    val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micScale"
    )
    val activeMicScale = if (isListening) (1.0f + rmsLevel * 0.4f).coerceIn(1.05f, 1.45f) else idlePulse

    val allFlattenedModels = remember(availableModels, defaultModel) {
        val list = mutableListOf<String>()
        if (defaultModel.isNotBlank()) list.add(defaultModel)
        availableModels.values.flatten().forEach { m ->
            if (!list.contains(m)) list.add(m)
        }
        list
    }

    fun proceedToArchitect() {
        if (promptInput.isBlank()) return
        if (isListening) recognizer.stopListening()
        isArchitecting = true
        stepErrorMessage = null
        coroutineScope.launch {
            try {
                val discussion = onArchitect(
                    promptInput,
                    selectedProjectId,
                    selectedArchitectModel,
                    councilSize
                )
                architectedDiscussion = discussion
                isArchitecting = false
                currentStep = 2
            } catch (e: Exception) {
                isArchitecting = false
                stepErrorMessage = e.message ?: "Failed to architect deliberation council"
            }
        }
    }

    Dialog(onDismissRequest = { if (!isArchitecting) onDismiss() }) {
        Box(
            modifier = Modifier
                .widthIn(min = 520.dp, max = 660.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cc.panel)
                .border(1.dp, cc.border.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(22.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── 1. Header with Step Indicator ──────────────────────────────────────
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
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(cc.accent.copy(alpha = 0.15f))
                                .border(1.dp, cc.accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (currentStep == 1) Icons.Outlined.AutoAwesome else Icons.Outlined.Verified,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                if (currentStep == 1) "Setup Conversation with AI" else "Confirm Council Seat Distribution",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.5.sp
                                ),
                                color = cc.textPrimary
                            )
                            Text(
                                if (currentStep == 1) "Step 1 of 2: Define dilemma & council size" else "Step 2 of 2: Review tailored personas & models",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    if (!isArchitecting) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = cc.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // ── 2. Error Banner (if any) ───────────────────────────────────────────
                if (!stepErrorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stepErrorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                // ══════════════════════════════════════════════════════════════════════
                // ── PAGE 1: Input Query, Council Size, Architect Model ────────────────
                // ══════════════════════════════════════════════════════════════════════
                if (currentStep == 1) {
                    // Prompt Input Area with Mic
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(1.dp, if (isListening) cc.accent else cc.border.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BasicTextField(
                                value = promptInput,
                                onValueChange = { promptInput = it },
                                enabled = !isArchitecting,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = cc.textPrimary,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp
                                ),
                                cursorBrush = SolidColor(cc.accent),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp, max = 160.dp),
                                decorationBox = { innerTextField ->
                                    Box {
                                        if (promptInput.isEmpty() && !isListening) {
                                            Text(
                                                "What would you like to deliberate?\n\ne.g. 'Should we migrate our monolith to microservices on Kubernetes? We have 3 engineers, strict budget, and a deadline in 6 months.'",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    color = cc.textMuted.copy(alpha = 0.55f),
                                                    fontSize = 13.sp,
                                                    lineHeight = 18.sp
                                                )
                                            )
                                        } else if (promptInput.isEmpty() && isListening) {
                                            Text(
                                                "Listening to microphone... Speak your challenge freely.",
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    color = cc.accent,
                                                    fontSize = 13.sp
                                                )
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )

                            // Status + Microphone
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (isListening) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(cc.accent)
                                        )
                                        Text(
                                            "Listening...",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            color = cc.accent
                                        )
                                    } else {
                                        Text(
                                            "${promptInput.length} chars",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            color = cc.textMuted.copy(alpha = 0.6f)
                                        )
                                    }
                                }

                                ThemedTooltipBox(if (isListening) "Stop dictating" else "Dictate with voice input") {
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = if (isListening) cc.accent.copy(alpha = 0.25f) else cc.panel,
                                        border = BorderStroke(1.dp, if (isListening) cc.accent else cc.border),
                                        modifier = Modifier
                                            .scale(activeMicScale)
                                            .clickable(enabled = !isArchitecting) {
                                                if (isListening) {
                                                    recognizer.stopListening()
                                                } else {
                                                    recognizer.startListening()
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Icon(
                                                if (isListening) Icons.Filled.Mic else Icons.Filled.MicOff,
                                                contentDescription = "Voice Input",
                                                tint = if (isListening) cc.accent else cc.textMuted,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Text(
                                                if (isListening) "Speaking" else "Voice",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium
                                                ),
                                                color = if (isListening) cc.accent else cc.textMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Council Size Selector
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Outlined.Group, contentDescription = null, tint = cc.accent, modifier = Modifier.size(15.dp))
                                Text(
                                    "Council Size",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
                                    color = cc.textPrimary
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(2, 3, 4, 5).forEach { count ->
                                    val isSelected = councilSize == count
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) cc.accent.copy(alpha = 0.2f) else cc.panelAlt,
                                        border = BorderStroke(
                                            1.dp,
                                            if (isSelected) cc.accent else cc.border.copy(alpha = 0.6f)
                                        ),
                                        modifier = Modifier
                                            .clickable(enabled = !isArchitecting) { councilSize = count }
                                    ) {
                                        Text(
                                            text = if (count == 3) "3 Agents (Standard)" else "$count Agents",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            ),
                                            color = if (isSelected) cc.accent else cc.textMuted,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        val roleHintText = when (councilSize) {
                            2 -> "Facilitator + Contrarian Advocate"
                            3 -> "Facilitator + Strategic Proponent + Devil's Advocate (Recommended)"
                            4 -> "Facilitator + Proponent + Risk Analyst + Pragmatist"
                            else -> "Facilitator + Proponent + Risk + Pragmatist + Domain Specialist"
                        }
                        Text(
                            roleHintText,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = cc.textMuted
                        )
                    }

                    // Available Agents Overview: Shows ONLY detected agents on host machine
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(Icons.Outlined.Terminal, contentDescription = null, tint = cc.accent, modifier = Modifier.size(14.dp))
                                    Text(
                                        "Detected Active AI Models",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                                        color = cc.textPrimary
                                    )
                                }
                                Text(
                                    if (anyCliReady) "Prefers CLI sessions first (free & sovereign)" else "Cloud API mode",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    color = if (anyCliReady) cc.accent else cc.textMuted
                                )
                            }

                            // Show ONLY detected active providers (e.g. Claude & Antigravity)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                activeProviders.forEach { prov ->
                                    val isCli = isCliReady(prov)
                                    val label = providerDisplayName(prov)
                                    val statusText = if (isCli) "CLI Ready" else "API Ready"
                                    val statusColor = if (isCli) cc.accent else cc.textPrimary

                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = cc.panel,
                                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                                            Text(statusText, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = statusColor)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Architect Model & Project Pickers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Model Selector
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(0.75.dp, cc.border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp)
                                    .clickable(enabled = !isArchitecting) { architectModelMenuOpen = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Speed,
                                        contentDescription = null,
                                        tint = cc.accent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "Architect: $selectedArchitectModel",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        color = cc.textPrimary,
                                        maxLines = 1
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = architectModelMenuOpen,
                                onDismissRequest = { architectModelMenuOpen = false },
                                modifier = Modifier.background(cc.panel)
                            ) {
                                Text(
                                    "AI Council Architect Model",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = cc.textMuted,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                                allFlattenedModels.take(8).forEach { m ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (m == selectedArchitectModel) {
                                                    Icon(Icons.Outlined.Check, contentDescription = null, tint = cc.accent, modifier = Modifier.size(13.dp))
                                                } else {
                                                    Spacer(Modifier.width(13.dp))
                                                }
                                                Text(m, style = MaterialTheme.typography.bodySmall, color = cc.textPrimary)
                                            }
                                        },
                                        onClick = {
                                            selectedArchitectModel = m
                                            architectModelMenuOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        // Right: Project Picker
                        if (projects.isNotEmpty()) {
                            Box(modifier = Modifier.weight(1f)) {
                                val projectOptions = projects.map { ThemedDropdownOption(it.id, it.name) }
                                ThemedDropdown(
                                    selectedId = selectedProjectId,
                                    options = projectOptions,
                                    onSelect = { opt -> selectedProjectId = opt.id },
                                    placeholder = "Select Project",
                                    leadingIcon = Icons.Outlined.Folder,
                                    isMinimal = true,
                                    minHeight = 36.dp,
                                    isCompact = true
                                )
                            }
                        }
                    }

                    // Progress Banner when architecting
                    AnimatedVisibility(visible = isArchitecting) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = cc.accent.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = cc.accent,
                                    strokeWidth = 2.dp
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        "Architecting Deliberation Council...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = cc.textPrimary
                                    )
                                    Text(
                                        "Synthesizing topic, constraints, & specialized adversarial personas",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = cc.textMuted
                                    )
                                }
                            }
                        }
                    }

                    // Bottom Action Buttons for Page 1
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !isArchitecting,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Cancel", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                        }

                        GradientButton(
                            text = "Architect Council →",
                            onClick = { proceedToArchitect() },
                            enabled = promptInput.isNotBlank() && !isArchitecting,
                            icon = Icons.Outlined.AutoAwesome,
                            modifier = Modifier.height(36.dp)
                        )
                    }
                }

                // ══════════════════════════════════════════════════════════════════════
                // ── PAGE 2: Review & Confirm AI Seat Distribution ─────────────────────
                // ══════════════════════════════════════════════════════════════════════
                if (currentStep == 2 && architectedDiscussion != null) {
                    val disc = architectedDiscussion!!
                    val config = disc.config

                    // Topic & Dilemma Synthesized Summary Card
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = disc.name,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                                color = cc.textPrimary
                            )
                            Text(
                                text = config.topic,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                                color = cc.accent
                            )
                            if (config.commonContext.isNotBlank()) {
                                Text(
                                    text = config.commonContext,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = cc.textMuted, lineHeight = 16.sp),
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // AI Seat Distribution List
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Council Seat Distribution",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                                color = cc.textPrimary
                            )
                            Text(
                                "Seats may share models with distinct roles",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = cc.textMuted
                            )
                        }

                        // Collect all active seats in order
                        val activeSeats = listOfNotNull(
                            config.primary to "Seat 1 (Moderator)",
                            config.secondary?.let { it to "Seat 2 (Proponent)" },
                            config.tertiary?.let { it to "Seat 3 (Adversary)" },
                            config.quaternary?.let { it to "Seat 4 (Pragmatist)" },
                            config.quinary?.let { it to "Seat 5 (Specialist)" },
                            config.senary?.let { it to "Seat 6 (Operations)" }
                        )

                        activeSeats.forEach { (agent, seatTitle) ->
                            Surface(
                                shape = RoundedCornerShape(9.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Row 1: Seat title, Role, and Execution Mode Badge
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = cc.accent.copy(alpha = 0.15f),
                                                border = BorderStroke(0.5.dp, cc.accent.copy(alpha = 0.4f))
                                            ) {
                                                Text(
                                                    seatTitle,
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.5.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = cc.accent,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            Text(
                                                agent.role.ifBlank { agent.displayName.ifBlank { agent.provider.brandName() } },
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                ),
                                                color = cc.textPrimary
                                            )
                                        }

                                        // Assigned Model + RunMode Badge
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                                        ) {
                                            Text(
                                                "${providerDisplayName(agent.provider)} (${agent.model})",
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = cc.textMuted
                                            )

                                            val isCli = agent.runMode == RunMode.CLI
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isCli) cc.accent.copy(alpha = 0.2f) else cc.panel,
                                                border = BorderStroke(0.5.dp, if (isCli) cc.accent else cc.border)
                                            ) {
                                                Text(
                                                    if (isCli) "CLI" else "API",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    color = if (isCli) cc.accent else cc.textPrimary,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Row 2: Assigned System Prompt Excerpt
                                    if (agent.systemPrompt.isNotBlank()) {
                                        Text(
                                            agent.systemPrompt,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, color = cc.textMuted, lineHeight = 15.sp),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Bottom Action Buttons for Page 2
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Back to Page 1
                        TextButton(
                            onClick = { currentStep = 1 },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("← Edit Query", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // View Full Setup
                            OutlinedButton(
                                onClick = { onViewFullSetup(disc) },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.75.dp, cc.border),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    "View Full Setup",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = cc.textPrimary
                                )
                            }

                            // Confirm & Launch
                            GradientButton(
                                text = "Confirm & Launch",
                                onClick = {
                                    coroutineScope.launch {
                                        onConfirmLaunch(disc)
                                    }
                                },
                                icon = Icons.Outlined.Check,
                                modifier = Modifier.height(36.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
