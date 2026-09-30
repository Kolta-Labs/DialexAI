@file:Suppress("DEPRECATION")

package com.dialex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dialex.theme.LocalCcColors

/** One CLI's install info shown in the help dialog. */
data class CliInfo(val name: String, val installCommand: String, val loginCommand: String, val docs: String)

val CliCatalog = listOf(
    CliInfo(
        name = "Claude Code",
        installCommand = "npm install -g @anthropic-ai/claude-code",
        loginCommand = "claude auth login",
        docs = "docs.claude.com/claude-code",
    ),
    CliInfo(
        name = "Codex (OpenAI)",
        installCommand = "npm install -g @openai/codex",
        loginCommand = "codex login",
        docs = "developers.openai.com/codex",
    ),
    CliInfo(
        name = "Antigravity (Gemini)",
        installCommand = "npm install -g @google/gemini-cli   (installs the `agy` binary — check Google's docs if the package name has moved)",
        loginCommand = "agy  (then follow the login prompt)",
        docs = "Google renamed the Gemini CLI to \"Antigravity\"; its binary is `agy`. If that's not found, older `antigravity` or `gemini` installs still work.",
    ),
)

/**
 * Bottom-left status row: a dot per CLI (green = found on PATH). Click opens install
 * instructions for whichever are missing.
 *
 * `supportsCli` hides the row entirely on platforms with no CLI at all (Android).
 * `status` null (while `supportsCli` is true) means the check hasn't finished yet — shown
 * as "Checking CLI tools…" instead of silently rendering nothing, so a stuck check is
 * visible instead of looking like a missing feature.
 */
@Composable
fun CliStatusFooter(
    supportsCli: Boolean,
    status: Map<String, Boolean>?,
    cliLogins: Map<String, Boolean>? = null,
    onRecheck: (() -> Unit)? = null
) {
    if (!supportsCli) return
    val cc = LocalCcColors.current
    var dialogOpen by remember { mutableStateOf(false) }

    if (status == null) {
        Text(
            "Checking CLI tools…",
            style = MaterialTheme.typography.labelSmall,
            color = cc.textMuted,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        )
        return
    }

    val greenColor = Color(0xFF10B981)
    val redColor = Color(0xFFEF4444)

    Row(
        Modifier.fillMaxWidth()
            .clickable { dialogOpen = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CliCatalog.forEach { cli ->
            val shortName = cli.name.substringBefore(' ')
            val isInstalled = status[cli.name] == true || status[shortName] == true
            val isLoggedIn = if (cliLogins != null && cliLogins.isNotEmpty()) {
                cliLogins[cli.name] == true || cliLogins[shortName] == true
            } else {
                isInstalled
            }
            val isAvailable = isInstalled && isLoggedIn
            val dotColor = if (isAvailable) greenColor else redColor

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                Text(shortName, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    if (dialogOpen) {
        CliManagementDialog(
            cliAvailability = status,
            cliLogins = cliLogins ?: emptyMap(),
            onDismiss = { dialogOpen = false },
            onRecheck = { onRecheck?.invoke() }
        )
    }
}

@Composable
private fun InstallLine(cc: com.dialex.theme.CcPalette, label: String, command: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
        SelectionContainer(Modifier.weight(1f)) {
            Text(command, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { clipboard.setText(AnnotatedString(command)) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("Copy", style = MaterialTheme.typography.labelSmall, color = cc.textPrimary)
        }
    }
}
