package com.dialex.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dialex.theme.LocalCcColors

/**
 * Ultra-sleek, aesthetic toggle switch with primary gradient track when active
 * and subtle neutral track when inactive, matching macOS/Antigravity styling.
 */
@Composable
fun AestheticSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val cc = LocalCcColors.current
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 18.dp else 2.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "SwitchThumbOffset"
    )
    val inactiveTrackColor = if (cc.isDark) Color(0xFF2E2E38) else Color(0xFFE2E4E9)
    val trackBrush = if (checked) {
        cc.accentGradient
    } else {
        Brush.horizontalGradient(listOf(inactiveTrackColor, inactiveTrackColor))
    }

    Box(
        modifier = modifier
            .width(36.dp)
            .height(20.dp)
            .clip(CircleShape)
            .background(trackBrush)
            .border(
                BorderStroke(0.5.dp, if (checked) Color.Transparent else cc.border.copy(alpha = 0.6f)),
                CircleShape
            )
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(16.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}
