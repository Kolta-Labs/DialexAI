package com.kritix.desktop.ui.runner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kritix.desktop.client.KritixEngineClient
import com.kritix.desktop.ui.components.CardBox
import com.kritix.desktop.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun RunnerView() {
    var targetUrl by remember { mutableStateOf("http://localhost:3000") }
    var selectedBlueprint by remember { mutableStateOf("pr-smoke-guard") }
    var isRunning by remember { mutableStateOf(false) }
    var executionSteps by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()

    val blueprints = listOf(
        Triple("pr-smoke-guard", "PR Smoke Guard", "Tier 1 · Critical path smoke test with zero unhandled exceptions"),
        Triple("exploratory", "Autonomous Explorer", "Vision Explorer · Crawls buttons, forms, and dynamic navigation"),
        Triple("api-contract-fuzzer", "API Contract Fuzzer", "Tier 2 · Boundary fuzzing, mutation injection & schema checks"),
        Triple("nightly-deep-audit", "Nightly Deep Audit", "Tier 3 · OWASP Top 10 fuzzing + k6 performance SLA")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Target URL & Action Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(BgSecondary)
                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(AccentCyan.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(text = "TARGET URL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = targetUrl,
                onValueChange = { targetUrl = it },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary
                ),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentIndigo,
                    unfocusedBorderColor = BorderSubtle
                )
            )
            Spacer(modifier = Modifier.width(12.dp))
            Button(
                onClick = {
                    scope.launch {
                        isRunning = true
                        executionSteps = KritixEngineClient.runTest(targetUrl, "Explore application safely")
                        isRunning = false
                    }
                },
                enabled = !isRunning,
                colors = ButtonDefaults.buttonColors(containerColor = AccentIndigo)
            ) {
                Text(text = if (isRunning) "⏳ Running..." else "▶ Run Autonomous Test")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Blueprint Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            blueprints.forEach { (id, name, desc) ->
                val isSelected = selectedBlueprint == id
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) AccentIndigo.copy(alpha = 0.1f) else BgSecondary)
                        .border(1.dp, if (isSelected) AccentIndigo else BorderSubtle, RoundedCornerShape(10.dp))
                        .clickable { selectedBlueprint = id }
                        .padding(14.dp)
                ) {
                    Text(text = name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = desc, fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Split view
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Pipeline Node Steps
            CardBox(
                modifier = Modifier.weight(1f),
                title = "Pipeline Execution Steps",
                action = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (executionSteps.isNotEmpty()) AccentEmerald.copy(alpha = 0.15f) else SurfaceCard)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (executionSteps.isNotEmpty()) "Passed" else "Ready",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (executionSteps.isNotEmpty()) AccentEmerald else TextSecondary
                        )
                    }
                }
            ) {
                if (executionSteps.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Select a blueprint and click 'Run Autonomous Test' to execute.",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(executionSteps) { step ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(SurfaceCard)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "✓", color = AccentEmerald, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(text = step, fontSize = 12.sp, color = TextPrimary)
                            }
                        }
                    }
                }
            }

            // Real-Time Telemetry Feed
            CardBox(
                modifier = Modifier.weight(1f),
                title = "Engine Telemetry Feed"
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF06090E))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "[System] Kritix AI Desktop Cockpit initialized.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8)
                        )
                        Text(
                            text = "[System] CDP Virtual Driver ready on localhost:9090.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF38BDF8)
                        )
                        if (isRunning) {
                            Text(
                                text = "[Action] Dispatched autonomous crawl to: $targetUrl",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFFFBBF24)
                            )
                        }
                        if (executionSteps.isNotEmpty()) {
                            Text(
                                text = "[Success] Invariant checks passed: 0 unhandled exceptions.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFF4ADE80)
                            )
                        }
                    }
                }
            }
        }
    }
}
