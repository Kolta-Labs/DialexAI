package com.dialex.ui.cliauth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.presentation.cliauth.CliAuthContract
import com.dialex.service.cliauth.CliAuthStatus
import com.dialex.theme.CcPalette
import com.dialex.ui.GradientButton

@Composable
fun GuidedAuthCard(
    state: CliAuthContract.State,
    cc: CcPalette,
    onIntent: (CliAuthContract.Intent) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val status = state.status

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Step 1: Open Browser & Authorize
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cc.panelAlt,
            border = BorderStroke(1.dp, if (state.oauthUrl != null) cc.accent.copy(alpha = 0.4f) else cc.border.copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(cc.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("1", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = cc.accent)
                        }
                        Column {
                            Text("Authorize in Browser", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = cc.textPrimary)
                            Text("Grant authentication access to your CLI account", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = cc.textMuted)
                        }
                    }

                    if (state.oauthUrl != null) {
                        GradientButton(
                            text = "Open Browser",
                            onClick = { onOpenUrl(state.oauthUrl) },
                            icon = Icons.AutoMirrored.Outlined.OpenInNew,
                            height = 30.dp,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        )
                    } else if (status is CliAuthStatus.Starting || status is CliAuthStatus.Running) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = cc.accent)
                            Text("Generating link...", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = cc.textMuted)
                        }
                    }
                }

                if (state.oauthUrl != null) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (cc.isDark) Color(0xFF14151A) else Color(0xFFF3F4F6),
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                state.oauthUrl,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                                color = cc.textMuted,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(state.oauthUrl)) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy Auth URL", tint = cc.textMuted, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                }
            }
        }

        // Step 2: Submit Verification Code (if requested by CLI)
        val isAwaitingCode = status is CliAuthStatus.AwaitingCodeInput || (status is CliAuthStatus.Running && state.oauthUrl != null)
        AnimatedVisibility(
            visible = isAwaitingCode || state.authCodeInput.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = cc.panelAlt,
                border = BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(cc.accent.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("2", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = cc.accent)
                        }
                        Column {
                            Text("Paste Authorization Code", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = cc.textPrimary)
                            Text("If your browser displayed a one-time code, paste it below", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = cc.textMuted)
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.authCodeInput,
                            onValueChange = { onIntent(CliAuthContract.Intent.UpdateAuthCodeInput(it)) },
                            placeholder = { Text("Paste code here...", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = cc.textMuted) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = cc.textPrimary),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = cc.accent,
                                unfocusedBorderColor = cc.border.copy(alpha = 0.5f),
                                focusedContainerColor = if (cc.isDark) Color(0xFF14151A) else Color(0xFFFFFFFF),
                                unfocusedContainerColor = if (cc.isDark) Color(0xFF14151A) else Color(0xFFFFFFFF),
                            ),
                            modifier = Modifier.weight(1f).height(44.dp)
                        )

                        GradientButton(
                            text = if (state.isSubmitting) "Submitting..." else "Submit",
                            onClick = { onIntent(CliAuthContract.Intent.SubmitAuthCode) },
                            enabled = state.authCodeInput.isNotBlank() && !state.isSubmitting,
                            isLoading = state.isSubmitting,
                            height = 44.dp,
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        )
                    }
                }
            }
        }

        // Step 3: Status / Waiting Feedback
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = when (status) {
                is CliAuthStatus.Success -> Color(0xFF4CAF50).copy(alpha = 0.12f)
                is CliAuthStatus.Failed -> Color(0xFFEF4444).copy(alpha = 0.12f)
                else -> if (cc.isDark) Color(0xFF14151A) else Color(0xFFF3F4F6)
            },
            border = BorderStroke(
                0.75.dp,
                when (status) {
                    is CliAuthStatus.Success -> Color(0xFF4CAF50).copy(alpha = 0.35f)
                    is CliAuthStatus.Failed -> Color(0xFFEF4444).copy(alpha = 0.35f)
                    else -> cc.border.copy(alpha = 0.3f)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (status) {
                    is CliAuthStatus.Success -> {
                        Icon(Icons.Outlined.CheckCircle, contentDescription = "Success", tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
                        Text(status.message, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = Color(0xFF4CAF50))
                    }
                    is CliAuthStatus.Failed -> {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = "Error", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                        Text(status.error, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = Color(0xFFEF4444))
                    }
                    is CliAuthStatus.Cancelled -> {
                        Icon(Icons.Outlined.Cancel, contentDescription = "Cancelled", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                        Text("Authentication session cancelled", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                    }
                    else -> {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cc.accent)
                        Text(
                            "Waiting for browser authentication to complete...",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = cc.textMuted
                        )
                    }
                }
            }
        }
    }
}
