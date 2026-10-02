package com.kritix.desktop.ui.knowledge

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
import com.kritix.desktop.ui.theme.*

data class KnowledgeItemUI(
    val id: String,
    val title: String,
    val category: String,
    val context: String,
    val breakthrough: String
)

@Composable
fun KnowledgeInsightsView() {
    val items = remember {
        listOf(
            KnowledgeItemUI(
                id = "ki-101",
                title = "Silent Token Refresh on 401 Interceptor",
                category = "architecture",
                context = "KMP Ktor Client with Auth Plugin",
                breakthrough = "Always refresh in Mutex lock to avoid multiple concurrent refresh calls when parallel requests 401."
            ),
            KnowledgeItemUI(
                id = "ki-102",
                title = "Preventing Coroutine Leak in Compose Desktop",
                category = "debugging",
                context = "DesktopApp Window disposal lifecycle",
                breakthrough = "Bind background polling jobs to rememberCoroutineScope() instead of GlobalScope."
            ),
            KnowledgeItemUI(
                id = "ki-103",
                title = "SQLite Multi-Threaded Read Lockup",
                category = "flaky_test",
                context = "Android & Desktop SQLite unit tests",
                breakthrough = "Enable WAL (Write-Ahead-Logging) mode on in-memory SQLite connections during parallel test runs."
            )
        )
    }

    var selectedItem by remember { mutableStateOf(items.first()) }
    var searchQuery by remember { mutableStateOf("") }

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
                Text("KNOWLEDGE ITEMS & INSTITUTIONAL MEMORY", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("Auto-Evolving Repository Learnings & Breakthroughs", color = TextSecondary, fontSize = 12.sp)
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceCard)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("${items.size} Knowledge Units Indexed", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Split: Left = Knowledge Item Cards, Right = Deep Detail & Code Breakthrough
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Left: List
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Text("INDEXED KNOWLEDGE ITEMS", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items) { ki ->
                        val isSelected = selectedItem.id == ki.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) SurfaceCard else Color.Transparent)
                                .border(1.dp, if (isSelected) AccentCyan else BorderDark, RoundedCornerShape(6.dp))
                                .clickable { selectedItem = ki }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(ki.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(BorderDark)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(ki.category, color = AccentPurple, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(ki.context, color = TextMuted, fontSize = 10.sp)
                        }
                    }
                }
            }

            // Right: Detail & Breakthrough
            Column(
                modifier = Modifier
                    .weight(1.2f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceDark)
                    .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(selectedItem.title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text("ID: ${selectedItem.id} • Category: ${selectedItem.category}", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(AccentGreen.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text("AUTO-LEARNED", color = AccentGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("PROBLEM CONTEXT:", color = AccentCyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    selectedItem.context,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text("BREAKTHROUGH & SOLUTION INSIGHT:", color = AccentGreen, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(SurfaceCard)
                        .border(1.dp, AccentGreen.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        selectedItem.breakthrough,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("APPLIED REPOSITORY BENEFIT:", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "This insight is automatically injected into future Stakeholder Planning Councils and Domain Coder prompts whenever related files or keywords are touched, preventing duplicate bugs.",
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}
