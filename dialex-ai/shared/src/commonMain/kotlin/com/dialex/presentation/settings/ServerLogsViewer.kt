@file:Suppress("DEPRECATION")

package com.dialex.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import com.dialex.logging.AppLogEntry
import com.dialex.logging.AppLogLevel
import com.dialex.logging.AppLogStore
import com.dialex.theme.LocalCcColors
import com.dialex.ui.AestheticSwitch
import com.dialex.ui.ThemedTooltipBox
import com.dialex.util.formatMessageTimestamp
import com.dialex.util.formatRelativeTime

/**
 * Grouped representation of identical or related log entries / errors (Crashlytics Issue model).
 */
data class LogGroup(
    val key: String,
    val level: AppLogLevel,
    val tag: String,
    val summaryMessage: String,
    val errorDetails: String?,
    val firstTimestampMs: Long,
    val latestTimestampMs: Long,
    val entries: List<AppLogEntry>
) {
    val count: Int get() = entries.size

    val exceptionName: String
        get() {
            if (errorDetails != null) {
                val firstLine = errorDetails.lines().firstOrNull()?.trim()
                if (!firstLine.isNullOrBlank()) return firstLine
            }
            return summaryMessage.lines().firstOrNull()?.trim() ?: summaryMessage
        }

    val componentTitle: String
        get() = when {
            tag.isNotBlank() -> tag
            summaryMessage.contains("skiko", ignoreCase = true) -> "Skiko Engine"
            summaryMessage.contains("engine", ignoreCase = true) -> "Backend Engine"
            summaryMessage.contains("api", ignoreCase = true) -> "Network Client"
            else -> "Runtime System"
        }
}

data class StackFrame(
    val raw: String,
    val method: String,
    val fileAndLine: String?,
    val isAppFrame: Boolean,
    val isHeader: Boolean = false
)

/**
 * Master-Detail Crashlytics-style Error & Runtime Logs Viewer.
 */
@Composable
fun ServerLogsViewer(
    modifier: Modifier = Modifier
) {
    val allLogs by AppLogStore.logs.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedLevelFilter by remember { mutableStateOf<AppLogLevel?>(null) }
    var selectedTagFilter by remember { mutableStateOf<String?>(null) }
    var isGroupedView by remember { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(true) }

    var selectedGroupKey by remember { mutableStateOf<String?>(null) }
    var selectedOccurrenceId by remember { mutableStateOf<Long?>(null) }

    val availableTags = remember(allLogs) {
        allLogs.map { it.tag }.distinct().sorted()
    }

    val groupedLogs = remember(allLogs, searchQuery, selectedLevelFilter, selectedTagFilter) {
        val filtered = allLogs.filter { log ->
            (selectedLevelFilter == null || log.level == selectedLevelFilter) &&
            (selectedTagFilter == null || log.tag.equals(selectedTagFilter, ignoreCase = true)) &&
            (searchQuery.isBlank() ||
                log.message.contains(searchQuery, ignoreCase = true) ||
                log.tag.contains(searchQuery, ignoreCase = true) ||
                (log.errorDetails?.contains(searchQuery, ignoreCase = true) == true))
        }

        val map = linkedMapOf<String, MutableList<AppLogEntry>>()
        for (log in filtered) {
            val normalizedMsg = log.message.trim().lines().firstOrNull() ?: log.message
            val errorSig = log.errorDetails?.trim()?.lines()?.firstOrNull() ?: ""
            val groupKey = "${log.level.name}|${log.tag}|$normalizedMsg|$errorSig"
            map.getOrPut(groupKey) { mutableListOf() }.add(log)
        }

        map.map { (key, entries) ->
            val first = entries.first()
            val latest = entries.last()
            LogGroup(
                key = key,
                level = first.level,
                tag = first.tag,
                summaryMessage = first.message,
                errorDetails = entries.mapNotNull { it.errorDetails }.firstOrNull(),
                firstTimestampMs = first.timestampMs,
                latestTimestampMs = latest.timestampMs,
                entries = entries.reversed()
            )
        }.sortedByDescending { it.latestTimestampMs }
    }

    val rawFilteredLogs = remember(allLogs, searchQuery, selectedLevelFilter, selectedTagFilter) {
        allLogs.filter { log ->
            (selectedLevelFilter == null || log.level == selectedLevelFilter) &&
            (selectedTagFilter == null || log.tag.equals(selectedTagFilter, ignoreCase = true)) &&
            (searchQuery.isBlank() ||
                log.message.contains(searchQuery, ignoreCase = true) ||
                log.tag.contains(searchQuery, ignoreCase = true) ||
                (log.errorDetails?.contains(searchQuery, ignoreCase = true) == true))
        }
    }

    val selectedGroup = remember(groupedLogs, selectedGroupKey) {
        groupedLogs.firstOrNull { it.key == selectedGroupKey }
    }

    if (selectedGroup != null) {
        LogIssueDetailView(
            group = selectedGroup,
            allGroups = groupedLogs,
            selectedOccurrenceId = selectedOccurrenceId,
            onSelectOccurrence = { selectedOccurrenceId = it },
            onSelectGroup = { group ->
                selectedGroupKey = group.key
                selectedOccurrenceId = group.entries.firstOrNull()?.id
            },
            onBack = {
                selectedGroupKey = null
                selectedOccurrenceId = null
            },
            modifier = modifier
        )
    } else {
        LogMasterListView(
            allLogs = allLogs,
            groupedLogs = groupedLogs,
            rawFilteredLogs = rawFilteredLogs,
            availableTags = availableTags,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            selectedLevelFilter = selectedLevelFilter,
            onSelectLevelFilter = { selectedLevelFilter = it },
            selectedTagFilter = selectedTagFilter,
            onSelectTagFilter = { selectedTagFilter = it },
            isGroupedView = isGroupedView,
            onToggleView = { isGroupedView = it },
            autoScroll = autoScroll,
            onToggleAutoScroll = { autoScroll = it },
            onSelectGroup = { group ->
                selectedGroupKey = group.key
                selectedOccurrenceId = group.entries.firstOrNull()?.id
            },
            modifier = modifier
        )
    }
}

