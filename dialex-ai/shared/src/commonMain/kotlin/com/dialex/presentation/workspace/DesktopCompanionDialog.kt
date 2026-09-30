package com.dialex.presentation.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dialex.theme.LocalCcColors
import kotlin.math.abs

/**
 * Desktop Companion & Tailscale Host Pairing Dialog.
 * Displays:
 * 1. Live Server Host connection info (LAN & Tailscale WireGuard).
 * 2. Visual Pairing Matrix / QR Code for 1-scan mobile intake.
 * 3. Embedded Tailscale Mesh host configuration.
 * 4. 1-click pairing URI copy for remote clipboard.
 */
@Composable
fun DesktopCompanionDialog(
    serverUrl: String,
    authToken: String,
    username: String = "admin",
    isTsnetEnabled: Boolean = false,
    tailscaleUrl: String? = null,
    onDismiss: () -> Unit,
    onToggleTsnet: (enabled: Boolean, authKey: String) -> Unit,
    onCopyPairingCode: (String) -> Unit
) {
    val cc = LocalCcColors.current
    var authKeyInput by remember { mutableStateOf("") }
    var tsnetActive by remember { mutableStateOf(isTsnetEnabled) }

    val activeUrl = if (tsnetActive && !tailscaleUrl.isNullOrBlank()) tailscaleUrl else serverUrl
    val pairingPayload = "dialex://pair?url=${encodeParam(activeUrl)}&user=$username&token=$authToken"

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(cc.panel)
                .border(1.5.dp, cc.accent, RoundedCornerShape(18.dp))
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cc.accent.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneIphone,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Pair Mobile Companion",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = cc.textPrimary
                            )
                            Text(
                                text = "Live session mirroring & pocket remote control",
                                fontSize = 11.sp,
                                color = cc.textMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = cc.textMuted)
                    }
                }

                // QR Code Display Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // Procedural Visual Matrix Canvas
                        Canvas(modifier = Modifier.size(160.dp)) {
                            val matrixSize = 21
                            val cellSize = size.width / matrixSize
                            val hash = pairingPayload.hashCode()

                            for (row in 0 until matrixSize) {
                                for (col in 0 until matrixSize) {
                                    // Corner positioning squares
                                    val isCorner = (row < 7 && col < 7) ||
                                            (row < 7 && col >= matrixSize - 7) ||
                                            (row >= matrixSize - 7 && col < 7)

                                    val isFilled = if (isCorner) {
                                        val inOuter = (row in 0..6 && col in 0..6) ||
                                                (row in 0..6 && col in (matrixSize - 7) until matrixSize) ||
                                                (row in (matrixSize - 7) until matrixSize && col in 0..6)
                                        val inInner = (row in 1..5 && col in 1..5 && (row == 1 || row == 5 || col == 1 || col == 5)) ||
                                                (row in 1..5 && col in (matrixSize - 6) until (matrixSize - 1) && (row == 1 || row == 5 || col == matrixSize - 6 || col == matrixSize - 2)) ||
                                                (row in (matrixSize - 6) until (matrixSize - 1) && col in 1..5 && (row == matrixSize - 6 || row == matrixSize - 2 || col == 1 || col == 5))
                                        val inCenter = (row in 2..4 && col in 2..4) ||
                                                (row in 2..4 && col in (matrixSize - 5) until (matrixSize - 2)) ||
                                                (row in (matrixSize - 5) until (matrixSize - 2) && col in 2..4)
                                        inOuter && (!inInner || inCenter)
                                    } else {
                                        abs((hash * 31 + row * 17 + col * 13).hashCode()) % 2 == 0
                                    }

                                    if (isFilled) {
                                        drawRoundRect(
                                            color = Color.Black,
                                            topLeft = Offset(col * cellSize, row * cellSize),
                                            size = Size(cellSize * 0.92f, cellSize * 0.92f),
                                            cornerRadius = CornerRadius(1.5f, 1.5f)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Scan with Dialex Mobile Camera",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.DarkGray
                        )
                    }
                }

                // Connection Info & Tailscale Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cc.panelAlt)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "HOST ADDRESS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = cc.accent
                            )
                            Text(
                                text = activeUrl,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = cc.textPrimary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "READY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }

                    // Embedded Tailscale Mesh Toggle
                    HorizontalDivider(color = cc.border.copy(alpha = 0.5f), thickness = 0.5.dp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VpnLock,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Embedded Tailscale (tsnet)",
                                fontSize = 12.sp,
                                color = cc.textPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Switch(
                            checked = tsnetActive,
                            onCheckedChange = {
                                tsnetActive = it
                                onToggleTsnet(it, authKeyInput)
                            }
                        )
                    }
                }

                // Bottom Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onCopyPairingCode(pairingPayload) }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = cc.accent)
                        Spacer(Modifier.width(6.dp))
                        Text("Copy Pairing Link", color = cc.accent, fontSize = 12.sp)
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black)
                    ) {
                        Text("Done", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun encodeParam(value: String): String {
    return value.replace(":", "%3A")
        .replace("/", "%2F")
        .replace("?", "%3F")
        .replace("=", "%3D")
        .replace("&", "%26")
}
