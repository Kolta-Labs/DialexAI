package com.dialex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dialex.theme.LocalCcColors

/**
 * Shown once, before the engine bootstrap — the choice between running an engine on this
 * device (the default, invisible the rest of the time) and connecting to a different one
 * instead (a desktop, or any other self-hosted instance). Both desktop and mobile need this:
 * a remote-only mobile app would be useless without some desktop already running in engine
 * mode, and a desktop that can only ever run its own local engine can't join a household's
 * shared one either.
 *
 * Platform-agnostic — desktop and Android both call this, differing only in what they do
 * with the result (see each platform's own `ConnectionPreferenceStore`/bootstrap code) and in
 * `supportsLocalEngine` (false wherever there's no bundled on-device binary yet, e.g. iOS).
 */
@Composable
fun ConnectScreen(
    supportsLocalEngine: Boolean,
    onConnectLocal: () -> Unit,
    onConnectRemote: (url: String, user: String, pass: String) -> Unit,
    errorMessage: String?,
    connecting: Boolean,
) {
    val cc = LocalCcColors.current
    var remoteMode by remember { mutableStateOf(!supportsLocalEngine) }
    var url by remember { mutableStateOf("http://localhost:3000") }
    var username by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize().background(cc.bg), Alignment.Center) {
        Column(
            Modifier.width(360.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DialexLogoView(size = 56.dp, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.height(12.dp))
            Text("Dialex", style = MaterialTheme.typography.headlineSmall, color = cc.textPrimary)
            Spacer(Modifier.height(24.dp))

            if (supportsLocalEngine) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cc.panelAlt)
                        .border(1.dp, cc.border, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!remoteMode) cc.panelAlt else Color.Transparent)
                            .border(
                                width = if (!remoteMode) 1.dp else 0.dp,
                                color = if (!remoteMode) cc.border else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { remoteMode = false }
                            .padding(vertical = 10.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AestheticRadioButton(
                                selected = !remoteMode,
                                onClick = null,
                                size = 18.dp
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "This device",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (!remoteMode) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = cc.textPrimary
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (remoteMode) cc.panelAlt else Color.Transparent)
                            .border(
                                width = if (remoteMode) 1.dp else 0.dp,
                                color = if (remoteMode) cc.border else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { remoteMode = true }
                            .padding(vertical = 10.dp, horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AestheticRadioButton(
                                selected = remoteMode,
                                onClick = null,
                                size = 18.dp
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "Another engine",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (remoteMode) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = cc.textPrimary
                            )
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }

            if (!remoteMode) {
                Text(
                    "Runs the engine on this device — nothing to configure, works offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted,
                )
                Spacer(Modifier.height(16.dp))
                GradientButton(
                    text = if (connecting) "Starting…" else "Use this device",
                    onClick = onConnectLocal,
                    enabled = !connecting,
                    height = 44.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    "Connect to an engine already running elsewhere — a desktop, or any " +
                        "other self-hosted Dialex engine on your network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Engine URL, e.g. http://192.168.1.20:7890") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                GradientButton(
                    text = if (connecting) "Connecting…" else "Connect",
                    onClick = { onConnectRemote(url.trim(), username.trim(), password) },
                    enabled = !connecting && url.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    height = 44.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(12.dp))
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
