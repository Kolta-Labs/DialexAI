package com.dialex.presentation.mobile.arena

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.Agent
import com.dialex.theme.LocalCcColors
import com.dialex.theme.accentColor
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circular visual deliberation arena stage.
 * Features:
 * - Radial seating of agents around the circular table.
 * - Animated glowing pulse for the actively speaking agent.
 * - Central animated consensus gauge ring with percentage display.
 * - Round progress indicator.
 */
@Composable
fun RoundtableArenaStage(
    agents: List<Agent>,
    activeSpeakerIndex: Int,
    isSpeaking: Boolean,
    consensusScore: Float,
    currentRound: Int,
    maxRounds: Int,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val animatedConsensus by animateFloatAsState(
        targetValue = consensusScore.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "consensusScore"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(CircleShape)
            .background(cc.panel)
            .border(1.dp, cc.border, CircleShape)
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        // Central Consensus Gauge Canvas
        Canvas(modifier = Modifier.size(110.dp)) {
            val strokeWidth = 6.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            val centerOffset = Offset(size.width / 2, size.height / 2)

            // Background Track
            drawCircle(
                color = cc.panelAlt,
                radius = radius,
                center = centerOffset,
                style = Stroke(width = strokeWidth)
            )

            // Active Progress Arc
            val sweepAngle = animatedConsensus * 360f
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(cc.accent, Color(0xFF10B981), cc.accent)
                ),
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Center Content: Consensus % & Round Indicator
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "${(animatedConsensus * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = if (animatedConsensus > 0.85f) Color(0xFF10B981) else cc.accent
            )
            Text(
                text = if (animatedConsensus > 0.85f) "AGREED" else "Consensus",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = cc.textMuted
            )
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(cc.panelAlt)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "R$currentRound / $maxRounds",
                    fontSize = 9.sp,
                    color = cc.textPrimary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Radially Positioned Agent Seats
        val seatCount = agents.size.coerceAtLeast(1)
        agents.forEachIndexed { index, agent ->
            val angle = (2 * Math.PI * index / seatCount) - (Math.PI / 2)
            val distance = 82.0 // Radius in dp from center

            val xOffset = (distance * cos(angle)).dp
            val yOffset = (distance * sin(angle)).dp

            val isCurrentSpeaker = isSpeaking && index == activeSpeakerIndex
            val agentColor = com.dialex.theme.memberColorFor(index, cc)

            Box(
                modifier = Modifier
                    .offset(x = xOffset, y = yOffset)
                    .scale(if (isCurrentSpeaker) pulseScale else 1.0f)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(if (isCurrentSpeaker) agentColor.copy(alpha = 0.25f) else cc.panelAlt)
                    .border(
                        width = if (isCurrentSpeaker) 2.dp else 1.dp,
                        color = if (isCurrentSpeaker) agentColor else cc.border,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = agent.displayName,
                        tint = agentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = agent.displayName.take(5),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = cc.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }
}
