package com.dialex.android

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dialex.model.DiscussionStatus
import com.dialex.theme.ClaudeCodeTheme
import com.dialex.theme.LocalCcColors
import com.dialex.ui.ChatView
import com.dialex.ui.DiscussionConfigForm
import com.dialex.ui.SettingsDialog
import com.dialex.ui.Sidebar
import com.dialex.util.installCrashLogger
import com.dialex.viewmodel.EngineViewModel
import com.dialex.viewmodel.launchGuarded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Mobile shell: same screens as desktop, no CLI mode. Collapsed single-pane nav — project
 * list is its own screen, selecting a project shows its discussion list + "New Discussion"
 * CTA, picking/creating a discussion pushes into config/chat, back returns to the list.
 * No nav library, just local screen state.
 *
 * Engine-backed, same as desktop, via [EngineProcessManager] — the engine runs on-device
 * (see that class's doc comment) so this works with no network at all, and any device on the
 * same LAN can pair with it later the same way it would with a desktop instance.
 */
class MainActivity : FragmentActivity() {
    // The system document picker only hands back a Uri asynchronously, so the Markdown
    // waiting to be written has to live somewhere until that callback fires.
    private var pendingMarkdown: String? = null

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri: Uri? ->
        val markdown = pendingMarkdown
        pendingMarkdown = null
        if (uri != null && markdown != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(markdown.toByteArray()) }
        }
    }

    // Same async-callback shape as export, for attaching a file's text content.
    private var pendingDeliver: ((fileName: String, content: String) -> Unit)? = null

    private val getContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val deliver = pendingDeliver
        pendingDeliver = null
        if (uri != null && deliver != null) {
            // ponytail: uses the last URI path segment as the file name rather than
            // querying OpenableColumns.DISPLAY_NAME — good enough for most providers,
            // upgrade if a picker turns up with an unhelpful path segment.
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            if (text != null) deliver(name, text)
        }
    }

    private val engineManager by lazy { EngineProcessManager(applicationContext) }
    private val preferenceStore by lazy { ConnectionPreferenceStore(applicationContext) }
    private val themeStore by lazy { ThemePreferenceStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.dialex.data.db.AndroidContextHolder.init(applicationContext)
        installCrashLogger(File(filesDir, "crash.log"))

        setContent {
            // One theme root for the whole activity — lock/connect/starting/main screens all
            // need to agree on light/dark/system, not just the main screen (see AppShell's
            // own doc comment for the same reasoning on desktop).
            var themeMode by remember { mutableStateOf(themeStore.load()) }
            ClaudeCodeTheme(themeMode) {
                val cc = LocalCcColors.current

                // App-unlock gate, ahead of everything else (including the connect screen) —
                // see BiometricAuthenticator's doc comment for exactly what this does and
                // doesn't cover (launch-time only, not re-locking on resume).
                var unlocked by remember { mutableStateOf(false) }
                var lockError by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    if (!BiometricAuthenticator.isAvailable(this@MainActivity)) {
                        unlocked = true
                    } else {
                        BiometricAuthenticator.authenticate(
                            this@MainActivity,
                            onSuccess = { unlocked = true },
                            onError = { lockError = it },
                        )
                    }
                }

                if (!unlocked) {
                    Box(Modifier.fillMaxSize().background(cc.bg), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Roundtable is locked", style = MaterialTheme.typography.titleMedium, color = cc.textPrimary)
                            if (lockError != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(lockError!!, style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = {
                                lockError = null
                                BiometricAuthenticator.authenticate(
                                    this@MainActivity,
                                    onSuccess = { unlocked = true },
                                    onError = { lockError = it },
                                )
                            }) { Text("Unlock") }
                        }
                    }
                    return@ClaudeCodeTheme
                }

                var engineClient by remember { mutableStateOf<com.dialex.engine.EngineClient?>(null) }
                var bootstrapError by remember { mutableStateOf<String?>(null) }
                var connecting by remember { mutableStateOf(false) }
                var preference by remember { mutableStateOf(preferenceStore.load()) }
                val scope = rememberCoroutineScope()

                suspend fun connect(makeClient: suspend () -> com.dialex.engine.EngineClient) {
                    connecting = true
                    bootstrapError = null
                    runCatching {
                        makeClient()
                    }.onSuccess { engineClient = it }.onFailure { bootstrapError = it.message ?: it.toString() }
                    connecting = false
                }

                LaunchedEffect(preference) {
                    if (preference is ConnectionPreference.ThisDevice) connect { engineManager.start() }
                }

                com.dialex.App(
                    engineClient = engineClient,
                    supportsCli = false,
                    supportsLocalEngine = true,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        themeStore.save(mode)
                    },
                    connectionLabel = when (val p = preference) {
                        is ConnectionPreference.ThisDevice -> "This device"
                        is ConnectionPreference.Remote -> "Remote: ${p.url}"
                        null -> ""
                    },
                    onSwitchConnection = {
                        engineManager.stop()
                        preferenceStore.clear()
                        preference = null
                        engineClient = null
                        bootstrapError = null
                    },
                    connectToLocalEngine = {
                        connect { engineManager.start() }
                    },
                    connectToRemote = { url, username, password ->
                        connect { com.dialex.engine.EngineClient(url).also { it.login(username, password) } }
                        if (bootstrapError == null) preferenceStore.save(ConnectionPreference.Remote(url, username))
                    },
                    recheckCli = { emptyList() },
                    onExportMarkdown = { markdown, suggestedFileName ->
                        pendingMarkdown = markdown
                        createDocument.launch(suggestedFileName)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engineManager.stop()
    }

    @Composable
    private fun MainScreen(
        vm: EngineViewModel,
        scope: CoroutineScope,
        themeMode: com.dialex.theme.ThemeMode,
        onThemeModeChange: (com.dialex.theme.ThemeMode) -> Unit,
        connectionLabel: String,
        onSwitchConnection: () -> Unit,
    ) {
        var selectedProjectId by remember { mutableStateOf<String?>(null) }
        var selectedDiscussionId by remember { mutableStateOf<String?>(null) }
        var settingsOpen by remember { mutableStateOf(false) }

        run {
            val cc = LocalCcColors.current
            Column(Modifier.fillMaxSize().background(cc.bg)) {
                // Nothing thrown by a guarded call site ever reaches here as a crash — this
                // is just where it's shown. See EngineViewModel's class doc + launchGuarded.
                vm.lastError?.let { message ->
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = vm::clearError) { Text("Dismiss") }
                    }
                }
                val discussion = vm.state.discussions.firstOrNull { it.id == selectedDiscussionId }
                when {
                    discussion == null && selectedProjectId == null -> Sidebar(
                        state = vm.state,
                        selectedProjectId = null,
                        selectedDiscussionId = null,
                        onSelectProject = { selectedProjectId = it },
                        onSelectDiscussion = { selectedDiscussionId = it },
                        onAddProject = { name -> scope.launchGuarded(vm) { vm.addProject(name) } },
                        onDeleteProject = { id -> scope.launchGuarded(vm) { vm.deleteProject(id) } },
                        onDeleteDiscussion = { id -> scope.launchGuarded(vm) { vm.deleteDiscussion(id) } },
                        onOpenSettings = { settingsOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    discussion == null -> {
                        BackBar { selectedProjectId = null }
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("No discussion yet", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = {
                                    scope.launchGuarded(vm) { selectedDiscussionId = vm.addDiscussion(selectedProjectId!!, "New Discussion").id }
                                }) { Text("+ New Discussion") }
                            }
                        }
                    }
                    else -> {
                        BackBar { selectedDiscussionId = null }
                        // Resets whenever a different discussion is opened.
                        var editingConfig by remember(discussion.id) { mutableStateOf(false) }
                        fun runDiscussion() {
                            editingConfig = false
                            // vm.start flips this discussion's status to RUNNING as soon as
                            // the engine accepts the start — that status change plus
                            // editingConfig=false together dismiss this config screen right
                            // away, not just the click itself.
                            scope.launchGuarded(vm) { vm.start(discussion.id) }
                        }
                        if (discussion.status == DiscussionStatus.DRAFT || editingConfig) {
                            DiscussionConfigForm(
                                discussion = discussion,
                                supportsCli = false,
                                apiKeys = vm.state.apiKeys,
                                cliCommands = vm.state.cliCommands,
                                onChange = { updated -> scope.launchGuarded(vm) { vm.updateDiscussion(updated) } },
                                onStart = { runDiscussion() },
                                onOpenSettings = { settingsOpen = true },
                                onCancelEdit = if (discussion.status != DiscussionStatus.DRAFT) { { editingConfig = false } } else null,
                                copyFromOptions = vm.state.discussions.filter { it.id != discussion.id },
                                onAttachFile = {
                                    pendingDeliver = { fileName, content ->
                                        val info = discussion.config.commonInfo
                                        val appended = (if (info.isBlank()) "" else "$info\n\n") + "**Attached: $fileName**\n\n$content"
                                        scope.launchGuarded(vm) { vm.updateDiscussion(discussion.copy(config = discussion.config.copy(commonInfo = appended))) }
                                    }
                                    getContent.launch("*/*")
                                },
                            )
                        } else {
                            ChatView(
                                discussion = discussion,
                                onPause = { scope.launchGuarded(vm) { vm.pause(discussion.id) } },
                                onHardStop = { scope.launchGuarded(vm) { vm.hardStop(discussion.id) } },
                                onResume = { scope.launchGuarded(vm) { vm.resume(discussion.id) } },
                                onEditConfig = { editingConfig = true },
                                onExport = { markdown, suggestedFileName ->
                                    pendingMarkdown = markdown
                                    createDocument.launch(suggestedFileName)
                                },
                                onGenerateHandoff = { vm.generateHandoffPrompt(discussion.id) },
                                onHandoffGenerated = {}, // EngineViewModel already persists + updates vm.state itself
                            )
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
                    onDismiss = { settingsOpen = false },
                    onSave = { keys, commands, compactionModel, tokenBudget ->
                        scope.launchGuarded(vm) {
                            vm.updateApiKeys(keys)
                            vm.updateCliCommands(commands)
                            vm.updateCompactionModel(compactionModel)
                            vm.updateTokenBudget(tokenBudget)
                            settingsOpen = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun BackBar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Back") }
    }
}
