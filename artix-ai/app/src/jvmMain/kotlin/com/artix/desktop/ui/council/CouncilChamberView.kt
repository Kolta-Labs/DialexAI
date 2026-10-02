package com.artix.desktop.ui.council

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.artix.desktop.model.CouncilMessage
import com.artix.desktop.model.Stakeholder
import com.artix.desktop.ui.theme.*

@Composable
fun CouncilChamberView(
    onSendToLab: () -> Unit
) {
    var promptInput by remember { mutableStateOf("Implement OAuth2 Token Refresh with exponential backoff") }
    var selectedStyle by remember { mutableStateOf("standard") } // "standard", "ponytail", "caveman"
    var showInterviewModal by remember { mutableStateOf(false) }

    var poAnswer by remember { mutableStateOf("Silent background refresh on 401; fallback to login screen if refresh token expired") }
    var archAnswer by remember { mutableStateOf("AuthRepository manages token lifecycle; ViewModel only observes TokenState StateFlow") }
    var qaAnswer by remember { mutableStateOf("Mock HTTP client returning 401 and verify maximum 3 retry attempts before throwing error") }

    val stakeholders = listOf(
        Stakeholder("po", "Product Owner Lead", "Value & Scope", "PO", 0.95f),
        Stakeholder("arch", "Senior Architect", "Boundaries & Clean Arch", "SA", 0.90f),
        Stakeholder("qa", "QA Testing Lead", "Adversarial Edge Cases", "QA", 0.85f),
        Stakeholder("em", "Engineering Manager", "Delivery Feasibility", "EM", 0.80f)
    )

    val debateMessages = remember {
        listOf(
            CouncilMessage("po", "Product Owner", 1, "User story: When access token expires during background sync, system must refresh silently without disrupting user session."),
            CouncilMessage("qa", "QA Lead", 1, "Challenge: What happens if network times out during refresh token exchange? We need strict exponential backoff (max 3 retries) and safe offline fallback."),
            CouncilMessage("arch", "Senior Architect", 2, "Architecture decision: Token refresh logic belongs exclusively in the AuthRepository boundary. UI layers must only observe TokenState. Zero direct database writes in viewmodels."),
            CouncilMessage("em", "Engineering Manager", 2, "Scope is tight and feasible. Acceptance criteria verified. Synthesis complete; generating docs/specs/STORY-101.md.")
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        // Stakeholders Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("STAKEHOLDER COUNCIL CHAMBER", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            
            // Alignment Interview Trigger Button
            Button(
                onClick = { showInterviewModal = !showInterviewModal },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showInterviewModal) AccentPurple else SurfaceCard,
                    contentColor = TextPrimary
                ),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    if (showInterviewModal) "✕ Close Interview" else "🎙️ Alignment Interview (Grill-Me)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Avatar Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            stakeholders.forEach { s ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SurfaceCard)
                            .border(1.dp, AccentCyan, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(s.avatarInitials, color = AccentCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(s.name, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Text(s.role, color = TextSecondary, fontSize = 10.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { s.influenceScore },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = AccentCyan,
                            trackColor = BorderDark,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Optional Alignment Interview Drawer
        if (showInterviewModal) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceCard)
                    .border(1.dp, AccentPurple, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Text("SOCRATIC ALIGNMENT INTERVIEW (PRE-PLANNING CLARIFICATIONS)", color = AccentPurple, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("1. [Product Owner] Offline / Fallback:", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = poAnswer,
                            onValueChange = { poAnswer = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("2. [Architect] Boundary / State:", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = archAnswer,
                            onValueChange = { archAnswer = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("3. [QA Lead] Verification Strategy:", color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = qaAnswer,
                            onValueChange = { qaAnswer = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // Split Workbench: Deliberation Transcript on Left, Live Verified Spec Preview on Right
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Transcript Column
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Text("DELIBERATION TRANSCRIPT", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(debateMessages) { msg ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceCard)
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(msg.authorRole, color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Round ${msg.round}", color = TextMuted, fontSize = 10.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(msg.text, color = TextPrimary, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Style Vector Selector Pills
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Style Vector:", color = TextMuted, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    listOf(
                        "standard" to "Balanced (Standard)",
                        "ponytail" to "Executive (Ponytail)",
                        "caveman" to "Technical (Caveman)"
                    ).forEach { (key, label) ->
                        val isSelected = selectedStyle == key
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSelected) AccentCyan else SurfaceCard)
                                .clickable { selectedStyle = key }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                label,
                                color = if (isSelected) Color.Black else TextSecondary,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Prompt Input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Enter feature idea or user story...", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentCyan,
                            unfocusedBorderColor = BorderDark
                        )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
                    ) {
                        Text("Deliberate", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            // Spec Preview Column
            Column(
                modifier = Modifier
                    .weight(0.9f)
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
                    Text("VERIFIED STORY SPEC", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("VERIFIED & GROUNDED", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceCard)
                        .padding(12.dp)
                ) {
                    Text("# STORY-101: OAuth2 Token Refresh", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Target Spec File: docs/specs/STORY-101.md", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Acceptance Criteria (Gherkin):", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("• Scenario: Silent background refresh\n  Given access token is expired\n  When API request triggers\n  Then token is refreshed seamlessly", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Scope Boundaries:", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("• In-Scope: Token refresh, 401 interceptor, secure storage\n• Out-of-Scope: UI login redesign", color = TextSecondary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Verification Commands:", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text("• ./gradlew testDebugUnitTest\n• ./gradlew ktlintCheck", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onSendToLab,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentPurple, contentColor = Color.White)
                ) {
                    Text("Approve & Send to Engineering Lab", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }
}
