package com.dialex.ui.cliauth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import com.dialex.presentation.cliauth.CliAuthContract
import com.dialex.presentation.cliauth.CliAuthViewModel
import com.dialex.service.CliToolDescriptor
import com.dialex.service.isPlatformCliSupported
import com.dialex.service.launchCliLoginTerminal
import com.dialex.service.cliauth.CliAuthJourneyMode
import com.dialex.service.cliauth.CliAuthStatus
import com.dialex.theme.LocalAppColors
import com.dialex.theme.accentColor
import com.dialex.ui.ThemedTooltipBox
import kotlinx.coroutines.delay

@Composable
fun CliAuthDialog(
    tool: CliToolDescriptor,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val cc = LocalAppColors.current
    val uriHandler = LocalUriHandler.current

    val viewModel = remember(tool.id) { CliAuthViewModel(tool) }
    DisposableEffect(viewModel) {
        onDispose {
            viewModel.onCleared()
        }
    }

    val state by viewModel.state.collectAsState()

    // Handle MVI Effects
    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is CliAuthContract.Effect.OpenUrl -> {
                    runCatching { uriHandler.openUri(effect.url) }
                }
                is CliAuthContract.Effect.SessionCompleted -> {
                    if (effect.isSuccess) {
                        delay(1200)
                        onSuccess()
                    }
                }
                is CliAuthContract.Effect.ShowSnackbar -> {}
            }
        }
    }

    Dialog(onDismissRequest = {
        viewModel.onIntent(CliAuthContract.Intent.CancelSession)
        onDismiss()
    }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier
                .width(620.dp)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // ── 1. Dialog Header ──────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(tool.provider.accentColor(cc).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Key,
                                contentDescription = null,
                                tint = tool.provider.accentColor(cc),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "Log In to ${tool.name}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp
                                    ),
                                    color = cc.textPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (cc.isDark) Color(0xFF22232A) else Color(0xFFE5E7EB)
                                ) {
                                    Text(
                                        tool.binaryName,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                                        color = cc.textMuted,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                "Authenticate directly inside Dialex",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isPlatformCliSupported()) {
                            ThemedTooltipBox("Open in System Terminal (Terminal.app)") {
                                IconButton(
                                    onClick = {
                                        launchCliLoginTerminal(tool.loginCommand)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.OpenInNew,
                                        contentDescription = "Open in System Terminal",
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }

                        ThemedTooltipBox("Close") {
                            IconButton(
                                onClick = {
                                    viewModel.onIntent(CliAuthContract.Intent.CancelSession)
                                    onDismiss()
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── 2. Journey Mode Switcher Tabs ─────────────────────────────
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Guided Tab
                        val isGuided = state.journeyMode == CliAuthJourneyMode.GUIDED
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (isGuided) cc.accent.copy(alpha = 0.15f) else Color.Transparent,
                            border = BorderStroke(0.5.dp, if (isGuided) cc.accent.copy(alpha = 0.4f) else Color.Transparent),
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.onIntent(CliAuthContract.Intent.ChangeJourneyMode(CliAuthJourneyMode.GUIDED)) }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Outlined.AutoAwesome,
                                    contentDescription = null,
                                    tint = if (isGuided) cc.accent else cc.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "✨ Guided Sign-In",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 12.sp,
                                        fontWeight = if (isGuided) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (isGuided) cc.accent else cc.textMuted
                                )
                            }
                        }

                        // Terminal Tab
                        val isTerminal = state.journeyMode == CliAuthJourneyMode.TERMINAL
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (isTerminal) cc.accent.copy(alpha = 0.15f) else Color.Transparent,
                            border = BorderStroke(0.5.dp, if (isTerminal) cc.accent.copy(alpha = 0.4f) else Color.Transparent),
                            modifier = Modifier.weight(1f),
                            onClick = { viewModel.onIntent(CliAuthContract.Intent.ChangeJourneyMode(CliAuthJourneyMode.TERMINAL)) }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Terminal,
                                    contentDescription = null,
                                    tint = if (isTerminal) cc.accent else cc.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "💻 In-App Terminal",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 12.sp,
                                        fontWeight = if (isTerminal) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = if (isTerminal) cc.accent else cc.textMuted
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── 3. Active Journey Card Content ────────────────────────────
                when (state.journeyMode) {
                    CliAuthJourneyMode.GUIDED -> {
                        GuidedAuthCard(
                            state = state,
                            cc = cc,
                            onIntent = viewModel::onIntent,
                            onOpenUrl = { url ->
                                runCatching { uriHandler.openUri(url) }
                            }
                        )
                    }
                    CliAuthJourneyMode.TERMINAL -> {
                        TerminalConsoleCard(
                            state = state,
                            cc = cc,
                            onIntent = viewModel::onIntent
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── 4. Dialog Footer ──────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (state.status is CliAuthStatus.Failed || state.status is CliAuthStatus.Cancelled) {
                        OutlinedButton(
                            onClick = { viewModel.onIntent(CliAuthContract.Intent.RestartSession) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Retry", modifier = Modifier.size(13.dp), tint = cc.textPrimary)
                            Spacer(Modifier.width(4.dp))
                            Text("Retry Session", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp), color = cc.textPrimary)
                        }
                    } else {
                        Text(
                            "Sign-in is handled by the CLI; Dialex does not store these credentials.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = cc.textMuted
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            viewModel.onIntent(CliAuthContract.Intent.CancelSession)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Dismiss", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp), color = cc.textMuted)
                    }
                }
            }
        }
    }
}