@Composable
private fun LogMasterListView(
    allLogs: List<AppLogEntry>,
    groupedLogs: List<LogGroup>,
    rawFilteredLogs: List<AppLogEntry>,
    availableTags: List<String>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedLevelFilter: AppLogLevel?,
    onSelectLevelFilter: (AppLogLevel?) -> Unit,
    selectedTagFilter: String?,
    onSelectTagFilter: (String?) -> Unit,
    isGroupedView: Boolean,
    onToggleView: (Boolean) -> Unit,
    autoScroll: Boolean,
    onToggleAutoScroll: (Boolean) -> Unit,
    onSelectGroup: (LogGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val clipboard = LocalClipboardManager.current
    val errorCount = remember(allLogs) { allLogs.count { it.level == AppLogLevel.ERROR } }
    val warnCount = remember(allLogs) { allLogs.count { it.level == AppLogLevel.WARN } }
    val infoCount = remember(allLogs) { allLogs.count { it.level == AppLogLevel.INFO } }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Toolbar
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Search bar
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.45f)),
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
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        "Filter by message, exception, tag, or stack...",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = cc.textMuted.copy(alpha = 0.6f)
                                    )
                                }
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = onSearchQueryChange,
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
                                IconButton(
                                    onClick = { onSearchQueryChange("") },
                                    modifier = Modifier.size(18.dp)
                                ) {
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

                    // Mode Switch
                    SubtleSegmentedControl(
                        options = listOf(true to "Grouped Issues", false to "Raw Stream"),
                        selected = isGroupedView,
                        onSelect = onToggleView
                    )

                    // Export / Copy All
                    ThemedTooltipBox("Copy all logs") {
                        IconButton(
                            onClick = {
                                val text = allLogs.joinToString("\n") { log ->
                                    "[${formatMessageTimestamp(log.timestampMs)}] [${log.level.name}] [${log.tag}] ${log.message}${if (log.errorDetails != null) "\n" + log.errorDetails else ""}"
                                }
                                clipboard.setText(AnnotatedString(text))
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy all",
                                tint = cc.textMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }

                    // Clear
                    ThemedTooltipBox("Clear logs") {
                        IconButton(
                            onClick = { AppLogStore.clear() },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Clear",
                                tint = cc.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Filter chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        label = "All (${allLogs.size})",
                        isSelected = selectedLevelFilter == null,
                        onClick = { onSelectLevelFilter(null) }
                    )
                    FilterChip(
                        label = "Errors ($errorCount)",
                        isSelected = selectedLevelFilter == AppLogLevel.ERROR,
                        color = Color(0xFFEF4444),
                        onClick = {
                            onSelectLevelFilter(if (selectedLevelFilter == AppLogLevel.ERROR) null else AppLogLevel.ERROR)
                        }
                    )
                    FilterChip(
                        label = "Warnings ($warnCount)",
                        isSelected = selectedLevelFilter == AppLogLevel.WARN,
                        color = Color(0xFFF59E0B),
                        onClick = {
                            onSelectLevelFilter(if (selectedLevelFilter == AppLogLevel.WARN) null else AppLogLevel.WARN)
                        }
                    )
                    FilterChip(
                        label = "Info ($infoCount)",
                        isSelected = selectedLevelFilter == AppLogLevel.INFO,
                        color = cc.agentRight,
                        onClick = {
                            onSelectLevelFilter(if (selectedLevelFilter == AppLogLevel.INFO) null else AppLogLevel.INFO)
                        }
                    )

                    if (availableTags.isNotEmpty()) {
                        VerticalDivider(modifier = Modifier.height(18.dp), color = cc.border.copy(alpha = 0.4f))
                        availableTags.take(5).forEach { tag ->
                            FilterChip(
                                label = tag,
                                isSelected = selectedTagFilter == tag,
                                onClick = {
                                    onSelectTagFilter(if (selectedTagFilter == tag) null else tag)
                                }
                            )
                        }
                    }
                }
            }
        }

        // List Content
        if (isGroupedView) {
            if (groupedLogs.isEmpty()) {
                EmptyLogsPlaceholder()
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    groupedLogs.forEach { group ->
                        MasterLogGroupCard(
                            group = group,
                            onClick = { onSelectGroup(group) }
                        )
                    }
                }
            }
        } else {
            // Raw stream
            if (rawFilteredLogs.isEmpty()) {
                EmptyLogsPlaceholder()
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF0D0D11),
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 560.dp)
                ) {
                    val scrollState = rememberScrollState()
                    LaunchedEffect(rawFilteredLogs.size, autoScroll) {
                        if (autoScroll && rawFilteredLogs.isNotEmpty()) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }
                    SelectionContainer {
                        Column(
                            modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            rawFilteredLogs.forEach { log ->
                                RawLogLine(log = log)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MasterLogGroupCard(
    group: LogGroup,
    onClick: () -> Unit
) {
    val cc = LocalCcColors.current
    val levelColor = when (group.level) {
        AppLogLevel.ERROR -> Color(0xFFEF4444)
        AppLogLevel.WARN -> Color(0xFFF59E0B)
        AppLogLevel.INFO -> cc.agentRight
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = cc.panel,
        border = BorderStroke(
            1.dp,
            if (group.level == AppLogLevel.ERROR) levelColor.copy(alpha = 0.35f) else cc.border.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Level Badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = levelColor.copy(alpha = 0.15f),
                    border = BorderStroke(0.75.dp, levelColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        group.level.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.5.sp),
                        color = levelColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Component Tag
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f))
                ) {
                    Text(
                        group.componentTitle,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                        color = cc.textMuted,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Title + Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.exceptionName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                        color = cc.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        group.summaryMessage,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = cc.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Right side stats: Events badge + Latest timestamp + Chevron
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Event count badge
                Surface(
                    shape = CircleShape,
                    color = if (group.count > 1) levelColor.copy(alpha = 0.18f) else cc.panelAlt,
                    border = BorderStroke(1.dp, if (group.count > 1) levelColor.copy(alpha = 0.4f) else cc.border.copy(alpha = 0.4f))
                ) {
                    Text(
                        "${group.count} event${if (group.count > 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.5.sp
                        ),
                        color = if (group.count > 1) levelColor else cc.textMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                // Timestamp
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatMessageTimestamp(group.latestTimestampMs),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = cc.textPrimary
                    )
                    Text(
                        formatRelativeTime(group.latestTimestampMs),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = cc.textMuted
                    )
                }

                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = "Open issue details",
                    tint = cc.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Detailed Crashlytics-style Error / Issue Inspector.
 */
@Composable
private fun LogIssueDetailView(
    group: LogGroup,
    allGroups: List<LogGroup>,
    selectedOccurrenceId: Long?,
    onSelectOccurrence: (Long) -> Unit,
    onSelectGroup: (LogGroup) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val clipboard = LocalClipboardManager.current
    var showAppFramesOnly by remember { mutableStateOf(false) }
    var copiedFeedback by remember { mutableStateOf(false) }

    val levelColor = when (group.level) {
        AppLogLevel.ERROR -> Color(0xFFEF4444)
        AppLogLevel.WARN -> Color(0xFFF59E0B)
        AppLogLevel.INFO -> cc.agentRight
    }

    val currentIndex = remember(allGroups, group) { allGroups.indexOfFirst { it.key == group.key } }
    val prevGroup = remember(allGroups, currentIndex) { if (currentIndex > 0) allGroups[currentIndex - 1] else null }
    val nextGroup = remember(allGroups, currentIndex) { if (currentIndex >= 0 && currentIndex < allGroups.size - 1) allGroups[currentIndex + 1] else null }

    val activeOccurrence = remember(group, selectedOccurrenceId) {
        group.entries.find { it.id == selectedOccurrenceId } ?: group.entries.first()
    }

    val stackFrames = remember(group.errorDetails) {
        if (group.errorDetails != null) parseStackTraceFrames(group.errorDetails) else emptyList()
    }
    val filteredStackFrames = remember(stackFrames, showAppFramesOnly) {
        if (showAppFramesOnly) stackFrames.filter { it.isAppFrame || it.isHeader } else stackFrames
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Navigation Header Bar
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
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = cc.panel,
                        contentColor = cc.textPrimary
                    ),
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to list",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Issues List", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
                }

                Text(
                    "Issue #${(currentIndex + 1).coerceAtLeast(1)} of ${allGroups.size}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = cc.textMuted
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Prev / Next issue
                IconButton(
                    onClick = { prevGroup?.let { onSelectGroup(it) } },
                    enabled = prevGroup != null,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous issue",
                        tint = if (prevGroup != null) cc.textPrimary else cc.textMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { nextGroup?.let { onSelectGroup(it) } },
                    enabled = nextGroup != null,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next issue",
                        tint = if (nextGroup != null) cc.textPrimary else cc.textMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(4.dp))

                // Copy Diagnostic Payload
                Button(
                    onClick = {
                        val payload = buildString {
                            appendLine("=== CRASHLYTICS ERROR REPORT ===")
                            appendLine("Component: ${group.componentTitle}")
                            appendLine("Exception: ${group.exceptionName}")
                            appendLine("Summary: ${group.summaryMessage}")
                            appendLine("Level: ${group.level.name}")
                            appendLine("Total Events: ${group.count}")
                            appendLine("First Occurrence: ${formatMessageTimestamp(group.firstTimestampMs)}")
                            appendLine("Latest Occurrence: ${formatMessageTimestamp(group.latestTimestampMs)}")
                            appendLine("Selected Occurrence ID: ${activeOccurrence.id} at ${formatMessageTimestamp(activeOccurrence.timestampMs)}")
                            appendLine("\n--- STACK TRACE / DETAILS ---")
                            appendLine(group.errorDetails ?: group.summaryMessage)
                        }
                        clipboard.setText(AnnotatedString(payload))
                        copiedFeedback = true
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cc.panelAlt,
                        contentColor = cc.textPrimary
                    ),
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (copiedFeedback) "Copied!" else "Copy Issue Payload",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    )
                }
            }
        }

        // Main Crashlytics Issue Header Banner
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cc.panel,
            border = BorderStroke(
                1.dp,
                if (group.level == AppLogLevel.ERROR) levelColor.copy(alpha = 0.4f) else cc.border.copy(alpha = 0.6f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header tags & badges
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = levelColor.copy(alpha = 0.15f),
                            border = BorderStroke(0.75.dp, levelColor.copy(alpha = 0.4f))
                        ) {
                            Text(
                                group.level.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                color = levelColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = cc.panelAlt,
                            border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f))
                        ) {
                            Text(
                                group.componentTitle,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                color = cc.textMuted,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        "Latest: ${formatMessageTimestamp(group.latestTimestampMs)} (${formatRelativeTime(group.latestTimestampMs)})",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                        color = cc.textMuted
                    )
                }

                // Exception signature & message
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            group.exceptionName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 15.sp
                            ),
                            color = if (group.level == AppLogLevel.ERROR) Color(0xFFFCA5A5) else cc.textPrimary
                        )
                        if (group.summaryMessage != group.exceptionName) {
                            Text(
                                group.summaryMessage,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                color = cc.textMuted
                            )
                        }
                    }
                }

                // Metrics Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    StatBox(
                        title = "TOTAL EVENTS",
                        value = "${group.count}",
                        subValue = if (group.count > 1) "Repeated failure" else "Single occurrence",
                        modifier = Modifier.weight(1f)
                    )
                    StatBox(
                        title = "FIRST OCCURRED",
                        value = formatMessageTimestamp(group.firstTimestampMs),
                        subValue = formatRelativeTime(group.firstTimestampMs),
                        modifier = Modifier.weight(1.2f)
                    )
                    StatBox(
                        title = "LATEST OCCURRED",
                        value = formatMessageTimestamp(group.latestTimestampMs),
                        subValue = formatRelativeTime(group.latestTimestampMs),
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }
        }

        // Timeline of Occurrences / Event Picker
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "TIMELINE OF OCCURRENCES (${group.entries.size})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp,
                            fontSize = 10.5.sp
                        ),
                        color = cc.textMuted
                    )
                    Text(
                        "Selected: Event #${group.entries.indexOfFirst { it.id == activeOccurrence.id } + 1} (${formatMessageTimestamp(activeOccurrence.timestampMs)})",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        color = cc.accent
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    group.entries.forEachIndexed { idx, entry ->
                        val isSelected = entry.id == activeOccurrence.id
                        val isLatest = idx == 0
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) cc.accent.copy(alpha = 0.15f) else cc.panelAlt,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) cc.accent else cc.border.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onSelectOccurrence(entry.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    if (isLatest) "#${group.entries.size - idx} (Latest)" else "#${group.entries.size - idx}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 11.sp
                                    ),
                                    color = if (isSelected) cc.accent else cc.textPrimary
                                )
                                Text(
                                    formatMessageTimestamp(entry.timestampMs),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp
                                    ),
                                    color = if (isSelected) cc.accent.copy(alpha = 0.9f) else cc.textMuted
                                )
                            }
                        }
                    }
                }
            }
        }

        // Stack Trace & Error Body Section
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0A0A0E),
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.7f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header with App Frames Toggle & Copy Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "STACK TRACE & LOG FRAMES",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                fontSize = 11.sp
                            ),
                            color = Color(0xFFF9FAFB)
                        )
                        if (stackFrames.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF1E293B)
                            ) {
                                Text(
                                    "${filteredStackFrames.size} frames",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = Color(0xFF94A3B8),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (stackFrames.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AestheticSwitch(
                                    checked = showAppFramesOnly,
                                    onCheckedChange = { showAppFramesOnly = it }
                                )
                                Text(
                                    "App Frames Only",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = Color(0xFF9CA3AF)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                val text = activeOccurrence.errorDetails ?: activeOccurrence.message
                                clipboard.setText(AnnotatedString(text))
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy stack trace",
                                tint = Color(0xFF9CA3AF),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF1E293B))

                // Frame Renderer
                SelectionContainer {
                    if (group.errorDetails != null && filteredStackFrames.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp, max = 460.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            filteredStackFrames.forEach { frame ->
                                StackFrameRow(frame = frame, cc = cc)
                            }
                        }
                    } else {
                        // Fallback plain message/details
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 100.dp, max = 300.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = activeOccurrence.errorDetails ?: activeOccurrence.message,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp
                                ),
                                color = Color(0xFFE5E7EB)
                            )
                        }
                    }
                }
            }
        }

        // Diagnostic Environment & Runtime Snapshot
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "RUNTIME ENVIRONMENT & DIAGNOSTICS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        fontSize = 10.5.sp
                    ),
                    color = cc.textMuted
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EnvItem(
                        label = "OS",
                        value = "${System.getProperty("os.name") ?: "Unknown"} (${System.getProperty("os.arch") ?: ""})",
                        modifier = Modifier.weight(1f)
                    )
                    EnvItem(
                        label = "JVM / Runtime",
                        value = "${System.getProperty("java.version") ?: "JVM"} (${System.getProperty("java.vendor") ?: ""})",
                        modifier = Modifier.weight(1.2f)
                    )
                    EnvItem(
                        label = "Heap Memory",
                        value = "${Runtime.getRuntime().totalMemory() / (1024 * 1024)}MB / ${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatBox(
    title: String,
    value: String,
    subValue: String,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cc.panelAlt,
        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = cc.textMuted
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ),
                color = cc.textPrimary
            )
            Text(
                subValue,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = cc.textMuted.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun EnvItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = cc.panelAlt,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = cc.textMuted)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                color = cc.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StackFrameRow(
    frame: StackFrame,
    cc: com.dialex.theme.CcPalette
) {
    if (frame.isHeader) {
        Text(
            frame.raw,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            ),
            color = Color(0xFFFCA5A5),
            modifier = Modifier.padding(vertical = 4.dp)
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (frame.isAppFrame) Color(0xFF1E293B).copy(alpha = 0.4f) else Color.Transparent,
                    shape = RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "at ",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp
                ),
                color = Color(0xFF64748B)
            )
            Text(
                frame.method,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    fontWeight = if (frame.isAppFrame) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (frame.isAppFrame) Color(0xFF38BDF8) else Color(0xFF94A3B8)
            )
            if (frame.isAppFrame) {
                Spacer(Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = Color(0xFF0284C7).copy(alpha = 0.25f)
                ) {
                    Text(
                        "APP",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color(0xFF38BDF8),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    color: Color? = null,
    onClick: () -> Unit
) {
    val cc = LocalCcColors.current
    val effectiveColor = color ?: cc.accent
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isSelected) effectiveColor.copy(alpha = 0.15f) else Color.Transparent,
        border = BorderStroke(
            0.75.dp,
            if (isSelected) effectiveColor.copy(alpha = 0.6f) else cc.border.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 11.sp
            ),
            color = if (isSelected) effectiveColor else cc.textMuted,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun SubtleSegmentedControl(
    options: List<Pair<Boolean, String>>,
    selected: Boolean,
    onSelect: (Boolean) -> Unit
) {
    val cc = LocalCcColors.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cc.panelAlt,
        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.4f)),
        modifier = Modifier.height(34.dp)
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { (value, label) ->
                val isCurrent = selected == value
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isCurrent) cc.panel else Color.Transparent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelect(value) }
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isCurrent) cc.textPrimary else cc.textMuted,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLogsPlaceholder() {
    val cc = LocalCcColors.current
    Box(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("No events match the current filter", color = cc.textMuted)
    }
}

