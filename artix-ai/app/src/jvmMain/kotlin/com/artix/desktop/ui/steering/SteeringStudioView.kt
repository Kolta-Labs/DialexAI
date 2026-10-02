package com.artix.desktop.ui.steering

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
import com.artix.desktop.model.SteeringRuleUI
import com.artix.desktop.ui.theme.*

@Composable
fun SteeringStudioView() {
    var prCommentInput by remember { mutableStateOf("Never use GlobalScope.launch in viewmodels, always use viewModelScope!") }
    var codeSimulatorInput by remember { mutableStateOf("import android.database.sqlite.SQLiteDatabase\nclass MyViewModel : ViewModel() {\n    fun load() { Thread.sleep(500) }\n}") }

    var rules by remember {
        mutableStateOf(
            listOf(
                SteeringRuleUI("rule-1", "KMP Architectural Invariants", ".standards/steering/kmp/architecture.md", "standard", isEnabled = true, boundPersonas = listOf("android_engineer", "backend_engineer")),
                SteeringRuleUI("rule-2", "Taboo Space: No Direct SQLite in UI", ".standards/steering/kmp/taboo_storage.md", "standard", isEnabled = true, boundPersonas = listOf("android_engineer", "adversarial_code_reviewer")),
                SteeringRuleUI("rule-3", "Claude Engine Guidelines", "CLAUDE.md", "local", isEnabled = true, boundPersonas = listOf("product_owner_lead", "senior_software_architect")),
                SteeringRuleUI("rule-4", "Corporate Security Baselines", "https://standards.corp/security.md", "remote_http", isEnabled = true, boundPersonas = listOf("security_auditor", "adversarial_code_reviewer"))
            )
        )
    }

    var selectedRule by remember { mutableStateOf(rules[1]) }
    var isSyncing by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("STEERING STUDIO", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Dynamic Persona-to-Rule Binding Matrix, PR-to-Rule Synthesizer & Standards Sync", color = TextSecondary, fontSize = 12.sp)
            }

            Button(
                onClick = { isSyncing = true },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
            ) {
                Text(if (isSyncing) "Syncing Standards..." else "Sync External Standards", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // PR-to-Rule Synthesizer Bar (GUI Feature)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceCard)
                .border(1.dp, AccentPurple, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("PR-TO-RULE SYNTHESIZER (LEARN FROM HUMAN CODE REVIEWS)", color = AccentPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = prCommentInput,
                    onValueChange = { prCommentInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Paste senior developer PR comment...", fontSize = 11.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                )
            }
            Button(
                onClick = {
                    val newRule = SteeringRuleUI(
                        id = "rule-syn-${rules.size + 1}",
                        name = "No GlobalScope in ViewModels",
                        path = ".standards/steering/kmp/concurrency.md",
                        type = "synthesized_pr",
                        isEnabled = true,
                        boundPersonas = listOf("android_engineer", "adversarial_code_reviewer")
                    )
                    rules = rules + newRule
                    selectedRule = newRule
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple, contentColor = Color.White),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Synthesize & Bind Rule", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Split: Left = Active Rules List, Center = Binding Matrix, Right = Live Taboo Simulator
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Rules List
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text("STEERING DOCUMENTS", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(rules) { r ->
                        val isSelected = selectedRule.id == r.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) SurfaceCard else Color.Transparent)
                                .border(1.dp, if (isSelected) AccentCyan else BorderDark, RoundedCornerShape(6.dp))
                                .clickable { selectedRule = r }
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(r.name, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(BorderDark)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(r.type, color = AccentPurple, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(r.path, color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Binding Matrix
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text("PERSONA BINDING MATRIX", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Target: ${selectedRule.name}", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)

                Spacer(modifier = Modifier.height(10.dp))

                val availablePersonas = listOf(
                    "product_owner_lead" to "Product Owner Lead",
                    "senior_software_architect" to "Senior Software Architect",
                    "backend_engineer" to "Backend Systems Engineer",
                    "android_engineer" to "Android & Compose Engineer",
                    "adversarial_code_reviewer" to "Adversarial Code Reviewer",
                    "security_auditor" to "Security Auditor"
                )

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availablePersonas) { (id, label) ->
                        val isBound = selectedRule.boundPersonas.contains(id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceCard)
                                .clickable {
                                    val newBound = if (isBound) selectedRule.boundPersonas - id else selectedRule.boundPersonas + id
                                    selectedRule = selectedRule.copy(boundPersonas = newBound)
                                    rules = rules.map { if (it.id == selectedRule.id) selectedRule else it }
                                }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(label, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Text(id, color = TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                            Switch(
                                checked = isBound,
                                onCheckedChange = {
                                    val newBound = if (it) selectedRule.boundPersonas + id else selectedRule.boundPersonas - id
                                    selectedRule = selectedRule.copy(boundPersonas = newBound)
                                    rules = rules.map { r -> if (r.id == selectedRule.id) selectedRule else r }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AccentCyan,
                                    checkedTrackColor = BorderDark
                                )
                            )
                        }
                    }
                }
            }

            // Live Taboo Simulator (GUI Feature)
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text("LIVE TABOO SIMULATOR", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Type speculative code below to inspect triggered Taboos in real time:", color = TextMuted, fontSize = 10.sp)

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = codeSimulatorInput,
                    onValueChange = { codeSimulatorInput = it },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Highlight detected Taboo violations
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceCard)
                        .padding(8.dp)
                ) {
                    if (codeSimulatorInput.contains("android.database.sqlite")) {
                        Text("🚫 TABOO: Direct SQLite import violates UI isolation!", color = AccentRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (codeSimulatorInput.contains("Thread.sleep")) {
                        Text("⚠️ TABOO: Blocking main thread with Thread.sleep!", color = AccentAmber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (!codeSimulatorInput.contains("android.database.sqlite") && !codeSimulatorInput.contains("Thread.sleep")) {
                        Text("✅ Code complies with active Taboo Spaces.", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
