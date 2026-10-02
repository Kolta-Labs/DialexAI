package com.artix.desktop.ui.persona

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artix.desktop.model.PersonaDnaUI
import com.artix.desktop.ui.components.PersonaRadarChart
import com.artix.desktop.ui.theme.*

@Composable
fun PersonaStudioView() {
    val personas = remember {
        listOf(
            PersonaDnaUI(
                id = "adversarial_code_reviewer",
                name = "Adversarial Code Reviewer",
                role = "Diff Scrutinizer & Test Enforcer",
                cognitivePriors = mapOf(
                    "Skepticism" to 0.95f,
                    "Strictness" to 0.90f,
                    "Paranoia" to 0.85f,
                    "Brevity" to 0.70f,
                    "Velocity" to 0.30f
                ),
                tabooRules = listOf(
                    "Do not allow raw sqlite imports in UI layer",
                    "Do not approve diffs with failing tests or unhandled exceptions",
                    "Do not accept empty commit diffs"
                ),
                heuristics = listOf(
                    "Demand unit test coverage for every newly introduced branch",
                    "Verify timeout boundaries on all network I/O calls"
                )
            ),
            PersonaDnaUI(
                id = "senior_software_architect",
                name = "Senior Software Architect",
                role = "Systems & Boundary Architect",
                cognitivePriors = mapOf(
                    "Modularity" to 0.95f,
                    "Isolation" to 0.90f,
                    "Strictness" to 0.80f,
                    "Brevity" to 0.65f,
                    "Velocity" to 0.50f
                ),
                tabooRules = listOf(
                    "Do not violate package dependency layer hierarchy",
                    "Do not introduce circular package dependencies"
                ),
                heuristics = listOf(
                    "Encapsulate domain models behind clean repository interfaces"
                )
            ),
            PersonaDnaUI(
                id = "android_engineer",
                name = "Android & Compose Engineer",
                role = "Lifecycle-Safe Mobile Implementer",
                cognitivePriors = mapOf(
                    "Compose" to 0.95f,
                    "Lifecycle" to 0.90f,
                    "Strictness" to 0.75f,
                    "Brevity" to 0.60f,
                    "Velocity" to 0.80f
                ),
                tabooRules = listOf(
                    "Do not block main thread with I/O calls",
                    "Do not keep strong references to Activity contexts"
                ),
                heuristics = listOf(
                    "Use ViewModel StateFlow for all unidirectional data flows"
                )
            )
        )
    }

    var selectedPersona by remember { mutableStateOf(personas.first()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        // Header
        Column {
            Text("PERSONA STUDIO", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text("8-Layer Persona DNA, Cognitive Priors & Taboo Spaces", color = TextSecondary, fontSize = 12.sp)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Split Layout: Personas list on left, Radar Chart & DNA rules on right
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left: Persona List
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Text("SWE PERSONAS", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(personas) { p ->
                        val isSelected = selectedPersona.id == p.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) SurfaceCard else androidx.compose.ui.graphics.Color.Transparent)
                                .border(1.dp, if (isSelected) AccentCyan else BorderDark, RoundedCornerShape(6.dp))
                                .clickable { selectedPersona = p }
                                .padding(10.dp)
                        ) {
                            Text(p.name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(p.role, color = TextSecondary, fontSize = 10.sp)
                        }
                    }
                }
            }

            // Right: Radar Chart & DNA Details
            Column(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(selectedPersona.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("ID: ${selectedPersona.id}", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentCyan.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("8-LAYER DNA ACTIVE", color = AccentCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PersonaRadarChart(
                        metrics = selectedPersona.cognitivePriors,
                        modifier = Modifier.size(160.dp)
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("COGNITIVE PRIORS (RADAR)", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        selectedPersona.cognitivePriors.forEach { (trait, score) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(trait, color = TextPrimary, fontSize = 11.sp)
                                Text("${(score * 100).toInt()}%", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Taboo Constraints
                Text("TABOO SPACE CONSTRAINTS", color = AccentRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                selectedPersona.tabooRules.forEach { taboo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SurfaceCard)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("✕", color = AccentRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(taboo, color = TextPrimary, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
