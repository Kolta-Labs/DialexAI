package com.dialex.presentation.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Analytics
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
import androidx.compose.ui.window.DialogProperties
import com.dialex.domain.model.CredenceLedger
import com.dialex.theme.LocalCcColors

@Composable
fun CredenceDrawer(
    ledger: CredenceLedger,
    selectedRound: Int? = null,
    onSelectRound: (Int) -> Unit = {},
    onRecalculate: () -> Unit = {},
    onDismiss: () -> Unit,
    isRecalculating: Boolean = false,
) {
    val cc = LocalCcColors.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = maxWidth < 640.dp
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = if (isCompact) Alignment.BottomCenter else Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(if (isCompact) 1f else 0.92f)
                        .fillMaxHeight(if (isCompact) 0.92f else 0.88f)
                        .clip(
                            if (isCompact) RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                            else RoundedCornerShape(16.dp)
                        ),
                    color = cc.panel,
                    border = BorderStroke(1.dp, cc.border),
                    shadowElevation = 24.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isCompact) 16.dp else 24.dp)
                    ) {
                        if (isCompact) {
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 10.dp)
                                    .size(36.dp, 4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(cc.border.copy(alpha = 0.8f))
                                    .align(Alignment.CenterHorizontally)
                            )
                        }

                        // Top Header Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = cc.accent.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.4f)),
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Outlined.Analytics,
                                            contentDescription = null,
                                            tint = cc.accent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }

                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "Bayesian Credence & Uncertainty",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = cc.textPrimary
                                        )
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(
                                                    when (ledger.status) {
                                                        "CONVERGED" -> Color(0xFF10B981).copy(alpha = 0.18f)
                                                        "STALEMATE" -> Color(0xFFF43F5E).copy(alpha = 0.18f)
                                                        else -> cc.accent.copy(alpha = 0.18f)
                                                    }
                                                )
                                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = ledger.status,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = when (ledger.status) {
                                                    "CONVERGED" -> Color(0xFF10B981)
                                                    "STALEMATE" -> Color(0xFFF43F5E)
                                                    else -> cc.accent
                                                }
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Hypothesis probability shifts • Shannon entropy • Likelihood ratios",
                                        fontSize = 11.sp,
                                        color = cc.textMuted
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(onClick = onRecalculate, enabled = !isRecalculating) {
                                    if (isRecalculating) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = cc.accent)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = "Recalculate", tint = cc.textMuted)
                                    }
                                }
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close", tint = cc.textMuted)
                                }
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(thickness = 0.5.dp, color = cc.border.copy(alpha = 0.6f))
                        Spacer(Modifier.height(14.dp))

                        // Scrollable Content
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            item {
                                CredenceRibbonCanvas(
                                    ledger = ledger,
                                    selectedRound = selectedRound,
                                    onSelectRound = onSelectRound
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
