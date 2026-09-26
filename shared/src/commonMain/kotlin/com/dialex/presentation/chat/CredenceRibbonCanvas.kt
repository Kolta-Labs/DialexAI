package com.dialex.presentation.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.CredenceHypothesis
import com.dialex.domain.model.CredenceLedger
import com.dialex.domain.model.PersonaCredencePoint
import com.dialex.domain.model.RoundCredenceSnapshot
import com.dialex.theme.LocalCcColors

private val defaultColors = listOf(
    Color(0xFF10B981), // Emerald
    Color(0xFF6366F1), // Indigo
    Color(0xFFF43F5E), // Coral
    Color(0xFFF59E0B)  // Amber
)

private fun parseColorHex(hex: String, fallback: Color): Color {
    return try {
        val clean = hex.removePrefix("#")
        val parsed = clean.toLong(16)
        if (clean.length == 6) {
            Color(parsed or 0xFF000000)
        } else {
            Color(parsed)
        }
    } catch (_: Exception) {
        fallback
    }
}

@Composable
fun CredenceRibbonCanvas(
    ledger: CredenceLedger,
    selectedRound: Int?,
    onSelectRound: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cc = LocalCcColors.current
    val hypotheses = ledger.hypotheses
    val snapshots = ledger.snapshots

    if (hypotheses.isEmpty() || snapshots.isEmpty()) {
		Box(
            modifier = modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(cc.panelAlt.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("No Bayesian credence data available for this discussion.", color = cc.textMuted, fontSize = 13.sp)
        }
        return
    }

    val currentRoundIdx = selectedRound ?: snapshots.last().roundIndex
    val activeSnapshot = snapshots.find { it.roundIndex == currentRoundIdx } ?: snapshots.last()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. Hypotheses Legend & Latest Credence
        HypothesesLegendRow(
            hypotheses = hypotheses,
            activeSnapshot = activeSnapshot
        )

        // 2. The Fluid Bayesian Area Canvas
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(12.dp)),
            color = cc.panelAlt.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f))
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val widthPx = constraints.maxWidth.toFloat()
                val heightPx = constraints.maxHeight.toFloat()
                val numSnapshots = snapshots.size

                val padX = 40f
                val padY = 24f
                val chartW = (widthPx - padX * 2).coerceAtLeast(10f)
                val chartH = (heightPx - padY * 2).coerceAtLeast(10f)
                val stepX = if (numSnapshots > 1) chartW / (numSnapshots - 1) else chartW

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(snapshots) {
                            detectTapGestures { tapOffset ->
                                val relativeX = tapOffset.x - padX
                                val tappedIdx = (relativeX / stepX + 0.5f).toInt().coerceIn(0, numSnapshots - 1)
                                onSelectRound(snapshots[tappedIdx].roundIndex)
                            }
                        }
                ) {
                    if (numSnapshots < 1) return@Canvas

                    // For each hypothesis k, we compute its bottom and top Y across all snapshots
                    var prevCumY = FloatArray(numSnapshots) { chartH + padY }

                    hypotheses.forEachIndexed { k, hyp ->
                        val hypColor = parseColorHex(hyp.colorHex, defaultColors.getOrElse(k) { Color.Gray })
                        val path = Path()

                        val currCumY = FloatArray(numSnapshots)
                        for (sIdx in 0 until numSnapshots) {
                            val snap = snapshots[sIdx]
                            val p = snap.aggregatedCredence[hyp.id] ?: 0.0
                            val hHeight = (p * chartH).toFloat()
                            currCumY[sIdx] = (prevCumY[sIdx] - hHeight).coerceAtLeast(padY)
                        }

                        // Top curve from left to right
                        for (sIdx in 0 until numSnapshots) {
                            val x = padX + sIdx * stepX
                            val y = currCumY[sIdx]
                            if (sIdx == 0) {
                                path.moveTo(x, y)
                            } else {
                                val prevX = padX + (sIdx - 1) * stepX
                                val prevY = currCumY[sIdx - 1]
                                val cx1 = prevX + (x - prevX) / 2f
                                val cx2 = prevX + (x - prevX) / 2f
                                path.cubicTo(cx1, prevY, cx2, y, x, y)
                            }
                        }

                        // Bottom curve from right to left
                        for (sIdx in numSnapshots - 1 downTo 0) {
                            val x = padX + sIdx * stepX
                            val y = prevCumY[sIdx]
                            if (sIdx == numSnapshots - 1) {
                                path.lineTo(x, y)
                            } else {
                                val nextX = padX + (sIdx + 1) * stepX
                                val nextY = prevCumY[sIdx + 1]
                                val cx1 = nextX - (nextX - x) / 2f
                                val cx2 = nextX - (nextX - x) / 2f
                                path.cubicTo(cx1, nextY, cx2, y, x, y)
                            }
                        }

                        path.close()
                        drawPath(path, hypColor.copy(alpha = 0.72f), style = Fill)

                        // Update baseline for next hypothesis layer
                        prevCumY = currCumY
                    }

                    // Draw vertical round lines and highlight active selection
                    snapshots.forEachIndexed { sIdx, snap ->
                        val x = padX + sIdx * stepX
                        val isSelected = snap.roundIndex == currentRoundIdx

                        // Guide line
                        drawLine(
                            color = if (isSelected) Color.White else cc.border.copy(alpha = 0.4f),
                            start = Offset(x, padY),
                            end = Offset(x, heightPx - padY),
                            strokeWidth = if (isSelected) 2.5f else 1f
                        )

                        // Marker dot
                        drawCircle(
                            color = if (isSelected) cc.accent else cc.textMuted.copy(alpha = 0.6f),
                            radius = if (isSelected) 5f else 3f,
                            center = Offset(x, heightPx - padY)
                        )
                    }
                }
            }
        }

        // 3. Round Selector Chips
        RoundSelectorStrip(
            snapshots = snapshots,
            selectedRound = currentRoundIdx,
            onSelectRound = onSelectRound
        )

        // 4. Active Round Epistemic Inspector
        RoundInspectorCard(
            snapshot = activeSnapshot,
            hypotheses = hypotheses
        )
    }
}

