package com.dialex.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.logging.ApiCallRecord
import com.dialex.logging.ApiCallStatus
import com.dialex.logging.ApiCallStore
import com.dialex.logging.ApiCallType
import com.dialex.theme.LocalCcColors
import com.dialex.ui.AestheticSwitch
import com.dialex.ui.ThemedTooltipBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class InspectorFilterType(val label: String) {
    ALL("All"),
    ENGINE("Engine API"),
    AGENT("Agent API"),
    CLI("CLI Runner"),
    ERRORS("Errors")
}

enum class InspectorDetailTab(val label: String) {
    OVERVIEW("Overview"),
    REQUEST("Request"),
    RESPONSE("Response")
}

@Composable
fun ApiCallInspector(
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val allCalls by ApiCallStore.records.collectAsState()
    var selectedRecordId by remember { mutableStateOf<String?>(null) }
    val selectedRecord = remember(allCalls, selectedRecordId) {
        allCalls.firstOrNull { it.id == selectedRecordId }
    }

    if (selectedRecord != null) {
        ApiCallDetailView(
            record = selectedRecord,
            onBack = { selectedRecordId = null },
            modifier = modifier
        )
    } else {
        ApiCallListView(
            calls = allCalls,
            onSelectCall = { selectedRecordId = it.id },
            modifier = modifier
        )
    }
}

