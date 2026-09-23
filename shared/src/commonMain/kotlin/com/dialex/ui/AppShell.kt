@file:Suppress("DEPRECATION")

package com.dialex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.dialex.model.DiscussionStatus
import com.dialex.theme.LocalCcColors
import com.dialex.theme.ThemeMode
import com.dialex.viewmodel.EngineViewModel
import com.dialex.viewmodel.launchGuarded
import kotlinx.coroutines.CoroutineScope

/**
 * Root screen: Sidebar (projects/discussions) + main pane. Main pane shows, in order of
 * priority: the chat/config for a selected discussion, a "New Discussion" CTA once a
 * project is selected, or a prompt to pick/create a project.
 *
 * Talks only to [EngineViewModel] — every mutation is a suspend call to the engine, so every
 * callback here just wraps one in `scope.launch {}`. There's no local runner selection or job
 * tracking to do anymore: the engine owns which runner (API/CLI) each agent uses and owns
 * cancellation for pause/hard-stop, so this shell only needs to ask it to do things and render
 * what comes back in `vm.state`.
 *
 * Does **not** wrap itself in `ClaudeCodeTheme` — the caller (`Main.kt`/`MainActivity`) wraps
 * its *entire* top-level content (this, the connect screen, the lock screen, the "starting
 * engine…" placeholder) in exactly one theme root, so every screen agrees on light/dark/system
 * before any of them exist, not just this one.
 */
