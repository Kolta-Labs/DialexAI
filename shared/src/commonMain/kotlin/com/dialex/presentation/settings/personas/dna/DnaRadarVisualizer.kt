package com.dialex.presentation.settings.personas.dna

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.dialex.domain.model.EpistemicBias
import com.dialex.theme.LocalCcColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Radar spider chart visualizer rendering the 5 Epistemic Bias cognitive coordinates:
 * 1. Rigor Threshold (Formal validation vs Heuristic)
 * 2. Practice (Theoretical 0.0 vs Empirical Practice 1.0)
 * 3. Provenance (Novelty 0.0 vs Provenance/Tradition 1.0)
 * 4. Velocity vs Safety (Velocity 0.0 vs Defensive Safety 1.0)
 * 5. Tenacity Score (Adversarial resistance)
 */
@Composable
fun DnaRadarVisualizer(
    bias: EpistemicBias,
    tenacityScore: Double = 0.8,
    modifier: Modifier = Modifier,
    onRigorChanged: ((Double) -> Unit)? = null,
    onPracticeChanged: ((Double) -> Unit)? = null,
    onProvenanceChanged: ((Double) -> Unit)? = null,
    onSafetyChanged: ((Double) -> Unit)? = null,
    onTenacityChanged: ((Double) -> Unit)? = null
) {
    val cc = LocalCcColors.current

    Column(
        modifier = modifier
            .background(cc.panelAlt, RoundedCornerShape(12.dp))
            .border(1.dp, cc.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "EPISTEMIC RADAR MATRIX",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = cc.accent,
                letterSpacing = 1.sp
            )
            Text(
                "5-D Cognitive Profile",
                style = MaterialTheme.typography.labelSmall,
                color = cc.textMuted
            )
        }

        Spacer(Modifier.height(16.dp))

        // Canvas Radar Chart
        Box(
            modifier = Modifier.size(240.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(220.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.width * 0.42f
                val axesCount = 5

                // Axis angles (Rigor=top, Practice=top-right, Provenance=bot-right, Safety=bot-left, Tenacity=top-left)
                val angles = List(axesCount) { i ->
                    (-PI / 2.0 + i * (2.0 * PI / axesCount)).toFloat()
                }

                // Draw concentric reference grid pentagons (0.25, 0.50, 0.75, 1.00)
                val gridLevels = listOf(0.25f, 0.5f, 0.75f, 1.0f)
                gridLevels.forEach { level ->
                    val gridPath = Path()
                    angles.forEachIndexed { i, angle ->
                        val r = maxRadius * level
                        val x = center.x + r * cos(angle)
                        val y = center.y + r * sin(angle)
                        if (i == 0) gridPath.moveTo(x, y) else gridPath.lineTo(x, y)
                    }
                    gridPath.close()
                    drawPath(
                        path = gridPath,
                        color = cc.border.copy(alpha = if (level == 1.0f) 0.5f else 0.2f),
                        style = Stroke(width = if (level == 1.0f) 1.5f else 1.0f)
                    )
                }

                // Draw radial spoke lines
                angles.forEach { angle ->
                    val endX = center.x + maxRadius * cos(angle)
                    val endY = center.y + maxRadius * sin(angle)
                    drawLine(
                        color = cc.border.copy(alpha = 0.35f),
                        start = center,
                        end = Offset(endX, endY),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // Compute values vector: [Rigor, Practice, Provenance, Safety, Tenacity]
                val values = listOf(
                    bias.rigorThreshold.toFloat().coerceIn(0.05f, 1f),
                    bias.theoryVsPractice.toFloat().coerceIn(0.05f, 1f),
                    bias.noveltyVsProvenance.toFloat().coerceIn(0.05f, 1f),
                    bias.safetyVsVelocity.toFloat().coerceIn(0.05f, 1f),
                    tenacityScore.toFloat().coerceIn(0.05f, 1f)
                )

                // Draw data polygon
                val dataPath = Path()
                val dataPoints = mutableListOf<Offset>()

                angles.forEachIndexed { i, angle ->
                    val r = maxRadius * values[i]
                    val x = center.x + r * cos(angle)
                    val y = center.y + r * sin(angle)
                    val pt = Offset(x, y)
                    dataPoints.add(pt)
                    if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
                }
                dataPath.close()

                // Fill polygon with translucent accent
                drawPath(
                    path = dataPath,
                    color = cc.accent.copy(alpha = 0.30f)
                )

                // Outline polygon
                drawPath(
                    path = dataPath,
                    color = cc.accent,
                    style = Stroke(width = 2.5f.dp.toPx())
                )

                // Draw glowing vertices
                dataPoints.forEach { pt ->
                    drawCircle(
                        color = cc.bg,
                        radius = 4.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = cc.accent,
                        radius = 3.dp.toPx(),
                        center = pt
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Coordinate Labels & Sliders
        EpistemicDimensionSlider(
            name = "Rigor Threshold (Formal vs Loose)",
            value = bias.rigorThreshold,
            onValueChange = onRigorChanged,
            accentColor = cc.accent
        )
        EpistemicDimensionSlider(
            name = "Practice (Theoretical vs Empirical)",
            value = bias.theoryVsPractice,
            onValueChange = onPracticeChanged,
            accentColor = cc.accent
        )
        EpistemicDimensionSlider(
            name = "Provenance (Novelty vs Established)",
            value = bias.noveltyVsProvenance,
            onValueChange = onProvenanceChanged,
            accentColor = cc.accent
        )
        EpistemicDimensionSlider(
            name = "Safety vs Velocity (Defensive vs Rapid)",
            value = bias.safetyVsVelocity,
            onValueChange = onSafetyChanged,
            accentColor = cc.accent
        )
        EpistemicDimensionSlider(
            name = "Tenacity (Adversarial Persistence)",
            value = tenacityScore,
            onValueChange = onTenacityChanged,
            accentColor = cc.accent
        )
    }
}

@Composable
private fun EpistemicDimensionSlider(
    name: String,
    value: Double,
    onValueChange: ((Double) -> Unit)?,
    accentColor: Color
) {
    val cc = LocalCcColors.current
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = cc.textPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = cc.accent,
                fontWeight = FontWeight.Bold
            )
        }
        if (onValueChange != null) {
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.toDouble()) },
                valueRange = 0.0f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = cc.border.copy(alpha = 0.4f)
                ),
                modifier = Modifier.height(28.dp)
            )
        }
    }
}