@Composable
private fun HypothesesLegendRow(
    hypotheses: List<CredenceHypothesis>,
    activeSnapshot: RoundCredenceSnapshot
) {
    val cc = LocalCcColors.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(hypotheses) { hyp ->
            val color = parseColorHex(hyp.colorHex, Color(0xFF10B981))
            val prob = activeSnapshot.probabilityFor(hyp.id)
            val pct = (prob * 100).toInt()

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = cc.panel,
                border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Text(
                        text = "H${hyp.index}: ${hyp.label}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cc.textPrimary
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(color.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "$pct%",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundSelectorStrip(
    snapshots: List<RoundCredenceSnapshot>,
    selectedRound: Int,
    onSelectRound: (Int) -> Unit
) {
    val cc = LocalCcColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Rounds:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = cc.textMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(snapshots) { snap ->
                val isSel = snap.roundIndex == selectedRound
                val label = if (snap.roundIndex == 0) "R0 (Prior)" else "R${snap.roundIndex}"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) cc.accent else cc.panel)
                        .border(1.dp, if (isSel) cc.accent else cc.border, RoundedCornerShape(6.dp))
                        .clickable { onSelectRound(snap.roundIndex) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSel) Color.Black else cc.textPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundInspectorCard(
    snapshot: RoundCredenceSnapshot,
    hypotheses: List<CredenceHypothesis>
) {
    val cc = LocalCcColors.current
    val entropy = snapshot.entropy
    val entropyColor = when {
        entropy <= 0.35 -> Color(0xFF10B981) // Green (Low uncertainty)
        entropy <= 0.90 -> Color(0xFFF59E0B) // Amber (Moderate divergence)
        else -> Color(0xFFF43F5E)            // Red (High uncertainty / Stalemate)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = cc.panel,
        border = BorderStroke(1.dp, cc.border)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header: Round index, Shannon Entropy, and Dominant status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Psychology, contentDescription = null, tint = cc.accent, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (snapshot.roundIndex == 0) "Round 0 (Initial Bayesian Prior)" else "Round ${snapshot.roundIndex} Credence State",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = cc.textPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(entropyColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Entropy: ${snapshot.entropy} bits",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = entropyColor
                    )
                }
            }

            // Tipping Points Alert (if any)
            snapshot.tippingPoints.forEach { tp ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                        Column {
                            Text(
                                text = "Epistemic Tipping Point (Shift: ${(tp.shiftDelta * 100).toInt()}%, Λ = ${tp.likelihoodRatio}x)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                            Text(
                                text = tp.evidenceSnippet,
                                fontSize = 11.sp,
                                color = cc.textPrimary,
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            // Persona Credence Breakdown
            if (snapshot.personaCredences.isNotEmpty()) {
                Text(
                    text = "Persona Belief Breakdown",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = cc.textMuted
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.personaCredences.forEach { pc ->
                        PersonaCredenceRow(
                            personaCredence = pc,
                            hypotheses = hypotheses
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaCredenceRow(
    personaCredence: PersonaCredencePoint,
    hypotheses: List<CredenceHypothesis>
) {
    val cc = LocalCcColors.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cc.panelAlt.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = personaCredence.personaName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = cc.textPrimary
                )
                Text(
                    text = "Certainty: ${(personaCredence.certaintyScore * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = cc.textMuted
                )
            }

            // Horizontal stacked probability bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                hypotheses.forEachIndexed { i, hyp ->
                    val p = personaCredence.hypothesisCredence[hyp.id] ?: 0.0
                    if (p > 0.01) {
                        val color = parseColorHex(hyp.colorHex, defaultColors.getOrElse(i) { Color.Gray })
                        Box(
                            modifier = Modifier
                                .weight(p.toFloat())
                                .fillMaxHeight()
                                .background(color)
                        )
                    }
                }
            }

            if (personaCredence.coreRationale.isNotBlank()) {
                Text(
                    text = personaCredence.coreRationale,
                    fontSize = 11.sp,
                    color = cc.textMuted,
                    lineHeight = 16.sp
                )
            }
        }
    }
}
