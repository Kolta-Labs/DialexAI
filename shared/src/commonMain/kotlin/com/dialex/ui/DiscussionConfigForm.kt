package com.dialex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.service.SupportedCliTools
import com.dialex.service.launchCliLoginTerminal
import com.dialex.ui.cliauth.CliAuthDialog
import com.dialex.model.*
import com.dialex.theme.LocalCcColors

// Each list's default (see Provider.defaultModel()) is deliberately first — the dropdown
// opens already showing the model a new agent actually gets.
private val defaultModels = mapOf(
    Provider.ANTHROPIC to listOf("claude-sonnet-5", "claude-opus-5", "claude-fable-5", "claude-haiku-4-5-20251001", "claude-sonnet-4-5"),
    Provider.OPENAI to listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5", "gpt-5.4-mini"),
    // Text-chat-capable only — excludes image/video/TTS/robotics variants from the full lineup.
    Provider.GEMINI to listOf("gemini-3.7-flash", "gemini-3.1-pro", "gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-3.1-flash-live"),
    Provider.GROK to listOf("grok-4-fast", "grok-4", "grok-3", "grok-3-mini"),
    Provider.DEEPSEEK to listOf("deepseek-chat", "deepseek-reasoner"),
    Provider.MISTRAL to listOf("mistral-medium-latest", "mistral-large-latest", "mistral-small-latest", "magistral-medium-latest"),
)

// CUSTOM has no curated model list (it's whatever CLI the user configured) — model is a
// freeform field for it instead of a dropdown, so it never needs an entry here.
private fun blankAgent(p: Provider) = if (p == Provider.CUSTOM) {
    Agent(provider = p, model = "", runMode = RunMode.CLI)
} else {
    Agent(provider = p, model = p.defaultModel())
}

private fun providerAccent(cc: com.dialex.theme.CcPalette, p: Provider) = when (p) {
    Provider.ANTHROPIC -> cc.agentLeft
    Provider.GEMINI -> cc.agentRight
    Provider.OPENAI -> cc.agentThird
    Provider.CUSTOM -> cc.agentFourth
    Provider.GROK -> cc.agentFifth
    Provider.DEEPSEEK -> cc.agentSixth
    Provider.MISTRAL -> cc.agentSeventh
    Provider.OLLAMA -> androidx.compose.ui.graphics.Color(0xFF0EA5E9)
}

/** A seat's provider dropdown only offers providers no *other* seat already has — each
 * provider can hold at most one seat (debate turns are attributed by provider alone). The
 * seat's own current provider (or `current` when toggling a new seat on) is always kept in
 * the list even if that seems redundant, so the dropdown never hides the selected value.
 * CUSTOM only ever appears where CLI is actually available — it has no API shape to call. */
private fun availableProviders(current: Provider?, usedElsewhere: List<Provider>, supportsCli: Boolean): List<Provider> =
    Provider.entries.filter { (it == current || it !in usedElsewhere) && (it != Provider.CUSTOM || supportsCli) }

// CLI mode always has a usable command (Settings has non-blank defaults) — only API mode
// needs a per-provider key check. CUSTOM has no model list to require a pick from, but does
// need a display name (there's no fixed brand to fall back on).
private fun Agent.isReady(apiKeys: ApiKeys): Boolean =
    (provider == Provider.CUSTOM || model.isNotBlank()) &&
        (provider != Provider.CUSTOM || displayName.isNotBlank()) &&
        (runMode == RunMode.CLI || apiKeys.forProvider(provider).isNotBlank())

/** Names exactly what's stopping this agent from being ready, or null if it's fine. */
private fun Agent.missingReason(name: String, apiKeys: ApiKeys): String? = when {
    provider == Provider.CUSTOM && displayName.isBlank() -> "$name: no display name set"
    provider != Provider.CUSTOM && model.isBlank() -> "$name: no model selected"
    runMode == RunMode.API && apiKeys.forProvider(provider).isBlank() -> "$name: no ${provider.brandName()} API key set (Settings)"
    else -> null
}

// The four non-primary seats, compacted (no gaps) — order is speaking order and also add
// order, since a removal always closes the gap by shifting everything after it down one
// named slot rather than leaving a hole.
private const val MAX_ADDITIONAL_AGENTS = 4