@Composable
fun AppShell(
    vm: EngineViewModel,
    supportsCli: Boolean,
    scope: CoroutineScope,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    /** What Settings → Connection shows — "This device" or "Remote: <url>". */
    connectionLabel: String,
    /** Null hides the "Switch connection…" control entirely (nothing to switch away from
     * yet, or the platform doesn't support it). */
    onSwitchConnection: (() -> Unit)? = null,
    /** Forwarded straight to `SettingsDialog` — see its own doc comment (desktop's Servers
     * tab; Android passes neither). */
    extraSettingsTabLabel: String? = null,
    extraSettingsTabContent: (@Composable () -> Unit)? = null,
    /** Null hides the CLI status row — pass a map from a platform that can check PATH. */
    cliStatus: Map<String, Boolean>? = null,
    cliLogins: Map<String, Boolean>? = null,
    onRecheckCli: (() -> Unit)? = null,
    /** Hands the platform a ready Markdown string + suggested file name for its own "save
     * as" flow (JFileChooser on desktop, a document-creation intent on Android). Null hides
     * the export button. */
    onExportMarkdown: ((markdown: String, suggestedFileName: String) -> Unit)? = null,
    /** Opens the platform's file picker; the platform reads the file and delivers
     * (fileName, content) here. Null hides the attach button. */
    onPickFile: ((deliver: (fileName: String, content: String) -> Unit) -> Unit)? = null,
) {
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var selectedDiscussionId by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    run {
        val cc = LocalCcColors.current
        Column(Modifier.fillMaxSize().background(cc.bg)) {
            // Nothing thrown by a guarded call site ever reaches here as a crash — this is
            // just where it's shown. See EngineViewModel's class doc + launchGuarded.
            vm.lastError?.let { message -> ErrorBanner(message, onDismiss = vm::clearError) }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Sidebar(
                    state = vm.state,
                    selectedProjectId = selectedProjectId,
                    selectedDiscussionId = selectedDiscussionId,
                    onSelectProject = { id -> selectedProjectId = id; selectedDiscussionId = null },
                    onSelectDiscussion = { id -> selectedDiscussionId = id },
                    onAddProject = { name ->
                        scope.launchGuarded(vm) {
                            selectedProjectId = vm.addProject(name).id
                            selectedDiscussionId = null
                        }
                    },
                    onDeleteProject = { id ->
                        scope.launchGuarded(vm) {
                            vm.deleteProject(id)
                            if (selectedProjectId == id) { selectedProjectId = null; selectedDiscussionId = null }
                        }
                    },
                    onDeleteDiscussion = { id ->
                        scope.launchGuarded(vm) {
                            vm.deleteDiscussion(id)
                            if (selectedDiscussionId == id) selectedDiscussionId = null
                        }
                    },
                    onOpenSettings = { settingsOpen = true },
                    supportsCli = supportsCli,
                    cliStatus = cliStatus,
                    cliLogins = cliLogins,
                    onRecheckCli = onRecheckCli,
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val discussion = vm.state.discussions.firstOrNull { it.id == selectedDiscussionId }
                    // Resets whenever the selected discussion changes — editing one discussion's
                    // setup shouldn't leak into the next one you open.
                    var editingConfig by remember(selectedDiscussionId) { mutableStateOf(false) }
                    fun runDiscussion(d: com.dialex.model.Discussion) {
                        editingConfig = false
                        // vm.start flips this discussion's status to RUNNING as soon as the
                        // engine accepts the start — editingConfig=false plus that status
                        // change together are what actually dismiss this config screen for
                        // ChatView below, not just the click itself.
                        scope.launchGuarded(vm) { vm.start(d.id) }
                    }
                    when {
                        discussion != null && (discussion.status == DiscussionStatus.DRAFT || editingConfig) -> DiscussionConfigForm(
                            discussion = discussion,
                            supportsCli = supportsCli,
                            apiKeys = vm.state.apiKeys,
                            cliCommands = vm.state.cliCommands,
                            onChange = { updated -> scope.launchGuarded(vm) { vm.updateDiscussion(updated) } },
                            onStart = { runDiscussion(discussion) },
                            onOpenSettings = { settingsOpen = true },
                            onCancelEdit = if (discussion.status != DiscussionStatus.DRAFT) { { editingConfig = false } } else null,
                            copyFromOptions = vm.state.discussions.filter { it.id != discussion.id },
                            onAttachFile = onPickFile?.let { pick ->
                                {
                                    pick { fileName, content ->
                                        val info = discussion.config.commonInfo
                                        val appended = (if (info.isBlank()) "" else "$info\n\n") + "**Attached: $fileName**\n\n$content"
                                        scope.launchGuarded(vm) { vm.updateDiscussion(discussion.copy(config = discussion.config.copy(commonInfo = appended))) }
                                    }
                                }
                            },
                        )
                        discussion != null -> ChatView(
                            discussion = discussion,
                            onPause = { scope.launchGuarded(vm) { vm.pause(discussion.id) } },
                            onHardStop = { scope.launchGuarded(vm) { vm.hardStop(discussion.id) } },
                            onResume = { scope.launchGuarded(vm) { vm.resume(discussion.id) } },
                            onEditConfig = { editingConfig = true },
                            onExport = onExportMarkdown,
                            onGenerateHandoff = { vm.generateHandoffPrompt(discussion.id) },
                            onHandoffGenerated = {}, // EngineViewModel already persists + updates vm.state itself
                        )
                        selectedProjectId != null -> NewDiscussionCta {
                            scope.launchGuarded(vm) { selectedDiscussionId = vm.addDiscussion(selectedProjectId!!, "New Discussion").id }
                        }
                        else -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text("Select or create a project to get started", style = MaterialTheme.typography.bodyMedium, color = cc.textMuted)
                        }
                    }
                }
            }
        }

        if (settingsOpen) {
            SettingsDialog(
                currentKeys = vm.state.apiKeys,
                currentCommands = vm.state.cliCommands,
                currentCompactionModel = vm.state.compactionModel,
                currentTokenBudget = vm.state.tokenBudget,
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                connectionLabel = connectionLabel,
                onSwitchConnection = onSwitchConnection,
                extraTabLabel = extraSettingsTabLabel,
                extraTabContent = extraSettingsTabContent,
                onDismiss = { settingsOpen = false },
                onSave = { keys, commands, compactionModel, tokenBudget ->
                    scope.launchGuarded(vm) {
                        vm.updateApiKeys(keys)
                        vm.updateCliCommands(commands)
                        vm.updateCompactionModel(compactionModel)
                        vm.updateTokenBudget(tokenBudget)
                        settingsOpen = false
                        onRecheckCli?.invoke()
                    }
                },
            )
        }
    }
}

/** A dismissible strip for whatever [EngineViewModel.lastError] most recently recorded —
 * shown above the rest of the app, never blocking it (no dialog, nothing modal). */
@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val cc = LocalCcColors.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val items = remember(message) {
        message.split("\n")
            .flatMap { it.split("  •  ", " • ") }
            .map { it.trim().removePrefix("•").trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "•",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            item,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (copied) "Copied!" else "Copy",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (copied) cc.textPrimary else MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable {
                    val textToCopy = items.joinToString("\n") { "• $it" }
                    clipboard.setText(AnnotatedString(textToCopy))
                    copied = true
                }
            )
            Text(
                "Dismiss",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = cc.textMuted,
                modifier = Modifier.clickable(onClick = onDismiss)
            )
        }
    }
}

@Composable
private fun NewDiscussionCta(onCreate: () -> Unit) {
    val cc = LocalCcColors.current
    Box(Modifier.fillMaxSize().background(cc.bg), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No discussion selected", style = MaterialTheme.typography.titleMedium, color = cc.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text("Start a new debate — a Primary Agent always takes part (defaults to Claude), add up to two more.", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
            Spacer(Modifier.height(16.dp))
            GradientButton(
                text = "+ New Discussion",
                onClick = onCreate,
                height = 36.dp
            )
        }
    }
}