@Composable
private fun ApiCallListView(
    calls: List<ApiCallRecord>,
    onSelectCall: (ApiCallRecord) -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(InspectorFilterType.ALL) }
    var showEngineCalls by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copiedAll by remember { mutableStateOf(false) }

    LaunchedEffect(copiedAll) {
        if (copiedAll) {
            delay(2000)
            copiedAll = false
        }
    }

    val filteredCalls = remember(calls, searchQuery, selectedFilter, showEngineCalls) {
        calls.asReversed().filter { call ->
            val engineVisibilityPass = showEngineCalls || selectedFilter == InspectorFilterType.ENGINE || !call.isEngineCall

            val matchesFilter = when (selectedFilter) {
                InspectorFilterType.ALL -> true
                InspectorFilterType.ENGINE -> call.isEngineCall || call.type == ApiCallType.ENGINE_API
                InspectorFilterType.AGENT -> call.type == ApiCallType.AGENT_API && !call.isEngineCall
                InspectorFilterType.CLI -> call.type == ApiCallType.CLI_RUNNER && !call.isEngineCall
                InspectorFilterType.ERRORS -> call.status == ApiCallStatus.ERROR
            }
            val matchesSearch = searchQuery.isBlank() ||
                call.urlOrCommand.contains(searchQuery, ignoreCase = true) ||
                call.name.contains(searchQuery, ignoreCase = true) ||
                call.method.contains(searchQuery, ignoreCase = true) ||
                (call.responseBody?.contains(searchQuery, ignoreCase = true) == true) ||
                (call.requestBody?.contains(searchQuery, ignoreCase = true) == true) ||
                (call.errorDetails?.contains(searchQuery, ignoreCase = true) == true)

            engineVisibilityPass && matchesFilter && matchesSearch
        }
    }

    val totalCalls = calls.size
    val engineCallsCount = remember(calls) { calls.count { it.isEngineCall } }
    val visibleCalls = remember(calls, showEngineCalls) {
        if (showEngineCalls) calls else calls.filter { !it.isEngineCall }
    }
    val errorCount = remember(visibleCalls) { visibleCalls.count { it.status == ApiCallStatus.ERROR } }
    val totalTokens = remember(visibleCalls) { visibleCalls.sumOf { (it.tokensIn ?: 0) + (it.tokensOut ?: 0) } }
    val totalCost = remember(visibleCalls) { visibleCalls.sumOf { it.estimatedCostUsd ?: 0.0 } }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Top Summary Header ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "API & Agent Network Inspector",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.5.sp
                        ),
                        color = cc.textPrimary
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = cc.accent.copy(alpha = 0.15f),
                        border = BorderStroke(0.75.dp, cc.accent.copy(alpha = 0.4f))
                    ) {
                        Text(
                            "Chucker",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = cc.accent,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Inspect full request/response bodies, headers, token usage, latency, and status codes for all AI Agents, CLI runners, and Engine endpoints.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cc.textMuted
                )
            }

            // Metric Counters
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = cc.panelAlt,
                border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (showEngineCalls) "$totalCalls calls" else "${visibleCalls.size} agent calls",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        color = cc.textPrimary
                    )
                    if (!showEngineCalls && engineCallsCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF6366F1).copy(alpha = 0.12f),
                            border = BorderStroke(0.5.dp, Color(0xFF6366F1).copy(alpha = 0.35f))
                        ) {
                            Text(
                                "$engineCallsCount engine hidden",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = Color(0xFF818CF8),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                            )
                        }
                    }
                    if (errorCount > 0) {
                        Text(
                            "$errorCount errors",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = Color(0xFFEF4444)
                        )
                    }
                    if (totalTokens > 0) {
                        Text(
                            if (totalTokens >= 1000) "${totalTokens / 1000}k tokens" else "$totalTokens tokens",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                            color = cc.agentThird
                        )
                    }
                    if (totalCost > 0.0) {
                        val costStr = if (totalCost < 0.01) "<$0.01" else "$${((totalCost * 100).toLong()) / 100.0}"
                        Text(
                            costStr,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = cc.accent
                        )
                    }
                }
            }
        }

        // ── Controls & Filter Bar ──
        SettingCard {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Row 1: Search, Engine Calls Switch, and Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Search Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)),
                        modifier = Modifier.weight(1f).height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                tint = cc.textMuted,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Filter by URL, command, payload, or status...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = cc.textMuted.copy(alpha = 0.6f)
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 12.sp,
                                        color = cc.textPrimary
                                    ),
                                    cursorBrush = SolidColor(cc.accent),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(18.dp)) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear",
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Engine Calls Toggle Switch
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (showEngineCalls) cc.panel else cc.panelAlt,
                        border = BorderStroke(
                            1.dp,
                            if (showEngineCalls) cc.accent.copy(alpha = 0.55f) else cc.border.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AestheticSwitch(
                                checked = showEngineCalls,
                                onCheckedChange = { showEngineCalls = it }
                            )
                            Text(
                                "Engine Calls",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = if (showEngineCalls) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = if (showEngineCalls) cc.accent else cc.textMuted
                            )
                        }
                    }

                    // Clear All Calls
                    OutlinedButton(
                        onClick = { ApiCallStore.clear() },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = cc.textMuted
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Clear",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                            color = cc.textMuted
                        )
                    }
                }

                // Row 2: Filter Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InspectorFilterType.entries.forEach { filter ->
                        val isSelected = selectedFilter == filter
                        val count = when (filter) {
                            InspectorFilterType.ALL -> if (showEngineCalls) calls.size else calls.count { !it.isEngineCall }
                            InspectorFilterType.ENGINE -> calls.count { it.isEngineCall || it.type == ApiCallType.ENGINE_API }
                            InspectorFilterType.AGENT -> calls.count { it.type == ApiCallType.AGENT_API && !it.isEngineCall }
                            InspectorFilterType.CLI -> calls.count { it.type == ApiCallType.CLI_RUNNER && !it.isEngineCall }
                            InspectorFilterType.ERRORS -> if (showEngineCalls) calls.count { it.status == ApiCallStatus.ERROR } else calls.count { it.status == ApiCallStatus.ERROR && !it.isEngineCall }
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) cc.panel else cc.panelAlt,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) cc.accent.copy(alpha = 0.6f) else cc.border.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    selectedFilter = filter
                                    if (filter == InspectorFilterType.ENGINE) {
                                        showEngineCalls = true
                                    }
                                }
                        ) {
                            Text(
                                "${filter.label} ($count)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                ),
                                color = if (isSelected) cc.accent else cc.textMuted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Transaction List Container ──
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (cc.isDark) Color(0xFF141419) else Color(0xFFF9FAFB),
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 500.dp)
        ) {
            if (filteredCalls.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Dns,
                            contentDescription = null,
                            tint = cc.textMuted.copy(alpha = 0.4f),
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            if (searchQuery.isNotEmpty() || selectedFilter != InspectorFilterType.ALL) {
                                "No API transactions match the current filter"
                            } else {
                                "No API calls recorded yet. Send a debate message or trigger an action to inspect traffic."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = cc.textMuted
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredCalls, key = { it.id }) { record ->
                        ApiCallItemRow(
                            record = record,
                            onClick = { onSelectCall(record) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ApiCallItemRow(
    record: ApiCallRecord,
    onClick: () -> Unit
) {
    val cc = LocalCcColors.current
    val isError = record.status == ApiCallStatus.ERROR

    val statusBadgeColor = when {
        isError -> Color(0xFFEF4444)
        record.responseStatusCode != null && record.responseStatusCode in 200..299 -> Color(0xFF10B981)
        record.type == ApiCallType.CLI_RUNNER && record.responseStatusCode == 0 -> Color(0xFF10B981)
        else -> Color(0xFFF59E0B)
    }

    val methodColor = when (record.method.uppercase()) {
        "GET" -> Color(0xFF3B82F6)
        "POST" -> Color(0xFF10B981)
        "PUT" -> Color(0xFFF59E0B)
        "DELETE" -> Color(0xFFEF4444)
        "CLI" -> Color(0xFF8B5CF6)
        else -> cc.accent
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cc.panel,
        border = BorderStroke(
            0.75.dp,
            if (isError) Color(0xFFEF4444).copy(alpha = 0.35f) else cc.border.copy(alpha = 0.45f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left block: Status + Method + URL / Command
            Row(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Status Code Pill
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = statusBadgeColor.copy(alpha = 0.12f),
                    border = BorderStroke(0.75.dp, statusBadgeColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        record.responseStatusCode?.toString() ?: (if (isError) "ERR" else "200"),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        ),
                        color = statusBadgeColor,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Method Pill
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = methodColor.copy(alpha = 0.12f),
                    border = BorderStroke(0.75.dp, methodColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        record.method,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.5.sp
                        ),
                        color = methodColor,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                if (record.isEngineCall) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF6366F1).copy(alpha = 0.15f),
                        border = BorderStroke(0.75.dp, Color(0xFF6366F1).copy(alpha = 0.45f))
                    ) {
                        Text(
                            "ENGINE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp
                            ),
                            color = Color(0xFF818CF8),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }

                // Call Name / Path / Command
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        record.name,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        ),
                        color = if (isError) Color(0xFFEF4444) else cc.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        record.urlOrCommand,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp
                        ),
                        color = cc.textMuted.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Right block: Metrics (Duration, Size/Tokens, Time)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Token / Size Tag
                val tokenStr = record.formatTotalTokens()
                if (tokenStr != null) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f))
                    ) {
                        Text(
                            tokenStr,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = cc.agentThird,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                } else if (record.responseSizeBytes > 0) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f))
                    ) {
                        Text(
                            record.formatSize(record.responseSizeBytes),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp
                            ),
                            color = cc.textMuted,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                // Latency Badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f))
                ) {
                    Text(
                        record.formatDuration(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp
                        ),
                        color = cc.textMuted,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // Timestamp
                Text(
                    record.formatTimestamp(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp
                    ),
                    color = cc.textMuted.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ApiCallDetailView(
    record: ApiCallRecord,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    var selectedTab by remember { mutableStateOf(InspectorDetailTab.OVERVIEW) }
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var copiedNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(copiedNotice) {
        if (copiedNotice != null) {
            delay(2000)
            copiedNotice = null
        }
    }

    val isError = record.status == ApiCallStatus.ERROR
    val statusBadgeColor = when {
        isError -> Color(0xFFEF4444)
        record.responseStatusCode != null && record.responseStatusCode in 200..299 -> Color(0xFF10B981)
        record.type == ApiCallType.CLI_RUNNER && record.responseStatusCode == 0 -> Color(0xFF10B981)
        else -> Color(0xFFF59E0B)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── Navigation & Header ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(13.dp),
                        tint = cc.textPrimary
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Back to List",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        color = cc.textPrimary
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = statusBadgeColor.copy(alpha = 0.15f),
                    border = BorderStroke(0.75.dp, statusBadgeColor.copy(alpha = 0.5f))
                ) {
                    Text(
                        "${record.method} ${record.responseStatusCode ?: (if (isError) "ERROR" else "200 OK")}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp
                        ),
                        color = statusBadgeColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                if (record.isEngineCall) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF6366F1).copy(alpha = 0.15f),
                        border = BorderStroke(0.75.dp, Color(0xFF6366F1).copy(alpha = 0.5f))
                    ) {
                        Text(
                            "ENGINE CALL",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = Color(0xFF818CF8),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Quick Copy Actions
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(record.toCurlCommand()))
                        copiedNotice = "cURL copied"
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Icon(
                        Icons.Outlined.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (copiedNotice == "cURL copied") cc.accent else cc.textMuted
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (copiedNotice == "cURL copied") "cURL Copied!" else "Copy cURL",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        color = if (copiedNotice == "cURL copied") cc.accent else cc.textPrimary
                    )
                }
            }
        }

        // ── Call Endpoint Banner ──
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    record.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp
                    ),
                    color = cc.textPrimary
                )
                SelectionContainer {
                    Text(
                        record.urlOrCommand,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp
                        ),
                        color = cc.textMuted
                    )
                }
            }
        }

        // ── Chucker Sub-Tabs (Overview / Request / Response) ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InspectorDetailTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) cc.panel else cc.panelAlt,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) cc.accent.copy(alpha = 0.7f) else cc.border.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { selectedTab = tab }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 12.5.sp
                            ),
                            color = if (isSelected) cc.accent else cc.textMuted
                        )
                    }
                }
            }
        }

        // ── Tab Content Container ──
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (cc.isDark) Color(0xFF141419) else Color(0xFFF9FAFB),
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().heightIn(min = 340.dp, max = 520.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                when (selectedTab) {
                    InspectorDetailTab.OVERVIEW -> DetailOverviewTab(record)
                    InspectorDetailTab.REQUEST -> DetailRequestTab(record)
                    InspectorDetailTab.RESPONSE -> DetailResponseTab(record)
                }
            }
        }
    }
}

