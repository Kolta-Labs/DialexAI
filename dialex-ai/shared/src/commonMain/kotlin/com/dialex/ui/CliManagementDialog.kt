@file:Suppress("DEPRECATION")

package com.dialex.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.model.Provider
import com.dialex.presentation.settings.ProviderStatus
import com.dialex.service.CliInstallState
import com.dialex.service.CliToolDescriptor
import com.dialex.service.SupportedCliTools
import com.dialex.service.isPlatformCliSupported
import com.dialex.service.launchCliLoginTerminal
import com.dialex.service.runCliInstallCommand
import com.dialex.theme.LocalAppColors
import com.dialex.ui.cliauth.CliAuthDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CliManagementDialog(
    providerStatuses: List<ProviderStatus> = emptyList(),
    cliAvailability: Map<String, Boolean> = emptyMap(),
    cliLogins: Map<String, Boolean> = emptyMap(),
    onDismiss: () -> Unit,
    onRecheck: () -> Unit,
) {
    val cc = LocalAppColors.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()

    var installStates by remember { mutableStateOf<Map<String, CliInstallState>>(emptyMap()) }
    var activeLoginToolId by remember { mutableStateOf<String?>(null) }
    var activeAuthTool by remember { mutableStateOf<CliToolDescriptor?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    // Auto-polling for verification when login terminal was launched
    LaunchedEffect(activeLoginToolId) {
        if (activeLoginToolId != null) {
            repeat(15) {
                delay(3000)
                onRecheck()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier
                .width(620.dp)
                .heightIn(max = 680.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp)
            ) {
                // ── 1. Dialog Header ──────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(cc.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Terminal,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "CLI Tools & Agent Runners",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 17.sp
                                ),
                                color = cc.textPrimary
                            )
                            Text(
                                "One-click install & authenticate local CLI engines",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ThemedTooltipBox("Re-check system CLI status") {
                            IconButton(onClick = onRecheck, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Refresh, contentDescription = "Re-check", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                        ThemedTooltipBox("Close") {
                            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Info Banner
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (cc.isDark) Color(0xFF1E1F24) else Color(0xFFF3F4F6),
                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = cc.accent, modifier = Modifier.size(15.dp))
                        Text(
                            "CLI runners enable autonomous agent reasoning on your local device. API-key agents do not require CLI tools.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = cc.textMuted
                        )
                    }
                }

                if (actionMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cc.accent.copy(alpha = 0.12f),
                        border = BorderStroke(0.75.dp, cc.accent.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(actionMessage!!, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = cc.accent)
                            IconButton(onClick = { actionMessage = null }, modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = cc.accent, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── 2. CLI Tool Cards List ────────────────────────────────────
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SupportedCliTools.forEach { tool ->
                        val provStatus = providerStatuses.firstOrNull { it.provider == tool.provider }
                        val isInstalled = provStatus?.cliFound == true || cliAvailability[tool.name] == true || cliAvailability[tool.binaryName] == true
                        val isLoggedIn = provStatus?.cliLoggedIn == true || cliLogins[tool.name] == true || cliLogins[tool.binaryName] == true
                        val installState = installStates[tool.id] ?: CliInstallState.Idle

                        CliToolCard(
                            tool = tool,
                            isInstalled = isInstalled,
                            isLoggedIn = isLoggedIn,
                            installState = installState,
                            cc = cc,
                            onInstall = {
                                if (!isPlatformCliSupported()) {
                                    actionMessage = "In-app CLI installation is only available on desktop platforms."
                                    return@CliToolCard
                                }
                                installStates = installStates + (tool.id to CliInstallState.Installing(tool.id, listOf("Starting installation: ${tool.installCommand}...")))
                                coroutineScope.launch {
                                    val logLines = mutableListOf("Running: ${tool.installCommand}")
                                    val result = runCliInstallCommand(tool.installCommand) { line ->
                                        logLines.add(line)
                                        installStates = installStates + (tool.id to CliInstallState.Installing(tool.id, logLines.toList()))
                                    }
                                    if (result.isSuccess) {
                                        installStates = installStates + (tool.id to CliInstallState.Success(tool.id, "${tool.name} successfully installed!"))
                                        onRecheck()
                                    } else {
                                        val err = result.exceptionOrNull()?.message ?: "Installation failed"
                                        installStates = installStates + (tool.id to CliInstallState.Failed(tool.id, err, logLines.toList()))
                                    }
                                }
                            },
                            onLogin = {
                                if (!isPlatformCliSupported()) {
                                    clipboard.setText(AnnotatedString(tool.loginCommand))
                                    actionMessage = "Copied login command: ${tool.loginCommand}"
                                    return@CliToolCard
                                }
                                launchCliLoginTerminal(tool.loginCommand)
                                actionMessage = "Launched Terminal for ${tool.name} login. Complete auth in the terminal window, then click Verify."
                            },
                            onVerify = {
                                onRecheck()
                                actionMessage = "Checked status for ${tool.name}."
                            },
                            onCopyCommand = { cmd, label ->
                                clipboard.setText(AnnotatedString(cmd))
                                actionMessage = "Copied $label: $cmd"
                            },
                            onOpenDocs = {
                                uriHandler.openUri(tool.docsUrl)
                            }
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── 3. Footer ─────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Changes take effect immediately across all discussions.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = cc.textMuted
                    )
                    GradientButton(
                        text = "Done",
                        onClick = onDismiss,
                        height = 32.dp,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

    if (activeAuthTool != null) {
        CliAuthDialog(
            tool = activeAuthTool!!,
            onDismiss = { activeAuthTool = null },
            onSuccess = {
                val toolName = activeAuthTool?.name ?: "CLI"
                activeAuthTool = null
                onRecheck()
                actionMessage = "Successfully authenticated $toolName!"
            }
        )
    }
}

@Composable
private fun CliToolCard(
    tool: CliToolDescriptor,
    isInstalled: Boolean,
    isLoggedIn: Boolean,
    installState: CliInstallState,
    cc: com.dialex.theme.CcPalette,
    onInstall: () -> Unit,
    onLogin: () -> Unit,
    onVerify: () -> Unit,
    onCopyCommand: (cmd: String, label: String) -> Unit,
    onOpenDocs: () -> Unit,
) {
    val isInstalling = installState is CliInstallState.Installing
    var showLogs by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cc.panelAlt,
        border = BorderStroke(1.dp, if (isInstalled && isLoggedIn) Color(0xFF10B981).copy(alpha = 0.35f) else cc.border.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Name, Package, Status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                        color = cc.textPrimary
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (cc.isDark) Color(0xFF22232A) else Color(0xFFE5E7EB),
                        modifier = Modifier.padding(horizontal = 2.dp)
                    ) {
                        Text(
                            tool.binaryName,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                            color = cc.textMuted,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when {
                        isInstalled && isLoggedIn -> Color(0xFF10B981).copy(alpha = 0.15f)
                        isInstalled -> Color(0xFFFF9800).copy(alpha = 0.15f)
                        else -> Color(0xFFEF4444).copy(alpha = 0.12f)
                    },
                    border = BorderStroke(
                        0.75.dp,
                        when {
                            isInstalled && isLoggedIn -> Color(0xFF10B981).copy(alpha = 0.4f)
                            isInstalled -> Color(0xFFFF9800).copy(alpha = 0.4f)
                            else -> Color(0xFFEF4444).copy(alpha = 0.35f)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isInstalled && isLoggedIn -> Color(0xFF10B981)
                                        isInstalled -> Color(0xFFFF9800)
                                        else -> Color(0xFFEF4444)
                                    }
                                )
                        )
                        Text(
                            when {
                                isInstalled && isLoggedIn -> "Ready"
                                isInstalled -> "Login Required"
                                else -> "Not Installed"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                            color = when {
                                isInstalled && isLoggedIn -> Color(0xFF10B981)
                                isInstalled -> Color(0xFFFF9800)
                                else -> Color(0xFFEF4444)
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                tool.description,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = cc.textMuted
            )

            Spacer(Modifier.height(10.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when {
                        !isInstalled -> {
                            GradientButton(
                                text = if (isInstalling) "Installing..." else "1-Click Install",
                                onClick = onInstall,
                                enabled = !isInstalling,
                                isLoading = isInstalling,
                                icon = if (!isInstalling) Icons.Outlined.Download else null,
                                height = 30.dp,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            )

                            OutlinedButton(
                                onClick = { onCopyCommand(tool.installCommand, "install command") },
                                shape = RoundedCornerShape(7.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp), tint = cc.textMuted)
                                Spacer(Modifier.width(4.dp))
                                Text("Copy Command", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), color = cc.textMuted)
                            }
                        }

                        isInstalled && !isLoggedIn -> {
                            GradientButton(
                                text = "Log In from App",
                                onClick = onLogin,
                                icon = Icons.AutoMirrored.Outlined.Login,
                                height = 30.dp,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            )

                            OutlinedButton(
                                onClick = onVerify,
                                shape = RoundedCornerShape(7.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(12.dp), tint = cc.textPrimary)
                                Spacer(Modifier.width(4.dp))
                                Text("Verify Login", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), color = cc.textPrimary)
                            }

                            OutlinedButton(
                                onClick = { onCopyCommand(tool.loginCommand, "login command") },
                                shape = RoundedCornerShape(7.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp), tint = cc.textMuted)
                            }
                        }

                        else -> {
                            // Ready
                            GradientButton(
                                text = "Launch CLI",
                                onClick = onLogin,
                                icon = Icons.Outlined.Terminal,
                                height = 30.dp,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            )

                            OutlinedButton(
                                onClick = onVerify,
                                shape = RoundedCornerShape(7.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(12.dp), tint = cc.textMuted)
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (installState is CliInstallState.Installing || installState is CliInstallState.Failed || installState is CliInstallState.Success) {
                        TextButton(
                            onClick = { showLogs = !showLogs },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(if (showLogs) "Hide Logs" else "View Logs", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp), color = cc.accent)
                        }
                    }

                    ThemedTooltipBox("Open Documentation") {
                        IconButton(onClick = onOpenDocs, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Docs", tint = cc.textMuted, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }

            // Expandable Installation Logs Terminal Drawer
            AnimatedVisibility(
                visible = showLogs || isInstalling || installState is CliInstallState.Failed,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val logs = when (installState) {
                    is CliInstallState.Installing -> installState.logs
                    is CliInstallState.Failed -> installState.logs
                    else -> emptyList()
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (cc.isDark) Color(0xFF0F1014) else Color(0xFF1E1F24))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Terminal Output",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = Color(0xFF9CA3AF)
                        )
                        if (installState is CliInstallState.Failed) {
                            Text(
                                "Failed",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = Color(0xFFEF4444)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 120.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            logs.takeLast(30).forEach { line ->
                                Text(
                                    line,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        lineHeight = 13.sp
                                    ),
                                    color = if (installState is CliInstallState.Failed && line.contains("error", ignoreCase = true)) Color(0xFFEF4444) else Color(0xFFD1D5DB)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
