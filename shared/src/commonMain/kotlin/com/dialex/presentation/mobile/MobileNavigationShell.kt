package com.dialex.presentation.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.ConnectionProfile
import com.dialex.domain.model.CouncilPreset
import com.dialex.domain.model.ProfileType
import com.dialex.domain.repository.ApiKeyRepository
import com.dialex.domain.repository.ProfileRepository
import com.dialex.model.Discussion
import com.dialex.theme.LocalCcColors
import com.dialex.theme.ThemeMode

/**
 * Thumb-friendly Mobile Navigation Shell for compact smartphone viewports.
 * Manages the 5 primary tabs, persistent engine status chip, and bottom navigation bar.
 */
@Composable
fun MobileNavigationShell(
    discussions: List<Discussion>,
    activeProfile: ConnectionProfile?,
    profileRepository: ProfileRepository,
    apiKeyRepository: ApiKeyRepository,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onSelectDiscussion: (String) -> Unit,
    onLaunchPreset: (CouncilPreset) -> Unit,
    onNewDilemma: () -> Unit,
    onExportMarkdown: (markdown: String, fileName: String) -> Unit,
    onSwitchToLocal: () -> Unit,
    onSwitchToRemote: () -> Unit,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    var currentTab by remember { mutableStateOf(MobileTab.COUNCIL) }
    val isLocal = (activeProfile?.type ?: ProfileType.LOCAL) == ProfileType.LOCAL

    Box(modifier = modifier.fillMaxSize().background(cc.bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top App Bar with Engine Status Chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(cc.panel)
                    .border(width = 1.dp, color = cc.border)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(cc.accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("D", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Dialex",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cc.textPrimary
                    )
                }

                // Interactive Engine Status Pill (Tapping flips to Vault)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isLocal) Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFF38BDF8).copy(alpha = 0.15f))
                        .border(
                            width = 1.dp,
                            color = if (isLocal) Color(0xFF10B981).copy(alpha = 0.4f) else Color(0xFF38BDF8).copy(alpha = 0.4f),
                            shape = RoundedCornerShape(999.dp)
                        )
                        .clickable { currentTab = MobileTab.VAULT }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isLocal) Icons.Default.PhoneAndroid else Icons.Default.Cloud,
                            contentDescription = null,
                            tint = if (isLocal) Color(0xFF10B981) else Color(0xFF38BDF8),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (isLocal) "Local Engine" else "Remote Server",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isLocal) Color(0xFF10B981) else Color(0xFF38BDF8)
                        )
                    }
                }
            }

            // Tab Content
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    MobileTab.COUNCIL -> {
                        CouncilHubTab(
                            discussions = discussions,
                            onSelectDiscussion = onSelectDiscussion,
                            onNewDilemma = onNewDilemma
                        )
                    }
                    MobileTab.PRESETS -> {
                        QuickStartTab(
                            onSelectPreset = onLaunchPreset
                        )
                    }
                    MobileTab.LAUNCH -> {
                        LaunchedEffect(Unit) {
                            onNewDilemma()
                            currentTab = MobileTab.COUNCIL
                        }
                    }
                    MobileTab.MEMOS -> {
                        MobileMemosTab(
                            discussions = discussions,
                            onOpenDiscussion = onSelectDiscussion,
                            onExportMarkdown = onExportMarkdown
                        )
                    }
                    MobileTab.VAULT -> {
                        MobileVaultTab(
                            activeProfile = activeProfile,
                            profileRepository = profileRepository,
                            apiKeyRepository = apiKeyRepository,
                            onSwitchToLocal = onSwitchToLocal,
                            onSwitchToRemote = onSwitchToRemote,
                            onScanQr = onScanQr,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange
                        )
                    }
                }
            }
        }

        // Thumb-Friendly Bottom Navigation Bar anchored at bottom
        MobileBottomBar(
            currentTab = currentTab,
            onSelectTab = { selected ->
                if (selected == MobileTab.LAUNCH) {
                    onNewDilemma()
                } else {
                    currentTab = selected
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
