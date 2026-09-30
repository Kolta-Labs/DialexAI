package com.dialex.presentation.arena

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.JudgeEvaluation
import com.dialex.domain.model.MetricDimension
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun ArenaRadarChart(
    evaluations: List<JudgeEvaluation>,
    modifier: Modifier = Modifier,
    councilColor: Color = Color(0xFF8B5CF6), // Royal Purple
    soloColor: Color = Color(0xFFF59E0B)     // Warm Amber
) {
    if (evaluations.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No evaluation data", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    // Compute averaged scores for Solo and Council
    var cFact = 0.0; var cBlind = 0.0; var cTrade = 0.0; var cAction = 0.0
    var sFact = 0.0; var sBlind = 0.0; var sTrade = 0.0; var sAction = 0.0

    for (ev in evaluations) {
        for (ms in ev.councilScores) {
            when (ms.dimension) {
                MetricDimension.FACTUALITY -> cFact += ms.score
                MetricDimension.BLIND_SPOTS -> cBlind += ms.score
                MetricDimension.TRADE_OFFS -> cTrade += ms.score
                MetricDimension.ACTIONABILITY -> cAction += ms.score
            }
        }
        for (ms in ev.soloScores) {
            when (ms.dimension) {
                MetricDimension.FACTUALITY -> sFact += ms.score
                MetricDimension.BLIND_SPOTS -> sBlind += ms.score
                MetricDimension.TRADE_OFFS -> sTrade += ms.score
                MetricDimension.ACTIONABILITY -> sAction += ms.score
            }
        }
    }

    val count = evaluations.size.toDouble()
    val councilScores = listOf(cFact / count, cBlind / count, cTrade / count, cAction / count)
    val soloScores = listOf(sFact / count, sBlind / count, sTrade / count, sAction / count)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Box(modifier = modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = (minOf(size.width, size.height) / 2f) * 0.75f

            // 4 Axes: Top (Factuality), Right (Blind Spots), Bottom (Trade-offs), Left (Actionability)
            // Angles in radians: -PI/2, 0, PI/2, PI
            val angles = listOf(-Math.PI / 2, 0.0, Math.PI / 2, Math.PI)

            // Draw concentric web rings (levels 2.5, 5.0, 7.5, 10.0)
            val levels = listOf(0.25f, 0.50f, 0.75f, 1.0f)
            for (level in levels) {
                val ringPath = Path()
                for (i in angles.indices) {
                    val angle = angles[i]
                    val r = radius * level
                    val x = center.x + (r * cos(angle)).toFloat()
                    val y = center.y + (r * sin(angle)).toFloat()
                    if (i == 0) ringPath.moveTo(x, y) else ringPath.lineTo(x, y)
                }
                ringPath.close()
                drawPath(ringPath, color = gridColor, style = Stroke(width = 1f))
            }

            // Draw radiating axis lines
            for (angle in angles) {
                val endX = center.x + (radius * cos(angle)).toFloat()
                val endY = center.y + (radius * sin(angle)).toFloat()
                drawLine(gridColor, center, Offset(endX, endY), strokeWidth = 1f)
            }

            // Function to build polygon path for scores
            fun buildPath(scores: List<Double>): Path {
                val path = Path()
                for (i in scores.indices) {
                    val angle = angles[i]
                    val normScore = (scores[i].coerceIn(0.0, 10.0) / 10.0).toFloat()
                    val r = radius * normScore
                    val x = center.x + (r * cos(angle)).toFloat()
                    val y = center.y + (r * sin(angle)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                return path
            }

            // Draw Solo Polygon
            val soloPath = buildPath(soloScores)
            drawPath(soloPath, color = soloColor.copy(alpha = 0.25f))
            drawPath(soloPath, color = soloColor, style = Stroke(width = 2.5f))

            // Draw Council Polygon
            val councilPath = buildPath(councilScores)
            drawPath(councilPath, color = councilColor.copy(alpha = 0.35f))
            drawPath(councilPath, color = councilColor, style = Stroke(width = 3f))

            // Draw point dots
            for (i in angles.indices) {
                val angle = angles[i]
                // Solo dot
                val sR = radius * (soloScores[i] / 10.0).toFloat()
                drawCircle(soloColor, radius = 4f, center = Offset(center.x + (sR * cos(angle)).toFloat(), center.y + (sR * sin(angle)).toFloat()))

                // Council dot
                val cR = radius * (councilScores[i] / 10.0).toFloat()
                drawCircle(councilColor, radius = 5f, center = Offset(center.x + (cR * cos(angle)).toFloat(), center.y + (cR * sin(angle)).toFloat()))
            }
        }
    }
}
