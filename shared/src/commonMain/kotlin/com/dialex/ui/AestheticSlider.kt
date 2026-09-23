package com.dialex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dialex.theme.LocalCcColors

/**
 * Ultra-sleek, aesthetic slider with primary color gradient on the active track
 * and thumb, matching the Antigravity styling system.
 */
@Composable
fun AestheticSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    thumbSize: Dp = 16.dp,
    trackHeight: Dp = 6.dp,
) {
    val cc = LocalCcColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val rangeSpan = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-6f)
    val fraction = ((value - valueRange.start) / rangeSpan).coerceIn(0f, 1f)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(thumbSize.coerceAtLeast(24.dp))
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val widthPx = size.width
                    if (widthPx > 0) {
                        val newFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                        val newValue = valueRange.start + newFraction * rangeSpan
                        onValueChange(newValue)
                    }
                }
            }
            .pointerInput(enabled, valueRange) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val widthPx = size.width
                    if (widthPx > 0) {
                        val newFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                        val newValue = valueRange.start + newFraction * rangeSpan
                        onValueChange(newValue)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val totalWidth = maxWidth
        val activeWidth = totalWidth * fraction

        // Inactive background track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(cc.panelAlt)
                .border(
                    BorderStroke(0.5.dp, cc.border.copy(alpha = 0.6f)),
                    RoundedCornerShape(trackHeight / 2)
                )
        )

        // Active track with primary color gradient
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .width(activeWidth)
                    .height(trackHeight)
                    .clip(RoundedCornerShape(trackHeight / 2))
                    .background(cc.accentGradient)
            )
        }

        // Thumb with primary gradient and high contrast border
        val thumbOffset = ((totalWidth - thumbSize) * fraction).coerceAtLeast(0.dp)
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .shadow(elevation = if (isHovered) 4.dp else 2.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(cc.accentGradient)
                .border(
                    BorderStroke(2.dp, Color.White),
                    CircleShape
                )
        )
    }
}
