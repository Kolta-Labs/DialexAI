package com.dialex.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Http
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.logging.ApiCallStore
import com.dialex.logging.AppLogLevel
import com.dialex.logging.AppLogStore
import com.dialex.presentation.settings.ApiCallInspector
import com.dialex.presentation.settings.ServerLogsViewer
import com.dialex.presentation.settings.SettingCard
import com.dialex.theme.LocalCcColors

enum class ServerSubPage {
    FLEET,
    API_INSPECTOR,
    SERVER_LOGS
}

/**
 * Settings → Servers:
 * 1. Overview of every `roundtable` engine process running on this machine.
 * 2. Independent sub-page for Chucker-style API & Network Call Inspector.
 * 3. Independent sub-page for Grouped & Summarized Server & Runtime Logs.
 */
@Composable
fun ServersTabContent() {
    val cc = LocalCcColors.current
    var subPage by remember { mutableStateOf(ServerSubPage.FLEET) }

    when (subPage) {
        ServerSubPage.FLEET -> {
            ServersFleetOverview(
                onOpenApiInspector = { subPage = ServerSubPage.API_INSPECTOR },
                onOpenServerLogs = { subPage = ServerSubPage.SERVER_LOGS }
            )
        }
        ServerSubPage.API_INSPECTOR -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Secondary Grey Back Button Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = { subPage = ServerSubPage.FLEET },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = cc.panelAlt,
                            contentColor = cc.textPrimary
                        ),
                        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Server Fleet",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Back to Server Fleet",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f))
                    ) {
                        Text(
                            "Sub-page: API & Network Inspector",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = cc.textMuted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                ApiCallInspector(modifier = Modifier.fillMaxSize())
            }
        }
        ServerSubPage.SERVER_LOGS -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Secondary Grey Back Button Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = { subPage = ServerSubPage.FLEET },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = cc.panelAlt,
                            contentColor = cc.textPrimary
                        ),
                        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.7f)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Server Fleet",
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Back to Server Fleet",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f))
                    ) {
                        Text(
                            "Sub-page: Grouped Server Logs",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = cc.textMuted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)

                ServerLogsViewer(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ServersFleetOverview(
    onOpenApiInspector: () -> Unit,
    onOpenServerLogs: () -> Unit
) {
    val cc = LocalCcColors.current
    var servers by remember { mutableStateOf(RunningEngines.list()) }
    var pendingKill by remember { mutableStateOf<RunningEngine?>(null) }
    var confirmingCleanup by remember { mutableStateOf(false) }

    val apiCalls by ApiCallStore.records.collectAsState()
    val allLogs by AppLogStore.logs.collectAsState()

    val apiErrors = remember(apiCalls) { apiCalls.count { it.status == com.dialex.logging.ApiCallStatus.ERROR } }
    val logErrors = remember(allLogs) { allLogs.count { it.level == AppLogLevel.ERROR } }
    val logWarns = remember(allLogs) { allLogs.count { it.level == AppLogLevel.WARN } }

    fun refresh() { servers = RunningEngines.list() }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // ── Section 1: Running Engine Fleet ──
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Running Engine Fleet",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = cc.textPrimary
            )
            Text(
                "All Dialex backend engine processes currently running on this machine. If a previous session terminated unexpectedly, background engine instances may remain active.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = cc.textMuted
            )

            SettingCard {
                if (servers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Dns,
                                contentDescription = null,
                                tint = cc.textMuted.copy(alpha = 0.5f),
                                modifier = Modifier.size(28.dp)
                            )
                            Text(
                                "No background engine processes found",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = cc.textMuted
                            )
                        }
                    }
                } else {
                    servers.forEachIndexed { index, engine ->
                        if (index > 0) {
                            HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.75.dp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(if (engine.isThisApp) cc.agentThird else cc.accent)
                                    )
                                    Text(
                                        "PID ${engine.pid}",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.5.sp
                                        ),
                                        color = cc.textPrimary
                                    )
                                    if (engine.isThisApp) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = cc.accent.copy(alpha = 0.15f),
                                            border = BorderStroke(0.75.dp, cc.accent.copy(alpha = 0.4f))
                                        ) {
                                            Text(
                                                "Active Window Engine",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                                color = cc.accent,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    engine.commandLine,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp
                                    ),
                                    color = cc.textMuted,
                                    maxLines = 1
                                )
                            }

                            Button(
                                onClick = { pendingKill = engine },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text(
                                    "Kill",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Fleet Action Controls
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { refresh() },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = cc.panelAlt,
                        contentColor = cc.textPrimary
                    ),
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.7f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Refresh Processes", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp))
                }
                if (servers.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { confirmingCleanup = true },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = cc.panelAlt,
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clean Up All", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp))
                    }
                }
            }
        }

        HorizontalDivider(color = cc.border.copy(alpha = 0.3f), thickness = 1.dp)

        // ── Section 2: Diagnostics & Telemetry Hub ──
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Diagnostics & Telemetry Hub",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                color = cc.textPrimary
            )
            Text(
                "Launch independent diagnostics consoles to monitor live agent API calls, CLI executions, and grouped server error traces.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = cc.textMuted
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Launcher Card 1: API Call Inspector
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cc.panel,
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenApiInspector)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = cc.accent.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.35f)),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.Speed,
                                        contentDescription = null,
                                        tint = cc.accent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "API & Network Call Inspector",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        ),
                                        color = cc.textPrimary
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (apiErrors > 0) Color(0xFFEF4444).copy(alpha = 0.15f) else cc.panelAlt,
                                        border = BorderStroke(0.5.dp, if (apiErrors > 0) Color(0xFFEF4444).copy(alpha = 0.4f) else cc.border.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            "${apiCalls.size} calls" + (if (apiErrors > 0) " · $apiErrors errors" else ""),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 10.5.sp
                                            ),
                                            color = if (apiErrors > 0) Color(0xFFEF4444) else cc.textMuted,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    "Chucker-style inspector tracking every Agent (Claude, OpenAI, Gemini), CLI runner, and Engine REST call with request/response payloads, latency, and cURL export.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = cc.textMuted
                                )
                            }
                        }

                        // Proper Secondary (Grey) Launch Button
                        OutlinedButton(
                            onClick = onOpenApiInspector,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = cc.panelAlt,
                                contentColor = cc.textPrimary
                            ),
                            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.8f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                "Open Inspector",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // Launcher Card 2: Server & Runtime Logs
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cc.panel,
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenServerLogs)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = cc.agentThird.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, cc.agentThird.copy(alpha = 0.35f)),
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.Terminal,
                                        contentDescription = null,
                                        tint = cc.agentThird,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Server & Runtime Logs",
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        ),
                                        color = cc.textPrimary
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (logErrors > 0) Color(0xFFEF4444).copy(alpha = 0.15f) else cc.panelAlt,
                                        border = BorderStroke(0.5.dp, if (logErrors > 0) Color(0xFFEF4444).copy(alpha = 0.4f) else cc.border.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            "${allLogs.size} logs" + (if (logErrors > 0) " · $logErrors errors" else ""),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Medium,
                                                fontSize = 10.5.sp
                                            ),
                                            color = if (logErrors > 0) Color(0xFFEF4444) else cc.textMuted,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    "Grouped error summaries, occurrence counts, stack trace inspector, and live console event stream from background engine processes.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = cc.textMuted
                                )
                            }
                        }

                        // Proper Secondary (Grey) Launch Button
                        OutlinedButton(
                            onClick = onOpenServerLogs,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = cc.panelAlt,
                                contentColor = cc.textPrimary
                            ),
                            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.8f)),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                "Open Logs",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }

    pendingKill?.let { engine ->
        com.dialex.ui.ConfirmDialog(
            title = "Kill engine process",
            message = "End PID ${engine.pid}?" +
                (if (engine.isThisApp) " This is the engine this window is currently using — closing it will break the app until you restart." else "") +
                " Any debate it's mid-turn on will be interrupted.",
            confirmLabel = "Kill",
            onDismiss = { pendingKill = null },
            onConfirm = { RunningEngines.kill(engine.pid, forcibly = true); refresh() },
        )
    }
    if (confirmingCleanup) {
        com.dialex.ui.ConfirmDialog(
            title = "Clean up all engine processes",
            message = "End all ${servers.size} `roundtable` process(es), including this app's own if it spawned one — the app will need restarting afterward. Any debate mid-turn on any of them will be interrupted.",
            confirmLabel = "Clean up",
            onDismiss = { confirmingCleanup = false },
            onConfirm = {
                servers.forEach { RunningEngines.kill(it.pid, forcibly = true) }
                refresh()
            },
        )
    }
}

