package com.artix.desktop.ui.lab

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
import com.artix.desktop.model.DiffChunk
import com.artix.desktop.ui.theme.*

@Composable
fun EngineeringLabView() {
    var selectedRound by remember { mutableStateOf(2) }
    var isRunningLoop by remember { mutableStateOf(false) }

    val roundHistory = listOf(
        1 to "Round 1 (Initial patch — Reviewer rejected missing exponential backoff test)",
        2 to "Round 2 (Current — Added AuthTest with MockWebServer, tests pass 100%)"
    )

    val initialChunks = remember {
        listOf(
            DiffChunk(
                id = "chunk-1",
                file = "shared/src/commonMain/kotlin/com/dialex/auth/AuthRepository.kt",
                header = "@@ -45,6 +45,18 @@ class AuthRepository",
                diffText = "+    suspend fun refreshToken(): Result<Token> {\n+        return client.post(\"/auth/refresh\")\n+    }",
                isAccepted = true
            ),
            DiffChunk(
                id = "chunk-2",
                file = "shared/src/commonTest/kotlin/com/dialex/auth/AuthTest.kt",
                header = "@@ -12,4 +12,12 @@ class AuthTest",
                diffText = "+    @Test\n+    fun testSilentRefreshSucceeds() = runTest {\n+        val result = repo.refreshToken()\n+        assertTrue(result.isSuccess)\n+    }",
                isAccepted = true
            )
        )
    }

    var diffChunks by remember { mutableStateOf(initialChunks) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        // Lab Header & Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("ENGINEERING LAB", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Domain Coder <-> Adversarial Reviewer Convergence Loop", color = TextSecondary, fontSize = 12.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        isRunningLoop = true
                        selectedRound = (selectedRound % 2) + 1
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = Color.Black)
                ) {
                    Text(if (isRunningLoop) "Iterating..." else "Run Iteration", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen, contentColor = Color.Black)
                ) {
                    Text("Merge Shadow Worktree", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Shadow Worktree Status & Time-Travel Scrubber Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceDark)
                .border(1.dp, BorderDark, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shadow Worktree Indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛡️ Shadow Worktree:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.width(6.dp))
                Text(".artix/worktrees/task-101", color = AccentCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.width(10.dp))
                Text("• Active workspace clean", color = AccentGreen, fontSize = 11.sp)
            }

            // Time Travel Round Checkpoint Scrubber
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Time-Travel Checkpoints:", color = TextMuted, fontSize = 11.sp)
                Spacer(modifier = Modifier.width(8.dp))
                roundHistory.forEach { (round, _) ->
                    val isSelected = selectedRound == round
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) AccentCyan else SurfaceCard)
                            .clickable { selectedRound = round }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "Round $round",
                            color = if (isSelected) Color.Black else TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Split Workbench: Coder Hunk Editor on Left, Reviewer & Sandbox on Right
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left: Domain Coder Patch & Chunk Toggles
            Column(
                modifier = Modifier
                    .weight(1.1f)
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
                    Text("DOMAIN CODER PATCH (ATOMIC DIFF)", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Interactive Chunk Accept/Reject", color = AccentPurple, fontSize = 10.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(diffChunks) { chunk ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(SurfaceCard)
                                .border(1.dp, if (chunk.isAccepted) AccentCyan.copy(alpha = 0.5f) else BorderDark, RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(chunk.file, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (chunk.isAccepted) AccentCyan else BorderDark)
                                        .clickable {
                                            diffChunks = diffChunks.map { if (it.id == chunk.id) it.copy(isAccepted = !it.isAccepted) else it }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        if (chunk.isAccepted) "ACCEPTED" else "REJECTED",
                                        color = if (chunk.isAccepted) Color.Black else TextMuted,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(chunk.header, color = AccentAmber, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(chunk.diffText, color = AccentGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp)
                        }
                    }
                }
            }

            // Right: Adversarial Reviewer & Sandbox Execution Terminal
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Reviewer Verdict Panel
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                        Text("ADVERSARIAL REVIEWER", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedRound == 2) AccentGreen.copy(alpha = 0.2f) else AccentRed.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                if (selectedRound == 2) "SIGN-OFF APPROVED" else "REVISION REQUIRED",
                                color = if (selectedRound == 2) AccentGreen else AccentRed,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (selectedRound == 2)
                            "Reviewer Verdict (Round 2): Code satisfies all acceptance criteria, meets active steering invariants, and passes all tests without main thread blocking."
                        else
                            "Reviewer Verdict (Round 1): Found 1 blocking issue: Missing unit test verification covering 401 retry limits.",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                // Sandbox Terminal
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                        .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("DETERMINISTIC SANDBOX EXECUTION", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text("Exit 0", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "> ./gradlew testDebugUnitTest --no-daemon\n[STDOUT] :shared:compileKotlinJvm\n[STDOUT] :shared:testDebugUnitTest\n[STDOUT] AuthTest.testSilentRefreshSucceeds PASSED [0.04s]\n[STDOUT] BUILD SUCCESSFUL in 1.4s",
                        color = AccentCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
