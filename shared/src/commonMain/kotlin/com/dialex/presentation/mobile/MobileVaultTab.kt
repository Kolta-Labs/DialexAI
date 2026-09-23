package com.dialex.presentation.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.ProfileType
import com.dialex.domain.repository.ApiKeyRepository
import com.dialex.domain.repository.ProfileRepository
import com.dialex.theme.LocalCcColors
import com.dialex.theme.ThemeMode

@Composable
fun MobileVaultTab(
    activeProfile: ConnectionProfile?,
    profileRepository: ProfileRepository,
    apiKeyRepository: ApiKeyRepository,
    onSwitchToLocal: () -> Unit,
    onSwitchToRemote: () -> Unit,
    onScanQr: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val currentType = activeProfile?.type ?: ProfileType.LOCAL

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
    ) {
        item {
            Text(
                text = "Vault & Connections",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = cc.textPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Manage your execution engine, hardware-backed keys, and server pairing.",
                style = MaterialTheme.typography.bodySmall,
                color = cc.textMuted
            )
        }

        item {
            // Engine Mode Switcher
            EngineModeSwitcher(
                activeProfileType = currentType,
                remoteUrl = activeProfile?.remoteUrl,
                onSelectLocal = onSwitchToLocal,
                onSelectRemote = onSwitchToRemote
            )
        }

        if (currentType == ProfileType.REMOTE) {
            item {
                // Remote Connection Details & QR Scanner Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cc.panel)
                        .border(1.dp, cc.border, RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Remote Server Connection",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = cc.textPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFF10B981).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Connected",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF10B981)
                                )
                            }
                        }

                        Text(
                            text = "Server URL: ${activeProfile?.remoteUrl ?: "http://127.0.0.1:7890"}",
                            fontSize = 12.sp,
                            color = cc.textMuted
                        )

                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = onScanQr,
                            colors = ButtonDefaults.buttonColors(containerColor = cc.accent, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Scan Desktop QR Code to Pair", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            item {
                // On-Device API Keys Editor
                MobileApiKeySettingsView(
                    profileId = activeProfile?.id ?: ConnectionProfile.LOCAL_PROFILE_ID,
                    apiKeyRepository = apiKeyRepository
                )
            }
        }

        item {
            // Appearance Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cc.panel)
                    .border(1.dp, cc.border, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Appearance Theme",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = cc.textPrimary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            val isSelected = themeMode == mode
                            Button(
                                onClick = { onThemeModeChange(mode) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) cc.accent else cc.panelAlt,
                                    contentColor = if (isSelected) Color.Black else cc.textPrimary
                                ),
                                modifier = Modifier.weight(1f).height(36.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
