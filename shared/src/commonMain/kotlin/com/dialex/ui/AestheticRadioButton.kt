package com.dialex.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dialex.theme.LocalCcColors

/**
 * Ultra-sleek, aesthetic radio button with primary color gradient ring and
 * inner dot when active, matching the Antigravity styling system.
 */
@Composable
fun AestheticRadioButton(
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 18.dp,
) {
    val cc = LocalCcColors.current
    val dotSize by animateDpAsState(
        targetValue = if (selected) size * 0.52f else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "RadioDotSize"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = false, radius = size),
                        enabled = enabled,
                        onClick = onClick
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outer ring: primary gradient border when selected, subtle hairline border when unselected
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(
                    BorderStroke(
                        width = 1.5.dp,
                        brush = if (selected) cc.accentGradient else SolidColor(cc.border.copy(alpha = 0.8f))
                    ),
                    shape = CircleShape
                )
        )
        // Inner dot: primary gradient fill when selected
        if (selected && dotSize > 0.dp) {
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .clip(CircleShape)
                    .background(cc.accentGradient)
            )
        }
    }
}
