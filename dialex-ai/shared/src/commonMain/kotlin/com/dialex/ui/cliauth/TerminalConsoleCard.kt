package com.dialex.ui.cliauth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.presentation.cliauth.CliAuthContract
import com.dialex.service.cliauth.CliAuthStatus
import com.dialex.theme.CcPalette

@Composable
fun TerminalConsoleCard(
    state: CliAuthContract.State,
    cc: CcPalette,
    onIntent: (CliAuthContract.Intent) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val isRunning = state.status is CliAuthStatus.Running || state.status is CliAuthStatus.Starting || state.status is CliAuthStatus.AwaitingBrowserAuth || state.status is CliAuthStatus.AwaitingCodeInput

    LaunchedEffect(state.terminalLines.size) {
        if (state.terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(state.terminalLines.size - 1)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Terminal Window
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (cc.isDark) Color(0xFF0F1014) else Color(0xFF1E1F24),
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                // Console Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) Color(0xFF4CAF50) else cc.textMuted)
                        )
                        Text(
                            if (isRunning) "SESSION ACTIVE" else "SESSION TERMINATED",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold),
                            color = if (isRunning) Color(0xFF4CAF50) else Color(0xFF9CA3AF)
                        )
                    }

                    Text(
                        "${state.terminalLines.size} lines",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.5.sp),
                        color = Color(0xFF6B7280)
                    )
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = Color(0xFF2E2E38), thickness = 0.5.dp)
                Spacer(Modifier.height(6.dp))

                // Scrollable Output Logs
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state.terminalLines) { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                ),
                                color = when {
                                    line.startsWith(">") -> Color(0xFF818CF8) // User input
                                    line.contains("error", ignoreCase = true) -> Color(0xFFEF4444)
                                    line.contains("success", ignoreCase = true) || line.contains("logged in", ignoreCase = true) -> Color(0xFF34D399)
                                    line.contains("http") -> Color(0xFF60A5FA)
                                    else -> Color(0xFFD1D5DB)
                                }
                            )
                        }
                    }
                }
            }
        }

        // Bottom Interactive Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.terminalInputText,
                onValueChange = { onIntent(CliAuthContract.Intent.UpdateTerminalInput(it)) },
                placeholder = { Text("Type input to CLI process...", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = cc.textMuted) },
                singleLine = true,
                enabled = isRunning,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, color = cc.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onIntent(CliAuthContract.Intent.SubmitTerminalInput) }),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cc.accent,
                    unfocusedBorderColor = cc.border.copy(alpha = 0.5f),
                    focusedContainerColor = if (cc.isDark) Color(0xFF14151A) else Color(0xFFFFFFFF),
                    unfocusedContainerColor = if (cc.isDark) Color(0xFF14151A) else Color(0xFFFFFFFF),
                ),
                modifier = Modifier.weight(1f).height(40.dp)
            )

            IconButton(
                onClick = { onIntent(CliAuthContract.Intent.SubmitTerminalInput) },
                enabled = isRunning && state.terminalInputText.isNotBlank(),
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isRunning && state.terminalInputText.isNotBlank()) cc.accent else cc.panelAlt)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = "Send to CLI",
                    tint = if (isRunning && state.terminalInputText.isNotBlank()) Color.White else cc.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }

            if (isRunning) {
                OutlinedButton(
                    onClick = { onIntent(CliAuthContract.Intent.CancelSession) },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Icon(Icons.Outlined.Cancel, contentDescription = "Cancel Session", modifier = Modifier.size(13.dp), tint = Color(0xFFEF4444))
                    Spacer(Modifier.width(4.dp))
                    Text("Cancel", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Color(0xFFEF4444))
                }
            }
        }
    }
}
