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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dialex.theme.AppTheme
import com.dialex.theme.LocalCcColors
import com.dialex.util.installCrashLogger
import java.io.File

/**
 * Android host activity running unified [com.dialex.App] with Navigation 3.
 *
 * Engine-backed, same as desktop, via [EngineProcessManager] — the engine runs on-device
 * so this works with no network at all, and any device on the same LAN can pair with it later.
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

    private val engineManager by lazy { EngineProcessManager(applicationContext) }
    private val preferenceStore by lazy { ConnectionPreferenceStore(applicationContext) }
    private val themeStore by lazy { ThemePreferenceStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.dialex.data.db.AndroidContextHolder.init(applicationContext)
        installCrashLogger(File(filesDir, "crash.log"))

        setContent {
            // One theme root for the whole activity — lock/connect/starting/main screens all
            // agree on light/dark/system via AppTheme.
            var themeMode by remember { mutableStateOf(themeStore.load()) }
            AppTheme(themeMode) {
                val cc = LocalCcColors.current

                // App-unlock gate ahead of everything else (launch-time authentication).
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
                    return@AppTheme
                }

                var engineClient by remember { mutableStateOf<com.dialex.engine.EngineClient?>(null) }
                var bootstrapError by remember { mutableStateOf<String?>(null) }
                var connecting by remember { mutableStateOf(false) }
                var preference by remember { mutableStateOf(preferenceStore.load()) }

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
}
