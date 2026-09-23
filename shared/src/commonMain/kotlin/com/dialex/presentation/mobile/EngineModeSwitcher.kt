package com.dialex.presentation.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.ProfileType
import com.dialex.theme.LocalCcColors

/**
 * 1-Tap Engine Mode Switcher between Standalone Local Engine and Remote Server.
 */
@Composable
fun EngineModeSwitcher(
    activeProfileType: ProfileType,
    remoteUrl: String?,
    onSelectLocal: () -> Unit,
    onSelectRemote: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val isLocal = activeProfileType == ProfileType.LOCAL

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cc.panel)
                .border(1.dp, cc.border, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Local Mode Option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isLocal) cc.panelAlt else Color.Transparent)
                    .border(
                        width = if (isLocal) 1.5.dp else 0.dp,
                        color = if (isLocal) cc.accent else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelectLocal() }
                    .padding(vertical = 12.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = "Local Engine",
                        tint = if (isLocal) cc.accent else cc.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Local Engine",
                            fontSize = 13.sp,
                            fontWeight = if (isLocal) FontWeight.Bold else FontWeight.Medium,
                            color = if (isLocal) cc.textPrimary else cc.textMuted
                        )
                        Text(
                            text = "Direct API Keys",
                            fontSize = 10.sp,
                            color = cc.textMuted
                        )
                    }
                    if (isLocal) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = cc.accent,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // Remote Mode Option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (!isLocal) cc.panelAlt else Color.Transparent)
                    .border(
                        width = if (!isLocal) 1.5.dp else 0.dp,
                        color = if (!isLocal) cc.accent else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onSelectRemote() }
                    .padding(vertical = 12.dp, horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = "Remote Server",
                        tint = if (!isLocal) cc.accent else cc.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Remote Server",
                            fontSize = 13.sp,
                            fontWeight = if (!isLocal) FontWeight.Bold else FontWeight.Medium,
                            color = if (!isLocal) cc.textPrimary else cc.textMuted
                        )
                        Text(
                            text = if (!remoteUrl.isNullOrBlank()) "Tailscale / LAN" else "Connect via QR",
                            fontSize = 10.sp,
                            color = cc.textMuted
                        )
                    }
                    if (!isLocal) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = cc.accent,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}