@Composable
private fun DetailOverviewTab(record: ApiCallRecord) {
    val cc = LocalCcColors.current
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OverviewSection(title = "General Information") {
            OverviewRow("Call Type", record.type.label)
            OverviewRow("Method", record.method)
            OverviewRow("Status", record.status.label + (record.responseStatusCode?.let { " ($it)" } ?: ""))
            OverviewRow("Timestamp", record.formatTimestamp())
            OverviewRow("Duration", record.formatDuration())
            OverviewRow("Request Size", record.formatSize(record.requestSizeBytes))
            OverviewRow("Response Size", record.formatSize(record.responseSizeBytes))
        }

        if (record.provider != null || record.model != null || record.tokensIn != null) {
            OverviewSection(title = "AI Model & Token Metrics") {
                record.provider?.let { OverviewRow("Provider", it.name) }
                record.model?.let { OverviewRow("Model", it) }
                record.tokensIn?.let { OverviewRow("Input Tokens (Prompt)", "$it tokens") }
                record.tokensOut?.let { OverviewRow("Output Tokens (Completion)", "$it tokens") }
                record.tokensCached?.let { OverviewRow("Cached Read Tokens", "$it tokens") }
                record.estimatedCostUsd?.let {
                    OverviewRow("Estimated Cost (USD)", "$${((it * 10000).toLong()) / 10000.0}")
                }
            }
        }

        if (record.errorDetails != null) {
            OverviewSection(title = "Execution Failure", isError = true) {
                SelectionContainer {
                    Text(
                        record.errorDetails,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        ),
                        color = Color(0xFFEF4444)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRequestTab(record: ApiCallRecord) {
    val cc = LocalCcColors.current
    val clipboard = LocalClipboardManager.current
    var copiedBody by remember { mutableStateOf(false) }

    LaunchedEffect(copiedBody) {
        if (copiedBody) {
            delay(2000)
            copiedBody = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Headers Section
        if (record.requestHeaders.isNotEmpty()) {
            OverviewSection(title = "Request Headers (${record.requestHeaders.size})") {
                record.requestHeaders.forEach { (k, v) ->
                    OverviewRow(k, v)
                }
            }
        }

        // Body Section
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Request Payload (${record.formatSize(record.requestSizeBytes)})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp
                    ),
                    color = cc.textPrimary
                )
                if (!record.requestBody.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(record.requestBody))
                            copiedBody = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            if (copiedBody) "Copied!" else "Copy Payload",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (copiedBody) cc.accent else cc.textMuted
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = cc.panel,
                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (record.requestBody.isNullOrBlank()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("(No request payload)", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = cc.textMuted)
                    }
                } else {
                    SelectionContainer {
                        Text(
                            ApiCallStore.formatPrettyJson(record.requestBody),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            ),
                            color = cc.textPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailResponseTab(record: ApiCallRecord) {
    val cc = LocalCcColors.current
    val clipboard = LocalClipboardManager.current
    var copiedBody by remember { mutableStateOf(false) }

    LaunchedEffect(copiedBody) {
        if (copiedBody) {
            delay(2000)
            copiedBody = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Headers Section
        if (record.responseHeaders.isNotEmpty()) {
            OverviewSection(title = "Response Headers (${record.responseHeaders.size})") {
                record.responseHeaders.forEach { (k, v) ->
                    OverviewRow(k, v)
                }
            }
        }

        // Response Body
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Response Output (${record.formatSize(record.responseSizeBytes)})",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp
                    ),
                    color = cc.textPrimary
                )
                if (!record.responseBody.isNullOrBlank()) {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(record.responseBody))
                            copiedBody = true
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(
                            if (copiedBody) "Copied!" else "Copy Response",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (copiedBody) cc.accent else cc.textMuted
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = cc.panel,
                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (record.responseBody.isNullOrBlank()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("(No response payload)", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = cc.textMuted)
                    }
                } else {
                    SelectionContainer {
                        Text(
                            ApiCallStore.formatPrettyJson(record.responseBody),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp
                            ),
                            color = if (record.status == ApiCallStatus.ERROR) Color(0xFFEF4444) else cc.textPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewSection(
    title: String,
    isError: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val cc = LocalCcColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.5.sp
            ),
            color = if (isError) Color(0xFFEF4444) else cc.textPrimary
        )
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = cc.panel,
            border = BorderStroke(
                0.75.dp,
                if (isError) Color(0xFFEF4444).copy(alpha = 0.4f) else cc.border.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun OverviewRow(label: String, value: String) {
    val cc = LocalCcColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            ),
            color = cc.textMuted,
            modifier = Modifier.widthIn(min = 100.dp, max = 160.dp)
        )
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp
                ),
                color = cc.textPrimary
            )
        }
    }
}
