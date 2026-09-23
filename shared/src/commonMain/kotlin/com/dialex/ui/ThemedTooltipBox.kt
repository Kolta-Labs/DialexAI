@file:Suppress("DEPRECATION")

package com.dialex.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.theme.LocalCcColors

/**
 * Reusable tooltip wrapper for icon buttons and interactive controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemedTooltipBox(
    tooltip: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (tooltip.isBlank()) {
        content()
        return
    }
    val cc = LocalCcColors.current
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = cc.panelAlt,
                border = androidx.compose.foundation.BorderStroke(0.5.dp, cc.border.copy(alpha = 0.5f)),
                shadowElevation = 3.dp
            ) {
                Text(
                    text = tooltip,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = cc.textPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        },
        state = rememberTooltipState(),
        modifier = modifier,
        content = content
    )
}
