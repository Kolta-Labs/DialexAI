package com.kritix.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kritix.desktop.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun TabHeader(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    val tabs = listOf("Council Chamber", "Engineering Lab", "Steering Studio", "Persona Studio")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(SurfaceDark)
            .border(1.dp, BorderDark)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Brand
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(AccentCyan),
                contentAlignment = Alignment.Center
            ) {
                Text("K", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text("KRITIX AI", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Cockpit", color = AccentCyan, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }

        // Navigation Tabs
        tabs.forEachIndexed { index, title ->
            val isSelected = selectedTab == index
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) SurfaceCard else Color.Transparent)
                    .clickable { onTabSelected(index) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = title,
                    color = if (isSelected) AccentCyan else TextSecondary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Live engine status pill
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(BorderDark)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AccentGreen)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Engine Grounded", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun PersonaRadarChart(
    metrics: Map<String, Float>,
    modifier: Modifier = Modifier,
    fillColor: Color = AccentCyan.copy(alpha = 0.25f),
    strokeColor: Color = AccentCyan
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val center = Offset(size.width / 2, size.height / 2)
            val radius = (size.width.coerceAtMost(size.height) / 2) * 0.8f
            val entries = metrics.toList()
            val count = entries.size
            if (count < 3) return@Canvas

            val angleStep = (2 * Math.PI / count).toFloat()

            // Draw concentric background web
            for (level in 1..4) {
                val r = radius * (level / 4f)
                val webPath = Path()
                for (i in 0 until count) {
                    val angle = (i * angleStep) - (Math.PI / 2).toFloat()
                    val x = center.x + r * cos(angle)
                    val y = center.y + r * sin(angle)
                    if (i == 0) webPath.moveTo(x, y) else webPath.lineTo(x, y)
                }
                webPath.close()
                drawPath(webPath, color = BorderDark, style = Stroke(width = 1.dp.toPx()))
            }

            // Draw spoke lines
            for (i in 0 until count) {
                val angle = (i * angleStep) - (Math.PI / 2).toFloat()
                val x = center.x + radius * cos(angle)
                val y = center.y + radius * sin(angle)
                drawLine(
                    color = BorderDark,
                    start = center,
                    end = Offset(x, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Draw metric polygon
            val dataPath = Path()
            entries.forEachIndexed { i, entry ->
                val angle = (i * angleStep) - (Math.PI / 2).toFloat()
                val r = radius * entry.second.coerceIn(0f, 1f)
                val x = center.x + r * cos(angle)
                val y = center.y + r * sin(angle)
                if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
            }
            dataPath.close()

            drawPath(dataPath, color = fillColor)
            drawPath(dataPath, color = strokeColor, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
