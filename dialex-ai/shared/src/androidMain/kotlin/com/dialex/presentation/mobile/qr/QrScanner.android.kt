package com.dialex.presentation.mobile.qr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.theme.LocalCcColors

@Composable
actual fun QrScannerView(
    onQrCodeScanned: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier
) {
    val cc = LocalCcColors.current
    var inputPayload by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = modifier
                .fillMaxWidth(0.95f)
                .clip(RoundedCornerShape(16.dp))
                .background(cc.panel)
                .border(1.5.dp, cc.accent, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(cc.accent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = cc.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Scan QR / Paste Code",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = cc.textPrimary
                        )
                        Text(
                            text = "Pair mobile device with desktop or server",
                            fontSize = 11.sp,
                            color = cc.textMuted
                        )
                    }
                }

                Text(
                    text = "Scan or paste the pairing code from your Web Admin Dashboard at http://<server-ip>:7890/admin:",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = inputPayload,
                    onValueChange = { inputPayload = it },
                    placeholder = { Text("dialex://pair?url=...&token=...", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cc.accent,
                        unfocusedBorderColor = cc.border
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = cc.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (inputPayload.isNotBlank()) {
                                onQrCodeScanned(inputPayload.trim())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black),
                        enabled = inputPayload.isNotBlank()
                    ) {
                        Text("Pair Device", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
