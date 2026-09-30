package com.dialex.presentation.arena

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.BenchmarkCase
import com.dialex.domain.model.BenchmarkRun
import com.dialex.domain.model.MetricDimension

private enum class BenchmarkMobileTab {
    ARENA,
    CASES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    state: BenchmarkState,
    onIntent: (BenchmarkIntent) -> Unit,
    onBack: () -> Unit,
    isCompact: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showCreateCaseDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val compactMode = isCompact || maxWidth < 720.dp
        var mobileTab by remember { mutableStateOf(BenchmarkMobileTab.ARENA) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (compactMode) "⚔️ Benchmark Arena" else "⚔️ Deliberation Arena & Null Hypothesis Benchmark Suite",
                                fontWeight = FontWeight.Bold,
                                fontSize = if (compactMode) 15.sp else 18.sp
                            )
                            if (!compactMode) {
                                Spacer(Modifier.width(12.dp))
                                state.summary?.let { summary ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (summary.isStatSignificant) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                        border = BorderStroke(1.dp, if (summary.isStatSignificant) Color(0xFF10B981) else Color.Transparent)
                                    ) {
                                        Text(
                                            text = "Council Win Rate: ${(summary.councilWinRate * 100).toInt()}% · p = ${summary.pValue}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (summary.isStatSignificant) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { onIntent(BenchmarkIntent.ExportResults("markdown")) }) {
                            Icon(Icons.Default.Download, contentDescription = "Export Markdown")
                        }
                    }
                )
            }
        ) { padding ->
            if (compactMode) {
                // Mobile Responsive Single-Column Layout with Top Tab Switcher
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    PrimaryTabRow(
                        selectedTabIndex = mobileTab.ordinal,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Tab(
                            selected = mobileTab == BenchmarkMobileTab.ARENA,
                            onClick = { mobileTab = BenchmarkMobileTab.ARENA },
                            text = { Text("⚔️ Arena & Radar", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = mobileTab == BenchmarkMobileTab.CASES,
                            onClick = { mobileTab = BenchmarkMobileTab.CASES },
                            text = { Text("📋 Dilemma Suite (${state.cases.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        )
                    }

                    when (mobileTab) {
                        BenchmarkMobileTab.ARENA -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(14.dp)
                            ) {
                                state.selectedCase?.let { bCase ->
                                    ActiveDilemmaArena(
                                        bCase = bCase,
                                        activeRun = state.activeRun,
                                        isRunning = state.isRunning,
                                        progress = state.runProgress,
                                        activePhase = state.activePhase,
                                        isCompact = true,
                                        onRunBenchmark = { onIntent(BenchmarkIntent.StartBenchmarkRun(bCase.id)) }
                                    )
                                } ?: run {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                "No benchmark dilemma selected",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.height(12.dp))
                                            Button(
                                                onClick = { mobileTab = BenchmarkMobileTab.CASES },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                            ) {
                                                Text("Choose a Dilemma")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        BenchmarkMobileTab.CASES -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .padding(14.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "BENCHMARK CASES",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = { showCreateCaseDialog = true },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("New Case", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(Modifier.height(8.dp))

                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(state.cases, key = { it.id }) { bCase ->
                                        val isSelected = bCase.id == state.selectedCase?.id
                                        CaseCard(
                                            bCase = bCase,
                                            isSelected = isSelected,
                                            onClick = {
                                                onIntent(BenchmarkIntent.SelectCase(bCase.id))
                                                mobileTab = BenchmarkMobileTab.ARENA
                                            }
                                        )
                                    }
                                }

                                Spacer(Modifier.height(10.dp))

                                state.summary?.let { summary ->
                                    SummaryStatsCard(summary)
                                }
                            }
                        }
                    }
                }
            } else {
                // Desktop Two-Column Wide Layout
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    // Left Pane: Case Picker & Benchmark History
                    Column(
                        modifier = Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "BENCHMARK CASES",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = { showCreateCaseDialog = true }) {
                                Icon(Icons.Default.Add, contentDescription = "Add Custom Case", Modifier.size(18.dp))
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.cases, key = { it.id }) { bCase ->
                                val isSelected = bCase.id == state.selectedCase?.id
                                CaseCard(
                                    bCase = bCase,
                                    isSelected = isSelected,
                                    onClick = { onIntent(BenchmarkIntent.SelectCase(bCase.id)) }
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // Summary Stats Card
                        state.summary?.let { summary ->
                            SummaryStatsCard(summary)
                        }
                    }

                    VerticalDivider()

                    // Right Pane: Active Dilemma, Execution Controls & Side-by-Side Arena
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(24.dp)
                    ) {
                        state.selectedCase?.let { bCase ->
                            ActiveDilemmaArena(
                                bCase = bCase,
                                activeRun = state.activeRun,
                                isRunning = state.isRunning,
                                progress = state.runProgress,
                                activePhase = state.activePhase,
                                isCompact = false,
                                onRunBenchmark = { onIntent(BenchmarkIntent.StartBenchmarkRun(bCase.id)) }
                            )
                        } ?: run {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Select a benchmark dilemma to begin evaluation", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        if (showCreateCaseDialog) {
            CreateCaseDialog(
                onDismiss = { showCreateCaseDialog = false },
                onCreate = { newCase ->
                    onIntent(BenchmarkIntent.CreateCustomCase(newCase))
                    showCreateCaseDialog = false
                }
            )
        }
    }
}

@Composable
private fun CaseCard(
    bCase: BenchmarkCase,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    bCase.id,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                )
                Text(
                    bCase.domain,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                bCase.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SummaryStatsCard(summary: com.dialex.domain.model.BenchmarkSummary) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("EMPIRICAL SCORECARD", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total Runs:", fontSize = 12.sp)
                Text("${summary.totalRuns}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Council Wins:", fontSize = 12.sp)
                Text("${summary.councilWins}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF10B981))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Solo Wins:", fontSize = 12.sp)
                Text("${summary.soloWins}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFF59E0B))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Mean Delta Q:", fontSize = 12.sp)
                Text("%+.2f".format(summary.meanDeltaQ), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("t-test p-value:", fontSize = 12.sp)
                Text("%.4f".format(summary.pValue), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ActiveDilemmaArena(
    bCase: BenchmarkCase,
    activeRun: BenchmarkRun?,
    isRunning: Boolean,
    progress: Float,
    activePhase: String,
    isCompact: Boolean = false,
    onRunBenchmark: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {
        // Dilemma Header Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                if (isCompact) {
                    Text(bCase.title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onRunBenchmark,
                        enabled = !isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isRunning) "Running Evaluation..." else "Run Dual-Arm Evaluation", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(bCase.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        Button(
                            onClick = onRunBenchmark,
                            enabled = !isRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isRunning) "Running Evaluation..." else "Run Dual-Arm Evaluation")
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(bCase.dilemma, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)

                Spacer(Modifier.height(10.dp))
                if (isCompact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column {
                            Text("CONSTRAINTS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            bCase.constraints.forEach { Text("• $it", fontSize = 11.sp) }
                        }
                        Column {
                            Text("GROUND TRUTH FAILURE TRAPS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                            bCase.groundTruthTraps.forEach { Text("⚠️ $it", fontSize = 11.sp, color = Color(0xFFEF4444)) }
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("CONSTRAINTS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            bCase.constraints.forEach { Text("• $it", fontSize = 11.sp) }
                        }
                        Column(Modifier.weight(1f)) {
                            Text("GROUND TRUTH FAILURE TRAPS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFEF4444))
                            bCase.groundTruthTraps.forEach { Text("⚠️ $it", fontSize = 11.sp, color = Color(0xFFEF4444)) }
                        }
                    }
                }
            }
        }

        if (isRunning) {
            Spacer(Modifier.height(16.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(activePhase, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
        }

        activeRun?.let { run ->
            Spacer(Modifier.height(20.dp))

            // Scoreboard Verdict Banner
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (run.winner == "COUNCIL") Color(0xFF10B981).copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f),
                border = BorderStroke(1.dp, if (run.winner == "COUNCIL") Color(0xFF10B981) else Color(0xFFF59E0B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "EVALUATION OUTCOME: ${run.winner} WIN",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (run.winner == "COUNCIL") Color(0xFF10B981) else Color(0xFFF59E0B)
                        )
                        Text(
                            text = "Quality: Solo ${run.soloTotalScore} vs Council ${run.councilTotalScore} (Delta Q: %+.2f)".format(run.deltaQ),
                            fontSize = 11.5.sp
                        )
                    }
                    Text("Judge: ${run.judgeModel}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Dual Arm Deliverable Comparison: Stacked on mobile, side-by-side on desktop
            if (isCompact) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Arm A: Solo
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ARM A: SOLO BASELINE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFF59E0B))
                                Text("${run.soloTotalScore} / 10.0", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Text(run.soloResult.modelOrCouncil, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text(run.soloResult.deliverable, fontSize = 11.sp, maxLines = 8)
                        }
                    }

                    // Arm B: Council
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ARM B: DIALEX COUNCIL", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF8B5CF6))
                                Text("${run.councilTotalScore} / 10.0", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Text(run.councilResult.modelOrCouncil, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            Text(run.councilResult.deliverable, fontSize = 11.sp, maxLines = 8)
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Arm A: Solo
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ARM A: SOLO BASELINE", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFFF59E0B))
                                Text("${run.soloTotalScore} / 10.0", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Text(run.soloResult.modelOrCouncil, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(run.soloResult.deliverable, fontSize = 11.sp, maxLines = 12)
                        }
                    }

                    // Arm B: Council
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.5f)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("ARM B: DIALEX COUNCIL", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF8B5CF6))
                                Text("${run.councilTotalScore} / 10.0", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Text(run.councilResult.modelOrCouncil, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(run.councilResult.deliverable, fontSize = 11.sp, maxLines = 12)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Radar Chart & Scorecard Details: Stacked on mobile, side-by-side on desktop
            if (isCompact) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth().height(260.dp)
                    ) {
                        ArenaRadarChart(evaluations = run.evaluations, modifier = Modifier.fillMaxSize())
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text("DOUBLE-BLIND CRITIQUE AUDIT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            run.evaluations.firstOrNull()?.let { ev ->
                                Text(ev.overallVerdict, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                Text(ev.detailedCritique, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8)
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(280.dp)
                    ) {
                        ArenaRadarChart(evaluations = run.evaluations, modifier = Modifier.fillMaxSize())
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f).height(280.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("DOUBLE-BLIND CRITIQUE AUDIT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            run.evaluations.firstOrNull()?.let { ev ->
                                Text(ev.overallVerdict, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp))
                                Text(ev.detailedCritique, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 8)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateCaseDialog(
    onDismiss: () -> Unit,
    onCreate: (BenchmarkCase) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("ARCHITECTURE") }
    var dilemma by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Custom Benchmark Dilemma") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("Domain") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dilemma,
                    onValueChange = { dilemma = it },
                    label = { Text("Dilemma Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        BenchmarkCase(
                            id = "",
                            title = title,
                            domain = domain,
                            dilemma = dilemma
                        )
                    )
                },
                enabled = title.isNotBlank() && dilemma.isNotBlank()
            ) {
                Text("Create Case")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
