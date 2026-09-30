package com.dialex.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.dialex.theme.CcPalette
import com.dialex.theme.LocalCcColors

data class ChatTypographySettings(
    val fontSizeSp: Float = 14.5f,
    val lineSpacingMultiplier: Float = 1.55f
) {
    companion object {
        val Default = ChatTypographySettings()
        val MinFontSize = 12f
        val MaxFontSize = 22f
        val Step = 1.5f
    }
}

val LocalChatTypographySettings = compositionLocalOf { mutableStateOf(ChatTypographySettings.Default) }

/**
 * Reading controls trigger button and popover menu for adjusting font size and line height.
 */
@Composable
fun ReadingTypographyButton(
    settings: ChatTypographySettings,
    onSettingsChange: (ChatTypographySettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    var isOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { isOpen = !isOpen },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Outlined.FormatSize,
                contentDescription = "Adjust Reading Size & Spacing",
                tint = if (isOpen) cc.textPrimary else cc.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        if (isOpen) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = androidx.compose.ui.unit.IntOffset(0, 48),
                onDismissRequest = { isOpen = false },
                properties = PopupProperties(focusable = true)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cc.panel,
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .width(260.dp)
                        .padding(top = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Title row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Typography & Zoom",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                                color = cc.textPrimary
                            )
                            IconButton(
                                onClick = { onSettingsChange(ChatTypographySettings.Default) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.RestartAlt,
                                    contentDescription = "Reset to Default",
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = cc.border.copy(alpha = 0.3f), thickness = 0.75.dp)

                        // 1. Font Size Controls
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Font Size",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = cc.textMuted
                                )
                                Text(
                                    "${settings.fontSizeSp.toInt()} sp",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                                    color = cc.textPrimary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                QuickSizeButton(
                                    label = "A−",
                                    enabled = settings.fontSizeSp > ChatTypographySettings.MinFontSize,
                                    onClick = {
                                        val newSize = (settings.fontSizeSp - ChatTypographySettings.Step).coerceAtLeast(ChatTypographySettings.MinFontSize)
                                        onSettingsChange(settings.copy(fontSizeSp = newSize))
                                    },
                                    modifier = Modifier.weight(1f),
                                    cc = cc
                                )
                                QuickSizeButton(
                                    label = "100%",
                                    enabled = settings.fontSizeSp != ChatTypographySettings.Default.fontSizeSp,
                                    onClick = {
                                        onSettingsChange(settings.copy(fontSizeSp = ChatTypographySettings.Default.fontSizeSp))
                                    },
                                    modifier = Modifier.weight(1.2f),
                                    cc = cc
                                )
                                QuickSizeButton(
                                    label = "A+",
                                    enabled = settings.fontSizeSp < ChatTypographySettings.MaxFontSize,
                                    onClick = {
                                        val newSize = (settings.fontSizeSp + ChatTypographySettings.Step).coerceAtMost(ChatTypographySettings.MaxFontSize)
                                        onSettingsChange(settings.copy(fontSizeSp = newSize))
                                    },
                                    modifier = Modifier.weight(1f),
                                    cc = cc
                                )
                            }
                        }

                        // 2. Line Spacing Controls
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Line Spacing",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SpacingPresetButton(
                                    label = "Compact",
                                    selected = settings.lineSpacingMultiplier <= 1.38f,
                                    onClick = { onSettingsChange(settings.copy(lineSpacingMultiplier = 1.35f)) },
                                    modifier = Modifier.weight(1f),
                                    cc = cc
                                )
                                SpacingPresetButton(
                                    label = "Normal",
                                    selected = settings.lineSpacingMultiplier > 1.38f && settings.lineSpacingMultiplier < 1.72f,
                                    onClick = { onSettingsChange(settings.copy(lineSpacingMultiplier = 1.55f)) },
                                    modifier = Modifier.weight(1f),
                                    cc = cc
                                )
                                SpacingPresetButton(
                                    label = "Relaxed",
                                    selected = settings.lineSpacingMultiplier >= 1.72f,
                                    onClick = { onSettingsChange(settings.copy(lineSpacingMultiplier = 1.85f)) },
                                    modifier = Modifier.weight(1f),
                                    cc = cc
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSizeButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cc: CcPalette
) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (enabled) cc.panelAlt else cc.panelAlt.copy(alpha = 0.5f))
            .border(0.75.dp, cc.border.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
            color = if (enabled) cc.textPrimary else cc.textMuted.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SpacingPresetButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cc: CcPalette
) {
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) cc.accent.copy(alpha = 0.12f) else cc.panelAlt)
            .border(
                0.75.dp,
                if (selected) cc.accent else cc.border.copy(alpha = 0.4f),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                fontSize = 11.5.sp
            ),
            color = if (selected) cc.accent else cc.textPrimary
        )
    }
}
