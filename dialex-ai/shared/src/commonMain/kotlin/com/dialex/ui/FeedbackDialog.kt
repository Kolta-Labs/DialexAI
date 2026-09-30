package com.dialex.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.service.TelegramFeedbackService
import com.dialex.theme.LocalAppColors
import kotlinx.coroutines.launch

@Composable
fun FeedbackDialog(
    initialBotToken: String = "",
    initialChatId: String = "",
    sessionTranscript: String? = null,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val cc = LocalAppColors.current
    val coroutineScope = rememberCoroutineScope()

    var description by remember { mutableStateOf("") }
    var botToken by remember { mutableStateOf(initialBotToken) }
    var chatId by remember { mutableStateOf(initialChatId) }
    var showConfig by remember { mutableStateOf(initialBotToken.isBlank() || initialChatId.isBlank()) }
    var isSending by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!isSending) onDismiss() }) {
        Column(
            modifier = Modifier
                .width(460.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(cc.panel)
                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)), RoundedCornerShape(14.dp))
                .padding(22.dp)
        ) {
            Text(
                text = "Send feedback",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                ),
                color = cc.textPrimary
            )

            Spacer(Modifier.height(14.dp))

            // Multiline Issue Description Box
            BasicTextField(
                value = description,
                onValueChange = {
                    description = it
                    errorMessage = null
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = cc.textPrimary,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(cc.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 130.dp, max = 220.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(cc.panelAlt)
                    .border(BorderStroke(0.75.dp, cc.border.copy(alpha = 0.4f)), RoundedCornerShape(8.dp))
                    .padding(12.dp),
                decorationBox = { innerTextField ->
                    if (description.isEmpty()) {
                        Text(
                            "Describe the issue",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                            color = cc.textMuted.copy(alpha = 0.6f)
                        )
                    }
                    innerTextField()
                }
            )

            Spacer(Modifier.height(10.dp))

            // Helper disclaimer note matching user screenshot
            Text(
                text = "This report will include your description and the current session transcript. We may use these to debug related issues and improve Dialex.",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                ),
                color = cc.textMuted.copy(alpha = 0.8f)
            )

            Spacer(Modifier.height(10.dp))

            // Telegram Bot Configuration (Expandable)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showConfig = !showConfig }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Telegram Bot Configuration",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = cc.accent
                )
                Icon(
                    if (showConfig) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    tint = cc.accent,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(visible = showConfig) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Bot Token", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = cc.textMuted)
                        BasicTextField(
                            value = botToken,
                            onValueChange = { botToken = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary, fontSize = 12.sp),
                            cursorBrush = SolidColor(cc.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(cc.panelAlt)
                                .border(BorderStroke(0.5.dp, cc.border.copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            decorationBox = { inner ->
                                if (botToken.isEmpty()) {
                                    Text("e.g. 123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = cc.textMuted.copy(alpha = 0.5f))
                                }
                                inner()
                            }
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Chat ID", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = cc.textMuted)
                        BasicTextField(
                            value = chatId,
                            onValueChange = { chatId = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = cc.textPrimary, fontSize = 12.sp),
                            cursorBrush = SolidColor(cc.accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(cc.panelAlt)
                                .border(BorderStroke(0.5.dp, cc.border.copy(alpha = 0.35f)), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            decorationBox = { inner ->
                                if (chatId.isEmpty()) {
                                    Text("e.g. -1001234567890 or @channelname", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = cc.textMuted.copy(alpha = 0.5f))
                                }
                                inner()
                            }
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(18.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isSending
                ) {
                    Text(
                        "Cancel",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                        color = cc.textMuted
                    )
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (description.isBlank()) {
                            errorMessage = "Please enter a description"
                            return@Button
                        }
                        if (botToken.isBlank() || chatId.isBlank()) {
                            showConfig = true
                            errorMessage = "Please provide Telegram Bot Token and Chat ID"
                            return@Button
                        }

                        isSending = true
                        errorMessage = null

                        coroutineScope.launch {
                            val result = TelegramFeedbackService.sendFeedback(
                                botToken = botToken,
                                chatId = chatId,
                                description = description,
                                sessionTranscript = sessionTranscript
                            )
                            isSending = false
                            if (result.isSuccess) {
                                onSuccess()
                            } else {
                                errorMessage = result.exceptionOrNull()?.message ?: "Failed to send feedback"
                            }
                        }
                    },
                    enabled = !isSending && description.isNotBlank(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cc.accent,
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Sending...", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp))
                    } else {
                        Text("Send", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium))
                    }
                }
            }
        }
    }
}