private fun DebateConfig.additionalAgents(): List<Agent> = listOfNotNull(secondary, tertiary, quaternary, quinary)

private fun DebateConfig.withAdditionalAgents(agents: List<Agent>): DebateConfig = copy(
    secondary = agents.getOrNull(0),
    tertiary = agents.getOrNull(1),
    quaternary = agents.getOrNull(2),
    quinary = agents.getOrNull(3),
)

/**
 * Discussion setup screen. The primary seat always takes part and always opens the debate
 * (defaults to Claude, but its provider is picked here); the second/third seats are
 * optional extra participants (2-way or 3-way) — any seat can be any provider, the only
 * rule is no two seats share one. Topic/Context/Information are the three shared cards.
 * API keys and CLI commands both live in Settings — this screen only shows whether each is
 * configured, with a shortcut to Settings if not. Orange `*` marks the fields required to
 * start.
 */
@Composable
fun DiscussionConfigForm(
    discussion: Discussion,
    supportsCli: Boolean,
    apiKeys: ApiKeys,
    cliCommands: CliCommands,
    onChange: (Discussion) -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onCancelEdit: (() -> Unit)? = null,
    /** Other discussions this one's setup can be copied from — empty hides the button. */
    copyFromOptions: List<Discussion> = emptyList(),
    /** Opens the platform's file picker; the caller reads it and folds the result into
     * this discussion's Information card. Null hides the attach button. */
    onAttachFile: (() -> Unit)? = null,
) {
    val cc = LocalCcColors.current
    val config = discussion.config

    fun setConfig(c: DebateConfig) = onChange(discussion.copy(config = c))
    fun setPrimary(a: Agent) = setConfig(config.copy(primary = a))
    fun setAdditionalAgents(agents: List<Agent>) = setConfig(config.withAdditionalAgents(agents))

    val additionalAgents = config.additionalAgents()

    val missingReasons = buildList {
        if (discussion.name.isBlank()) add("Discussion name is empty")
        if (config.topic.isBlank()) add("Topic is empty")
        config.primary.missingReason(config.primary.label(), apiKeys)?.let(::add)
        additionalAgents.forEach { it.missingReason(it.label(), apiKeys)?.let(::add) }
    }
    val canStart = missingReasons.isEmpty()

    var copyFromOpen by remember { mutableStateOf(false) }
    // Which additional-agent card is open for editing (index into additionalAgents), or
    // null. Separate flag for "adding a brand new one" since that has no existing Agent to
    // seed the dialog with. Reset whenever a different discussion is opened.
    var editingIndex by remember(discussion.id) { mutableStateOf<Int?>(null) }
    var addingNew by remember(discussion.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(cc.bg).verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (onCancelEdit != null) "EDIT SETUP" else "DISCUSSION SETUP", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (copyFromOptions.isNotEmpty()) {
                    TextButton(onClick = { copyFromOpen = true }) { Text("Copy settings from…") }
                }
                if (onCancelEdit != null) TextButton(onClick = onCancelEdit) { Text("‹ Back to chat") }
            }
        }
        Spacer(Modifier.height(8.dp))
        CcField(discussion.name, { onChange(discussion.copy(name = it)) }, "Discussion name", required = true)

        Spacer(Modifier.height(16.dp))
        EditableCard("Topic", config.topic, "What should they debate?", required = true) { setConfig(config.copy(topic = it)) }
        Spacer(Modifier.height(8.dp))
        EditableCard("Context", config.commonContext, "Background every agent sees") { setConfig(config.copy(commonContext = it)) }
        Spacer(Modifier.height(8.dp))
        EditableCard("Information", config.commonInfo, "Extra data, ground rules, anything else relevant") { setConfig(config.copy(commonInfo = it)) }
        if (onAttachFile != null) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onAttachFile) { Text("📎 Attach file → Information") }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("PARTICIPANTS", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "The Primary Agent always speaks first and gives the final decision — defaults to " +
                "Claude, but any provider works, including \"Custom\" (any other CLI tool, " +
                "desktop only). Add up to four more; each seat's provider is independent, no " +
                "two can share one.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        AgentPanel(
            agent = config.primary,
            availableProviders = availableProviders(config.primary.provider, additionalAgents.map { it.provider }, supportsCli),
            supportsCli = supportsCli, apiKeys = apiKeys, cliCommands = cliCommands,
            onOpenSettings = onOpenSettings, onChange = ::setPrimary,
        )

        additionalAgents.forEachIndexed { index, agent ->
            Spacer(Modifier.height(10.dp))
            AgentCard(agent = agent, onEdit = { editingIndex = index })
        }

        if (additionalAgents.size < MAX_ADDITIONAL_AGENTS) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { addingNew = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = cc.panelAlt, contentColor = cc.textPrimary),
                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.8f))
            ) {
                Text("+ Add Agent", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium))
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("SHARED SETTINGS", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(8.dp))
        Dropdown(
            label = "Round mode",
            selected = if (config.roundMode == RoundMode.FIXED) "Fixed — ends with the primary agent's final decision" else "Unlimited — ends with Pause",
            options = listOf("Fixed — ends with the primary agent's final decision", "Unlimited — ends with Pause"),
            onSelect = { choice ->
                val mode = if (choice.startsWith("Fixed")) RoundMode.FIXED else RoundMode.UNLIMITED
                setConfig(config.copy(roundMode = mode))
            },
        )
        if (config.roundMode == RoundMode.FIXED) {
            Spacer(Modifier.height(8.dp))
            RoundsField(config.maxRounds) { setConfig(config.copy(maxRounds = it)) }
        } else {
            Spacer(Modifier.height(6.dp))
            Text("Runs indefinitely; press Pause during the debate, Resume to continue.", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        GradientButton(
            text = if (onCancelEdit != null) "Restart with these changes" else "Start discussion",
            onClick = onStart,
            enabled = canStart,
            height = 38.dp,
            modifier = Modifier.fillMaxWidth()
        )
        if (missingReasons.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Missing before you can start:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            missingReasons.forEach { reason ->
                Text("• $reason", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (copyFromOpen) {
        PickDiscussionDialog(
            title = "Copy settings from",
            options = copyFromOptions,
            onDismiss = { copyFromOpen = false },
            onPick = { picked -> setConfig(picked.config); copyFromOpen = false },
        )
    }

    editingIndex?.let { index ->
        val editing = additionalAgents[index]
        val usedElsewhere = (listOf(config.primary) + additionalAgents.filterIndexed { i, _ -> i != index }).map { it.provider }
        AgentEditDialog(
            title = "Edit Agent",
            initial = editing,
            availableProviders = availableProviders(editing.provider, usedElsewhere, supportsCli),
            supportsCli = supportsCli, apiKeys = apiKeys, cliCommands = cliCommands, onOpenSettings = onOpenSettings,
            onDismiss = { editingIndex = null },
            onSave = { updated -> setAdditionalAgents(additionalAgents.toMutableList().apply { this[index] = updated }); editingIndex = null },
            onRemove = { setAdditionalAgents(additionalAgents.filterIndexed { i, _ -> i != index }); editingIndex = null },
        )
    }

    if (addingNew) {
        val usedElsewhere = (listOf(config.primary) + additionalAgents).map { it.provider }
        val options = availableProviders(null, usedElsewhere, supportsCli)
        AgentEditDialog(
            title = "Add Agent",
            initial = blankAgent(options.first()),
            availableProviders = options,
            supportsCli = supportsCli, apiKeys = apiKeys, cliCommands = cliCommands, onOpenSettings = onOpenSettings,
            onDismiss = { addingNew = false },
            onSave = { added -> setAdditionalAgents(additionalAgents + added); addingNew = false },
            onRemove = null,
        )
    }
}

/** Small modal list — pick one of [options] by name (topic shown as a subtitle). */
@Composable
private fun PickDiscussionDialog(
    title: String,
    options: List<Discussion>,
    onDismiss: () -> Unit,
    onPick: (Discussion) -> Unit,
) {
    val cc = LocalCcColors.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(400.dp).heightIn(max = 420.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(cc.panel)
                .padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                options.forEach { d ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(cc.panelAlt)
                            .clickable { onPick(d) }
                            .padding(10.dp),
                    ) {
                        Text(d.name, style = MaterialTheme.typography.bodyMedium)
                        if (d.config.topic.isNotBlank()) {
                            Text(d.config.topic, style = MaterialTheme.typography.bodySmall, color = cc.textMuted, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

/** Free-typing rounds input — a field bound straight to a derived string snaps back on every
 * keystroke (can't clear it to type "10"), so this keeps its own local text and only commits
 * a value up when it parses to something valid. */
@Composable
private fun RoundsField(value: Int, onValueChange: (Int) -> Unit) {
    val cc = LocalCcColors.current
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column {
        Row {
            Text("Max rounds", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = text,
            onValueChange = { new ->
                text = new
                new.toIntOrNull()?.let { if (it in 1..20) onValueChange(it) }
            },
            singleLine = true,
            textStyle = TextStyle(fontSize = MaterialTheme.typography.bodyMedium.fontSize, color = cc.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(cc.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(80.dp)
                .border(1.dp, cc.border, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Read-only summary of one additional agent — label, model, run mode — with the one action
 * available from the setup screen itself; everything else happens in [AgentEditDialog]. */
@Composable
private fun AgentCard(agent: Agent, onEdit: () -> Unit) {
    val cc = LocalCcColors.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cc.panelAlt)
            .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.6f)), RoundedCornerShape(8.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(agent.label(), style = MaterialTheme.typography.titleSmall, color = providerAccent(cc, agent.provider))
            Spacer(Modifier.height(2.dp))
            val detail = listOfNotNull(agent.model.takeIf { it.isNotBlank() }, if (agent.runMode == RunMode.CLI) "CLI" else "API")
                .joinToString(" · ")
            Text(detail, style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
        }
        TextButton(onClick = onEdit) { Text("Edit") }
    }
}

/** Everything about one agent — provider, model, run mode, context, prompt — behind a
 * Save/Cancel(/Remove), so adding or editing a seat never disturbs the rest of the setup
 * screen while it's mid-edit. */
@Composable
private fun AgentEditDialog(
    title: String,
    initial: Agent,
    availableProviders: List<Provider>,
    supportsCli: Boolean,
    apiKeys: ApiKeys,
    cliCommands: CliCommands,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Agent) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val cc = LocalCcColors.current
    var draft by remember { mutableStateOf(initial) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(460.dp).heightIn(max = 620.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cc.panel)
                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)), RoundedCornerShape(12.dp))
                .padding(20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                AgentFields(draft, availableProviders, supportsCli, apiKeys, cliCommands, onOpenSettings) { draft = it }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box {
                    if (onRemove != null) {
                        TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    GradientButton(
                        text = "Save",
                        onClick = { onSave(draft) },
                        height = 34.dp
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentPanel(
    agent: Agent,
    availableProviders: List<Provider>,
    supportsCli: Boolean,
    apiKeys: ApiKeys,
    cliCommands: CliCommands,
    onOpenSettings: () -> Unit,
    onChange: (Agent) -> Unit,
) {
    val cc = LocalCcColors.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cc.panelAlt)
            .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.6f)), RoundedCornerShape(8.dp))
            .padding(14.dp)
    ) {
        Text("Primary Agent — ${agent.label()}", style = MaterialTheme.typography.titleSmall, color = providerAccent(cc, agent.provider))
        Spacer(Modifier.height(10.dp))
        AgentFields(agent, availableProviders, supportsCli, apiKeys, cliCommands, onOpenSettings, onChange)
    }
}

@Composable
private fun AgentFields(
    agent: Agent,
    availableProviders: List<Provider>,
    supportsCli: Boolean,
    apiKeys: ApiKeys,
    cliCommands: CliCommands,
    onOpenSettings: () -> Unit,
    onChange: (Agent) -> Unit,
) {
    val cc = LocalCcColors.current

    // Android has no CLI at all — force back to API if a persisted agent was left in CLI
    // mode (e.g. edited on desktop, opened on mobile). CUSTOM is the opposite: there's no
    // known API shape to call, so it's always CLI wherever CLI exists at all.
    LaunchedEffect(supportsCli, agent.provider, agent.runMode) {
        if (!supportsCli && agent.runMode == RunMode.CLI) onChange(agent.copy(runMode = RunMode.API))
        if (agent.provider == Provider.CUSTOM && agent.runMode != RunMode.CLI) onChange(agent.copy(runMode = RunMode.CLI))
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (availableProviders.size > 1) {
            Dropdown(
                label = "Provider",
                selected = agent.provider.brandName(),
                options = availableProviders.map { it.brandName() },
                onSelect = { choice ->
                    val p = availableProviders.first { it.brandName() == choice }
                    onChange(
                        if (p == Provider.CUSTOM) agent.copy(provider = p, model = "", runMode = RunMode.CLI)
                        else agent.copy(provider = p, model = p.defaultModel()),
                    )
                },
            )
        }
        if (agent.provider == Provider.CUSTOM) {
            CcField(agent.displayName, { onChange(agent.copy(displayName = it)) }, "Display name (e.g. Aider, Cursor CLI)", required = true)
            CcField(agent.model, { onChange(agent.copy(model = it)) }, "Model / version (optional)")
        } else {
            Dropdown(
                label = "Model",
                selected = agent.model,
                options = defaultModels.getValue(agent.provider),
                onSelect = { onChange(agent.copy(model = it)) },
            )
        }

        if (supportsCli && agent.provider != Provider.CUSTOM) {
            Dropdown(
                label = "Run via",
                selected = agent.runMode.name,
                options = RunMode.entries.map { it.name },
                onSelect = { onChange(agent.copy(runMode = RunMode.valueOf(it))) },
            )
        }

        if (agent.runMode == RunMode.CLI && supportsCli) {
            val tool = SupportedCliTools.firstOrNull { it.provider == agent.provider }
            var authToolOpen by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (tool != null) Color(0xFF4CAF50) else cc.textMuted))
                    Text(
                        "CLI: `${cliCommands.forProvider(agent.provider)}`",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = cc.textMuted,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (tool != null) {
                        GradientButton(
                            text = "Log In",
                            onClick = {
                                launchCliLoginTerminal(tool.loginCommand)
                            },
                            icon = Icons.AutoMirrored.Outlined.Login,
                            height = 26.dp,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                    TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                        Text("Settings", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = cc.textMuted)
                    }
                }
            }

            if (authToolOpen && tool != null) {
                CliAuthDialog(
                    tool = tool,
                    onDismiss = { authToolOpen = false },
                    onSuccess = { authToolOpen = false }
                )
            }
        } else {
            val hasKey = apiKeys.forProvider(agent.provider).isNotBlank()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (hasKey) "Uses your ${agent.provider.brandName()} API key" else "No API key set for ${agent.provider.brandName()}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = if (hasKey) cc.textMuted else MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onOpenSettings, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(if (!hasKey) "Add Key" else "Settings", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = if (!hasKey) cc.accent else cc.textMuted)
                }
            }
        }

        EditableCard("Individual context", agent.context, "Background only this agent sees") { onChange(agent.copy(context = it)) }
        EditableCard("Starting prompt / persona", agent.systemPrompt, "e.g. Argue in favor, be concise") { onChange(agent.copy(systemPrompt = it)) }
    }
}

@Composable
private fun Dropdown(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    val cc = LocalCcColors.current
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = cc.textMuted)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = cc.panelAlt, contentColor = cc.textPrimary),
                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.8f))
            ) {
                Text(selected.ifBlank { "Choose" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(cc.panel).border(BorderStroke(1.dp, cc.border), RoundedCornerShape(8.dp))
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(opt, style = MaterialTheme.typography.bodyMedium, color = cc.textPrimary) },
                        onClick = { onSelect(opt); open = false }
                    )
                }
            }
        }
    }
}

/** Label gets an orange `*` suffix when `required` — the field that blocks "Start discussion". */
@Composable
private fun CcField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    required: Boolean = false,
) {
    val cc = LocalCcColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Row {
                Text(label, style = MaterialTheme.typography.bodySmall)
                if (required) Text(" *", style = MaterialTheme.typography.bodySmall, color = cc.accent)
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}
