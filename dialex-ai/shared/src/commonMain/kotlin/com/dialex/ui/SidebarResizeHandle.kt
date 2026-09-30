package com.dialex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dialex.theme.LocalCcColors

/**
 * Resizable split-pane handle with visual indication on hover/drag.
 */
@Composable
fun SidebarResizeHandle(
    onResizeDelta: (Dp) -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(6.dp)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val deltaDp = with(density) { dragAmount.toDp() }
                        onResizeDelta(deltaDp)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Vertical divider line: 1dp default, expands to 2dp and tints with accent color when active
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(if (isHovered || isDragging) 2.dp else 1.dp)
                .background(
                    if (isHovered || isDragging) cc.textMuted
                    else cc.border.copy(alpha = 0.5f)
                )
        )
    }
}
