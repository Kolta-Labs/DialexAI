package com.dialex.presentation.mobile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom navigation destinations for the Dialex Mobile App.
 */
enum class MobileTab(
    val title: String,
    val icon: ImageVector,
    val badgeLabel: String? = null
) {
    COUNCIL("Council", Icons.Default.Groups),
    PRESETS("Presets", Icons.Default.Bolt),
    LAUNCH("New", Icons.Default.PlayCircleOutline),
    MEMOS("Memos", Icons.Default.Description),
    VAULT("Vault", Icons.Default.Key);
}