@Composable
private fun RawLogLine(log: AppLogEntry) {
    val cc = LocalCcColors.current
    val levelColor = when (log.level) {
        AppLogLevel.ERROR -> Color(0xFFEF4444)
        AppLogLevel.WARN -> Color(0xFFF59E0B)
        AppLogLevel.INFO -> Color(0xFF60A5FA)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            formatMessageTimestamp(log.timestampMs),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
            color = Color(0xFF6B7280)
        )
        Text(
            log.level.label.padEnd(5),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
            color = levelColor
        )
        if (log.tag.isNotBlank()) {
            Text(
                "[${log.tag}]",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
                color = Color(0xFF9CA3AF)
            )
        }
        Text(
            log.message,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.5.sp),
            color = Color(0xFFF3F4F6),
            modifier = Modifier.weight(1f)
        )
    }
}

private fun parseStackTraceFrames(stackTrace: String): List<StackFrame> {
    return stackTrace.lines().mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("at ")) {
            val content = trimmed.removePrefix("at ").trim()
            val isApp = content.contains("com.dialex")
            val openParen = content.indexOf('(')
            val method = if (openParen != -1) content.substring(0, openParen) else content
            val fileAndLine = if (openParen != -1 && content.endsWith(")")) {
                content.substring(openParen + 1, content.length - 1)
            } else null
            StackFrame(
                raw = line,
                method = content,
                fileAndLine = fileAndLine,
                isAppFrame = isApp
            )
        } else if (trimmed.contains("Exception") || trimmed.contains("Error") || trimmed.startsWith("Caused by:")) {
            StackFrame(
                raw = line,
                method = trimmed,
                fileAndLine = null,
                isAppFrame = true,
                isHeader = true
            )
        } else null
    }
}
