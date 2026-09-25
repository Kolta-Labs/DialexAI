@file:Suppress("DEPRECATION")

package com.dialex.presentation.chat

import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import kotlinx.coroutines.Job
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.key.*
import com.dialex.util.formatMessageTimestamp
import com.dialex.util.formatTokenCount
import com.dialex.service.launchCliLoginTerminal
import com.dialex.service.SupportedCliTools
import com.dialex.service.CliToolDescriptor
import com.dialex.ui.cliauth.CliAuthDialog
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import com.dialex.ui.GradientButton
import com.dialex.ui.ThemedTooltipBox
import com.dialex.export.exportMemoFileName
import com.dialex.export.resolveExportFileName
import com.dialex.export.toDeliverableMarkdown
import com.dialex.export.toExecutiveMemorandumHtml
import com.dialex.export.toMarkdown
import com.dialex.export.toSummaryMarkdown
import com.dialex.model.DeliverableFormat
import com.dialex.model.DiscussionArtifact
import com.dialex.model.ConsensusEvaluationResult
import com.dialex.model.ConsensusStrategy
import com.dialex.orchestrator.ConsensusDetector
import com.dialex.util.writeTextFile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Provider
import com.dialex.model.RoundMode
import com.dialex.model.brandName
import com.dialex.model.label
import io.github.koltalabs.kolt.utils.state.AsyncState
import com.dialex.presentation.setup.TokenWarningLevel
import com.dialex.domain.model.TensionStatus
import com.dialex.theme.CcPalette
import com.dialex.theme.LocalCcColors
import com.dialex.ui.MarkdownText
import com.dialex.presentation.workspace.WorkspaceHeader
import com.dialex.service.isPlatformCliSupported
import com.dialex.service.checkPlatformCliLogins
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class SearchMatchOccurrence(
    val transcriptIndex: Int,
    val occurrenceIndexInMessage: Int,
    val matchCharOffset: Int = 0
)

private fun calculateMatchScrollOffset(msgContent: String, matchCharOffset: Int): Int {
    if (matchCharOffset <= 0 || msgContent.isBlank()) return 0
    val textBeforeMatch = msgContent.take(matchCharOffset.coerceAtMost(msgContent.length))
    val estimatedLines = textBeforeMatch.split('\n').sumOf { line ->
        (line.length / 70).coerceAtLeast(1)
    }
    val linePixelHeight = 24
    val headerPaddingPx = 48
    val viewportContextPx = 80
    val targetOffset = headerPaddingPx + (estimatedLines * linePixelHeight) - viewportContextPx
    return targetOffset.coerceAtLeast(0)
}

private val shownArtifactToastedDiscussions = mutableSetOf<String>()

private fun Discussion.labelFor(seatId: String, fallbackProvider: Provider): String {
    val agent = config.agents.firstOrNull { it.id == seatId }
    return agent?.label() ?: config.agents.firstOrNull { it.provider == fallbackProvider }?.label() ?: fallbackProvider.brandName()
}

private fun Discussion.labelFor(msg: DebateMessage): String {
    if (msg.authorDisplayName.isNotBlank()) return msg.authorDisplayName
    return labelFor(msg.seatId, msg.provider)
}

private fun Discussion.labelFor(p: Provider): String =
    config.agents.firstOrNull { it.provider == p }?.label() ?: p.brandName()


internal fun Discussion.effectiveRoundFor(artifact: DiscussionArtifact): Int? {
    if (artifact.round != null && artifact.round > 0) return artifact.round
    val rMatch = Regex("""_r(\d+)|\(Round (\d+)\)|Round (\d+)""").find(artifact.id + " " + artifact.name + " " + artifact.timestamp)
    if (rMatch != null) {
        val num = rMatch.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
        if (num != null) return num
    }
    if (artifact.type.equals("Reference Document", ignoreCase = true) || artifact.id.startsWith("doc_")) {
        return null
    }
    if (transcript.isEmpty()) return null
    if (artifact.timestampMs > 0L) {
        val matchingTurn = transcript.findLast { it.timestampMs in 1L..artifact.timestampMs + 60_000L }
        if (matchingTurn != null) return matchingTurn.round
        val nextTurn = transcript.firstOrNull { it.timestampMs >= artifact.timestampMs && it.timestampMs > 0L }
        if (nextTurn != null) return nextTurn.round
    }
    return transcript.firstOrNull()?.round ?: 1
}

fun Discussion.resolveAllArtifacts(snapshotRound: Int? = null): List<DiscussionArtifact> {
    val list = mutableListOf<DiscussionArtifact>()

    // 1. Explicitly generated/saved artifacts (e.g. Action Plan Deliverable, Decision Matrix Deliverable)
    list.addAll(artifacts)

    val roundTag = if (snapshotRound != null && snapshotRound > 0) " (Round $snapshotRound)" else ""
    val roundIdSuffix = if (snapshotRound != null && snapshotRound > 0) "_r$snapshotRound" else ""
    val roundMsg = if (snapshotRound != null) transcript.findLast { it.round == snapshotRound } else transcript.lastOrNull()
    val generatedTimeMs = roundMsg?.timestampMs ?: if (updatedAt > 0L) updatedAt else System.currentTimeMillis()
    val timeLabel = formatMessageTimestamp(generatedTimeMs)
    val effectiveRound = snapshotRound ?: transcript.maxOfOrNull { it.round }

    // 2. Discussion Summary (standard automated artifact)
    val sumId = if (snapshotRound != null && snapshotRound > 0) "art_sum$roundIdSuffix" else "art_sum"
    if (list.none { it.id == sumId || (snapshotRound == null && (it.type.equals("summary", ignoreCase = true) || it.id.startsWith("art_sum"))) }) {
        if (summary != null || conclusion != null) {
            val content = toSummaryMarkdown()
            list.add(
                DiscussionArtifact(
                    id = sumId,
                    name = if (snapshotRound != null && snapshotRound > 0) "Discussion Summary (Round $snapshotRound)" else resolveExportFileName("summary"),
                    type = "Summary",
                    format = "md",
                    content = content,
                    timestamp = timeLabel,
                    timestampMs = generatedTimeMs,
                    sizeBytes = content.encodeToByteArray().size.toLong(),
                    round = effectiveRound
                )
            )
        }
    }

    // 5. Full Transcript
    val traId = if (snapshotRound != null && snapshotRound > 0) "art_tra$roundIdSuffix" else "art_tra"
    if (list.none { it.id == traId || (snapshotRound == null && (it.type.equals("transcript", ignoreCase = true) || it.id.startsWith("art_tra"))) }) {
        if (transcript.isNotEmpty()) {
            val content = toMarkdown()
            list.add(
                DiscussionArtifact(
                    id = traId,
                    name = if (snapshotRound != null && snapshotRound > 0) "Full Transcript (Round $snapshotRound)" else resolveExportFileName("transcript"),
                    type = "Transcript",
                    format = "md",
                    content = content,
                    timestamp = timeLabel,
                    timestampMs = generatedTimeMs,
                    sizeBytes = content.encodeToByteArray().size.toLong(),
                    round = effectiveRound
                )
            )
        }
    }

    // Always include attached reference documents for full grounding visibility in artifacts
    attachedFiles.forEach { f ->
        if (list.none { it.id == "doc_${f.id}" || it.name == f.name }) {
            list.add(
                DiscussionArtifact(
                    id = "doc_${f.id}",
                    name = f.name,
                    type = "Reference Document",
                    format = f.name.substringAfterLast('.', "txt"),
                    content = f.content,
                    timestamp = f.sizeLabel.ifBlank { "Attached" },
                    timestampMs = 0L,
                    sizeBytes = f.content.encodeToByteArray().size.toLong()
                )
            )
        }
    }

    return list
}

private val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatState,
    onIntent: (ChatIntent) -> Unit,
    onBack: (() -> Unit)? = null,
    projectName: String? = null,
    onEditSetup: (() -> Unit)? = null,
    onExportMarkdown: (() -> Unit)? = null,
    onExportMemo: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenTokenSettings: (() -> Unit)? = null,
    isCompact: Boolean = false,
    externalArtifactsPaneOpen: Boolean? = null,
    onExternalToggleArtifacts: (() -> Unit)? = null,
    externalSummaryDialogOpen: Boolean? = null,
    onExternalToggleSummary: (() -> Unit)? = null,
    externalSearchQuery: String? = null,
    onExternalSearchQueryChange: ((String) -> Unit)? = null,
    externalSearchActive: Boolean? = null,
    onExternalToggleSearch: ((Boolean) -> Unit)? = null,
    externalSearchNextTrigger: Int = 0,
    externalSearchPrevTrigger: Int = 0,
    onSearchMatchesChanged: ((count: Int, currentIndex: Int) -> Unit)? = null,
    snackbarMessage: String? = null,
    onClearSnackbar: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cc = LocalCcColors.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val toastScope = rememberCoroutineScope()
    var activeAuthCliTool by remember { mutableStateOf<CliToolDescriptor?>(null) }
    var activeCliLogins by remember {
        mutableStateOf(if (isPlatformCliSupported()) checkPlatformCliLogins() else emptyMap())
    }

    LaunchedEffect(Unit) {
        if (isPlatformCliSupported()) {
            activeCliLogins = checkPlatformCliLogins()
        }
    }

    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage, duration = SnackbarDuration.Short)
            onClearSnackbar?.invoke()
        }
    }

    val discussion = state.discussion

    if (discussion == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.loadAsync is AsyncState.Loading) {
                CircularProgressIndicator(color = cc.textPrimary)
            } else {
                Text("Error loading discussion", color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }

    val nextSpeakerProvider = state.nextSpeakerProvider
    val handoff = state.handoffState
    val nextSpeakerAgent = discussion.config.agents.find { it.provider == nextSpeakerProvider }

    var inputPrompt by remember { mutableStateOf("") }
    var selectedAgentMode by remember { mutableStateOf("All Agents") }
    var agentModeDropdownOpen by remember { mutableStateOf(false) }
    var isDebateErrorDialogOpen by remember { mutableStateOf(false) }
    var isContinueDebateDialogOpen by remember { mutableStateOf(false) }

    // Chat search state management
    var internalSearchQuery by remember { mutableStateOf("") }
    var internalSearchActive by remember { mutableStateOf(false) }
    var currentMatchIndex by remember(discussion.id) { mutableStateOf(0) }

    val effectiveSearchQuery = externalSearchQuery ?: internalSearchQuery
    val effectiveSearchActive = externalSearchActive ?: internalSearchActive
    val onEffectiveSearchQueryChange: (String) -> Unit = { query ->
        if (onExternalSearchQueryChange != null) onExternalSearchQueryChange(query) else internalSearchQuery = query
    }
    val onEffectiveToggleSearch: (Boolean) -> Unit = { active ->
        if (onExternalToggleSearch != null) onExternalToggleSearch(active) else {
            internalSearchActive = active
            if (!active) internalSearchQuery = ""
        }
    }

    // Occurrences of search query across all transcript turns
    val searchMatches = remember(discussion.transcript, effectiveSearchQuery) {
        val q = effectiveSearchQuery.trim()
        if (q.isBlank()) {
            emptyList<SearchMatchOccurrence>()
        } else {
            val matches = mutableListOf<SearchMatchOccurrence>()
            discussion.transcript.forEachIndexed { idx, msg ->
                val agentLabel = discussion.labelFor(msg)
                val agentConfig = discussion.config.agents.find { it.id == msg.seatId } ?: discussion.config.agents.find { it.provider == msg.agentId }
                val modelName = agentConfig?.model ?: ""
                val personaRole = agentConfig?.role ?: ""

                var countInContent = 0
                var sIdx = msg.content.indexOf(q, 0, ignoreCase = true)
                while (sIdx != -1) {
                    matches.add(SearchMatchOccurrence(transcriptIndex = idx, occurrenceIndexInMessage = countInContent, matchCharOffset = sIdx))
                    countInContent++
                    sIdx = msg.content.indexOf(q, sIdx + q.length.coerceAtLeast(1), ignoreCase = true)
                }

                if (countInContent == 0) {
                    if (agentLabel.contains(q, ignoreCase = true) ||
                        modelName.contains(q, ignoreCase = true) ||
                        personaRole.contains(q, ignoreCase = true) ||
                        msg.agentId.name.contains(q, ignoreCase = true) ||
                        (msg.isUserComment && "Human User Comment Observer".contains(q, ignoreCase = true))) {
                        matches.add(SearchMatchOccurrence(transcriptIndex = idx, occurrenceIndexInMessage = 0, matchCharOffset = 0))
                    }
                }
            }
            matches
        }
    }

    // Synchronize match count and current index with parent/header
    LaunchedEffect(searchMatches.size, currentMatchIndex) {
        if (searchMatches.isNotEmpty() && currentMatchIndex >= searchMatches.size) {
            currentMatchIndex = 0
        }
        onSearchMatchesChanged?.invoke(
            searchMatches.size,
            if (searchMatches.isEmpty()) 0 else currentMatchIndex
        )
    }

    // Next trigger from header
    LaunchedEffect(externalSearchNextTrigger) {
        if (externalSearchNextTrigger > 0 && searchMatches.isNotEmpty()) {
            currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
        }
    }

    // Prev trigger from header
    LaunchedEffect(externalSearchPrevTrigger) {
        if (externalSearchPrevTrigger > 0 && searchMatches.isNotEmpty()) {
            currentMatchIndex = (currentMatchIndex - 1 + searchMatches.size) % searchMatches.size
        }
    }

    // Reset currentMatchIndex when search query changes
    LaunchedEffect(effectiveSearchQuery) {
        currentMatchIndex = 0
    }

    // Unified scroll to active match whenever currentMatchIndex or effectiveSearchQuery changes
    LaunchedEffect(currentMatchIndex, effectiveSearchQuery) {
        if (effectiveSearchQuery.isNotBlank() && searchMatches.isNotEmpty() && currentMatchIndex in searchMatches.indices) {
            val match = searchMatches[currentMatchIndex]
            val headerOffset = 1 + (if (state.invalidFolders.isNotEmpty()) 1 else 0)
            val targetItemIndex = (headerOffset + match.transcriptIndex).coerceAtLeast(0)
            val msg = discussion.transcript.getOrNull(match.transcriptIndex)
            val scrollOffset = if (msg != null) calculateMatchScrollOffset(msg.content, match.matchCharOffset) else 0
            listState.animateScrollToItem(targetItemIndex, scrollOffset = scrollOffset)
        }
    }

    val onNextSearchMatch: () -> Unit = {
        if (searchMatches.isNotEmpty()) {
            currentMatchIndex = (currentMatchIndex + 1) % searchMatches.size
        }
    }

    val onPrevSearchMatch: () -> Unit = {
        if (searchMatches.isNotEmpty()) {
            currentMatchIndex = (currentMatchIndex - 1 + searchMatches.size) % searchMatches.size
        }
    }

    val hasDeliverable = (discussion.deliverable != null || discussion.conclusion != null || discussion.summary != null || state.generatingDeliverableFormat != null)

    val isSearchEngaged = effectiveSearchActive || effectiveSearchQuery.isNotBlank()

    // Scroll all the way to bottom on initial load and keep following live turns (unless actively searching)
    var hasScrolledToBottomInitially by remember(discussion.id) { mutableStateOf(false) }
    LaunchedEffect(discussion.id, discussion.transcript.size, listState.layoutInfo.totalItemsCount, nextSpeakerProvider, handoff, discussion.handoffPrompt, discussion.conclusion) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0 && !isSearchEngaged) {
            if (!hasScrolledToBottomInitially && (discussion.transcript.isNotEmpty() || discussion.conclusion != null)) {
                listState.scrollToItem(count - 1)
                hasScrolledToBottomInitially = true
            } else if (hasScrolledToBottomInitially || discussion.status == DiscussionStatus.RUNNING) {
                listState.animateScrollToItem(count - 1)
            }
        }
    }

    val chatTypographyState = LocalChatTypographySettings.current
    val typographySettings by chatTypographyState
    val chatDisplayState = LocalChatDisplaySettings.current
    val chatDisplaySettings by chatDisplayState

    var internalArtifactsPaneOpen by remember { mutableStateOf(false) }
    val isArtifactsPaneOpen = externalArtifactsPaneOpen ?: internalArtifactsPaneOpen
    val totalArtifactsList = remember(discussion) { discussion.resolveAllArtifacts() }
    var lastSeenArtifactsCount by remember(discussion.id) { mutableStateOf(0) }
    val hasUnreadArtifacts = totalArtifactsList.size > lastSeenArtifactsCount && !isArtifactsPaneOpen

    val toggleArtifactsPane: () -> Unit = {
        val willOpen = !isArtifactsPaneOpen
        if (onExternalToggleArtifacts != null) onExternalToggleArtifacts() else internalArtifactsPaneOpen = willOpen
        if (willOpen) {
            lastSeenArtifactsCount = totalArtifactsList.size
        }
    }

    var internalSummaryDialogOpen by remember { mutableStateOf(false) }
    val isSummaryDialogOpen = externalSummaryDialogOpen ?: internalSummaryDialogOpen
    val toggleSummaryDialog: () -> Unit = {
        if (onExternalToggleSummary != null) onExternalToggleSummary() else internalSummaryDialogOpen = !internalSummaryDialogOpen
    }
    var hasAutoSavedForThisRun by remember(discussion.id) { mutableStateOf(false) }

    val systemLogs by com.dialex.logging.AppLogStore.logs.collectAsState()
    val latestAppError = remember(systemLogs) {
        systemLogs.findLast { it.level == com.dialex.logging.AppLogLevel.ERROR }?.message
    }

    // If discussion already has artifacts loaded from past runs, mark as toasted so toast never re-fires
    LaunchedEffect(discussion.id) {
        if (discussion.artifacts.isNotEmpty()) {
            shownArtifactToastedDiscussions.add(discussion.id)
        }
    }

    // Auto-save artifacts when discussion completes
    LaunchedEffect(discussion.status, discussion.id) {
        if (discussion.status.isCompleted && !hasAutoSavedForThisRun) {
            val out = discussion.config.output
            if (out.saveArtifacts) {
                hasAutoSavedForThisRun = true
                val newArtifacts = mutableListOf<DiscussionArtifact>()
                var savedDiskCount = 0
                val currentMaxRound = discussion.transcript.maxOfOrNull { it.round } ?: 1
                val lastTurn = discussion.transcript.findLast { it.round == currentMaxRound } ?: discussion.transcript.lastOrNull()
                val now = lastTurn?.timestampMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
                val timeLabel = formatMessageTimestamp(now)

                if (out.autoSaveSummary && (discussion.summary != null || discussion.conclusion != null)) {
                    val fn = discussion.resolveExportFileName("summary")
                    val content = discussion.toSummaryMarkdown()
                    if (out.outputFolder.isNotBlank() && writeTextFile(out.outputFolder, fn, content, out.appendMode)) {
                        savedDiskCount++
                    }
                    val sumId = "art_sum_r$currentMaxRound"
                    if (discussion.artifacts.none { it.id == sumId || it.name == fn }) {
                        newArtifacts.add(
                            DiscussionArtifact(
                                id = sumId,
                                name = fn,
                                type = "Summary",
                                format = "md",
                                content = content,
                                timestamp = timeLabel,
                                timestampMs = now,
                                sizeBytes = content.encodeToByteArray().size.toLong(),
                                round = currentMaxRound
                            )
                        )
                    }
                }

                if (out.autoSaveFullTranscript && discussion.transcript.isNotEmpty()) {
                    val fn = discussion.resolveExportFileName("transcript")
                    val content = discussion.toMarkdown()
                    if (out.outputFolder.isNotBlank() && writeTextFile(out.outputFolder, fn, content, out.appendMode)) {
                        savedDiskCount++
                    }
                    val traId = "art_tra_r$currentMaxRound"
                    if (discussion.artifacts.none { it.id == traId || it.name == fn }) {
                        newArtifacts.add(
                            DiscussionArtifact(
                                id = traId,
                                name = fn,
                                type = "Transcript",
                                format = "md",
                                content = content,
                                timestamp = timeLabel,
                                timestampMs = now,
                                sizeBytes = content.encodeToByteArray().size.toLong(),
                                round = currentMaxRound
                            )
                        )
                    }
                }

                if (out.autoSaveDeliverable && (discussion.deliverable != null || discussion.conclusion != null)) {
                    val formatLabel = discussion.config.deliverable.format.name.lowercase().replace('_', ' ')
                        .split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    val deliverableName = "$formatLabel Deliverable"
                    val fn = discussion.resolveExportFileName("deliverable")
                    val content = discussion.toDeliverableMarkdown()
                    if (out.outputFolder.isNotBlank() && writeTextFile(out.outputFolder, fn, content, out.appendMode)) {
                        savedDiskCount++
                    }
                    val delId = "art_del_r$currentMaxRound"
                    if (discussion.artifacts.none { it.id == delId || it.name == deliverableName }) {
                        newArtifacts.add(
                            DiscussionArtifact(
                                id = delId,
                                name = deliverableName,
                                type = "Deliverable",
                                format = "md",
                                content = content,
                                timestamp = timeLabel,
                                timestampMs = now,
                                sizeBytes = content.encodeToByteArray().size.toLong(),
                                round = currentMaxRound
                            )
                        )
                    }
                }

                if (newArtifacts.isNotEmpty()) {
                    onIntent(ChatIntent.SaveArtifacts(newArtifacts))
                }

                val msg = if (savedDiskCount > 0) {
                    "Auto-saved $savedDiskCount debate file(s) to ${out.outputFolder} & engine repository"
                } else if (newArtifacts.isNotEmpty()) {
                    "Auto-saved ${newArtifacts.size} artifact(s) to engine repository"
                } else null

                if (msg != null && shownArtifactToastedDiscussions.add(discussion.id)) {
                    toastScope.launch {
                        snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
                    }
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalToast provides { message ->
            toastScope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short) }
        },
    ) {
        BoxWithConstraints(
            modifier.fillMaxSize()
                .background(cc.bg)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.isMetaPressed || event.isCtrlPressed) &&
                        event.key == Key.F
                    ) {
                        onEffectiveToggleSearch(!effectiveSearchActive)
                        true
                    } else false
                }
        ) {
            val isWideDisplay = maxWidth >= 900.dp && !isCompact

            Column(Modifier.fillMaxSize()) {
                // Header on compact screens (wide-screen header is provided by App.kt)
                if (isCompact) {
                    WorkspaceHeader(
                        projectName = projectName,
                        discussionName = discussion.name.ifBlank { "Dialex Debate" },
                        status = discussion.status,
                        readingSettings = typographySettings,
                        onReadingSettingsChange = { chatTypographyState.value = it },
                        onBack = onBack,
                        onEditSetup = onEditSetup,
                        onExportMarkdown = onExportMarkdown,
                        onExportMemo = onExportMemo,
                        onToggleSummary = toggleSummaryDialog,
                        onOpenArtifacts = toggleArtifactsPane,
                        artifactsCount = totalArtifactsList.size,
                        hasUnreadArtifacts = hasUnreadArtifacts,
                        onRenameDiscussion = { newName -> onIntent(ChatIntent.RenameDiscussion(newName)) },
                        onOpenSettings = onOpenSettings ?: {},
                        searchQuery = effectiveSearchQuery,
                        onSearchQueryChange = onEffectiveSearchQueryChange,
                        isSearchActive = effectiveSearchActive,
                        onToggleSearch = onEffectiveToggleSearch,
                        searchMatchCount = searchMatches.size,
                        currentSearchMatchIndex = currentMatchIndex,
                        onNextSearchMatch = onNextSearchMatch,
                        onPrevSearchMatch = onPrevSearchMatch
                    )
                }

                // ── Main Aerated Transcript Area & Optional Summary Right Panel ───
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // Discussion Space (Transcript + Floating Bottom Input Bar + FAB + Snackbar)
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        // Fluid-responsive reading measure: expands gracefully with screen width, clamped at 880dp
                        val maxContentWidth = when {
                            maxWidth >= 1200.dp -> 880.dp
                            maxWidth >= 960.dp -> (maxWidth * 0.85f).coerceIn(780.dp, 880.dp)
                            maxWidth >= 768.dp -> (maxWidth * 0.92f).coerceIn(680.dp, 780.dp)
                            else -> maxWidth
                        }

                        LazyColumn(
                            state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentPadding = PaddingValues(top = 46.dp, bottom = 150.dp, start = 20.dp, end = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        item {
                            Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
                                ContextHeader(
                                    cc = cc,
                                    discussion = discussion,
                                    onRenameDiscussion = { onIntent(ChatIntent.RenameDiscussion(it)) },
                                    onRegenerateTitle = { onIntent(ChatIntent.GenerateSummaryTitle) },
                                    typographySettings = typographySettings
                                )
                            }
                        }

                        if (state.invalidFolders.isNotEmpty()) {
                            item(key = "invalid_folders_blocking_banner") {
                                Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(Icons.Outlined.FolderOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                                                Text(
                                                    "Workspace Folder Removed or Invalid — Processing Blocked",
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                            Text(
                                                "One or more workspace folders attached to this discussion were removed or are inaccessible on disk. Discussion turns, resumes, and user comments are blocked until resolved.",
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                state.invalidFolders.forEach { (folder, reason) ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                folder.path,
                                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                                                color = MaterialTheme.colorScheme.error
                                                            )
                                                            Text(
                                                                reason,
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                                            )
                                                        }
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            TextButton(
                                                                onClick = { onIntent(ChatIntent.RetryFolderValidation) },
                                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(26.dp)
                                                            ) {
                                                                Text("Retry", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                                                            }
                                                            TextButton(
                                                                onClick = { onIntent(ChatIntent.RemoveInvalidFolder(folder.path)) },
                                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                                modifier = Modifier.height(26.dp)
                                                            ) {
                                                                Text("Remove", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.error)
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Pre-debate attached reference documents (rendered at top)
                        val preDebateArtifacts = discussion.artifacts.filter {
                            discussion.effectiveRoundFor(it) == null && it.id !in discussion.dismissedArtifactIds
                        }.sortedWith(compareBy<DiscussionArtifact> { if (it.timestampMs > 0L) it.timestampMs else 0L }.thenBy { it.id })

                        if (chatDisplaySettings.showArtifactGenerationStrips && preDebateArtifacts.isNotEmpty()) {
                            items(preDebateArtifacts.size, key = { "pre_debate_art_${preDebateArtifacts[it].id}" }) { idx ->
                                val art = preDebateArtifacts[idx]
                                Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
                                    ArtifactStrip(
                                        cc = cc,
                                        artifact = art,
                                        onView = toggleArtifactsPane,
                                        onDismiss = { onIntent(ChatIntent.DismissArtifactBanner(art.id)) }
                                    )
                                }
                            }
                        }

                        val primaryProvider = discussion.config.primary.provider
                        val compactionThreshold = 20

                        // All turns in strict chronological order
                        items(discussion.transcript.size) { i ->
                            val msg = discussion.transcript[i]
                            val prevMsg = if (i > 0) discussion.transcript[i - 1] else null
                            val nextMsg = if (i + 1 < discussion.transcript.size) discussion.transcript[i + 1] else null

                            val msgMatches = searchMatches.filter { it.transcriptIndex == i }
                            val isMatch = msgMatches.isNotEmpty()
                            val activeMatch = searchMatches.getOrNull(currentMatchIndex)
                            val isActiveMatch = activeMatch != null && activeMatch.transcriptIndex == i
                            val activeOccurrenceInMsg = if (isActiveMatch) activeMatch.occurrenceIndexInMessage else -1
                            val matchLabel = if (isActiveMatch) {
                                "MATCH ${currentMatchIndex + 1} of ${searchMatches.size}"
                            } else if (isMatch) {
                                if (msgMatches.size > 1) "${msgMatches.size} MATCHES" else "MATCH"
                            } else null

                            if (chatDisplaySettings.showCompactionPills && i > 0 && i % compactionThreshold == 0) {
                                CompactionInfoBar(
                                    cc = cc,
                                    startTurn = (i - compactionThreshold + 1).coerceAtLeast(1),
                                    endTurn = i,
                                    summaryText = discussion.summary?.takeIf { it.isNotBlank() }
                                )
                            }

                            val roundAgentTurns = discussion.transcript.take(i)
                                .filter { it.round == msg.round && !it.isUserComment && !it.isModeratorIntervention }
                            val seatIndex = if (discussion.config.agents.isNotEmpty()) {
                                roundAgentTurns.size % discussion.config.agents.size
                            } else {
                                0
                            }
                            val agentConfig = discussion.config.agents.find { it.id == msg.seatId }
                                ?: discussion.config.agents.getOrNull(seatIndex)
                                ?: discussion.config.agents.find { it.provider == msg.agentId }
                                ?: discussion.config.primary
                            val seatLabel = if (seatIndex >= 0) "Agent ${seatIndex + 1}" else if (msg.agentId == primaryProvider) "Primary" else ""
                            val memberDisplayName = discussion.labelFor(msg)

                            Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
                                if (prevMsg == null || msg.round != prevMsg.round) {
                                    RoundDivider(
                                        round = msg.round,
                                        totalRounds = discussion.config.maxRounds,
                                        cc = cc
                                    )
                                }

                                AeratedMessageItem(
                                    cc = cc,
                                    agentName = if (msg.isUserComment) "You (Observer)" else memberDisplayName,
                                    seatLabel = if (msg.isUserComment) "" else seatLabel,
                                    modelName = if (msg.isUserComment) "Human" else agentConfig.model,
                                    personaRole = if (msg.isUserComment) "User Comment" else agentConfig.role.takeIf { it.isNotBlank() },
                                    meta = if (msg.isError) "error" else "round ${msg.round}",
                                    content = msg.content,
                                    provider = msg.agentId,
                                    isPrimary = msg.agentId == primaryProvider,
                                    memberIndex = seatIndex,
                                    isError = msg.isError,
                                    tokensIn = msg.tokensIn,
                                    tokensOut = msg.tokensOut,
                                    typographySettings = typographySettings,
                                    isModeratorIntervention = msg.isModeratorIntervention,
                                    isUserComment = msg.isUserComment,
                                    isLoopRecovered = msg.isLoopRecovered,
                                    isStalledConcession = msg.isStalledConcession,
                                    timestampMs = msg.timestampMs,
                                    searchQuery = if (isMatch) effectiveSearchQuery else null,
                                    activeOccurrenceIndex = activeOccurrenceInMsg,
                                    isSearchMatch = isMatch,
                                    isActiveSearchMatch = isActiveMatch,
                                    matchIndexLabel = matchLabel,
                                    isCliLoggedIn = run {
                                        val bin = extractCliBinary(msg.content, msg.provider)
                                        activeCliLogins[bin] == true || activeCliLogins[msg.provider.name] == true
                                    },
                                    onLoginCli = { binary ->
                                        val tool = SupportedCliTools.firstOrNull { it.binaryName == binary || it.name.contains(binary, ignoreCase = true) || it.id == binary }
                                        val cmd = tool?.loginCommand ?: binary
                                        launchCliLoginTerminal(cmd)
                                        toastScope.launch {
                                            snackbarHostState.showSnackbar("Terminal opened for '$cmd' login. Complete login in Terminal window.")
                                            repeat(30) {
                                                delay(2000)
                                                if (isPlatformCliSupported()) {
                                                    val updated = checkPlatformCliLogins()
                                                    activeCliLogins = updated
                                                    if (updated[binary] == true || updated[tool?.name.orEmpty()] == true || updated[tool?.provider?.name.orEmpty()] == true) {
                                                        snackbarHostState.showSnackbar("✓ $binary logged in successfully! Click Resume to continue.")
                                                        return@launch
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onRetry = {
                                        if (isPlatformCliSupported()) {
                                            activeCliLogins = checkPlatformCliLogins()
                                        }
                                        onIntent(ChatIntent.Resume)
                                    }
                                )

                                // Check if this message is the end of a milestone round with a saved deliverable or summary
                                val isEndOfRound = nextMsg == null || nextMsg.round != msg.round || nextMsg.isUserComment
                                val isFinalTurn = nextMsg == null
                                val milestoneRound = msg.round
                                val roundArtifacts = discussion.artifacts
                                    .filter { discussion.effectiveRoundFor(it) == milestoneRound }
                                    .sortedWith(compareBy<DiscussionArtifact> { if (it.timestampMs > 0L) it.timestampMs else 0L }.thenBy { it.id })
                                val maxCompletedAgentRound = discussion.transcript.filterNot { it.isUserComment || it.isError }.maxOfOrNull { it.round } ?: 1
                                val hasMilestoneDeliverable = roundArtifacts.any {
                                    it.type.equals("deliverable", ignoreCase = true) ||
                                    it.type.equals("summary", ignoreCase = true) ||
                                    it.id.startsWith("art_sum") ||
                                    it.id.startsWith("art_del")
                                } || (isFinalTurn && !msg.isUserComment && hasDeliverable) ||
                                  (isEndOfRound && !msg.isUserComment && milestoneRound == maxCompletedAgentRound && hasDeliverable)

                                if (isEndOfRound && !msg.isUserComment && (hasMilestoneDeliverable || (isFinalTurn && hasDeliverable))) {
                                    Spacer(Modifier.height(10.dp))
                                    CheckpointDivider(
                                        checkpointNumber = milestoneRound,
                                        cc = cc
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    DeliverableBlock(
                                        cc = cc,
                                        discussion = discussion,
                                        typographySettings = typographySettings,
                                        milestoneRound = if (!isFinalTurn || roundArtifacts.any { it.round == milestoneRound || it.id.contains("_r$milestoneRound") }) milestoneRound else null,
                                        generatingFormat = if (isFinalTurn) state.generatingDeliverableFormat else null,
                                        onOpenSummary = toggleSummaryDialog,
                                        onOpenArtifacts = toggleArtifactsPane,
                                        onGenerateFormat = if (isFinalTurn) { fmt -> onIntent(ChatIntent.GenerateAlternativeDeliverable(fmt)) } else null,
                                        onExportDeliverable = { content, name ->
                                            val folder = discussion.config.output.outputFolder.ifBlank { "." }
                                            val ok = writeTextFile(folder, name, content)
                                            toastScope.launch {
                                                snackbarHostState.showSnackbar(
                                                    if (ok) "Saved $name to $folder" else "Failed to save $name",
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        }
                                    )
                                }

                                // Render milestone artifact notification strips right here in strict chronological order!
                                if (isEndOfRound && !msg.isUserComment && chatDisplaySettings.showArtifactGenerationStrips && roundArtifacts.isNotEmpty()) {
                                    roundArtifacts.filterNot { it.id in discussion.dismissedArtifactIds }.forEach { art ->
                                        Spacer(Modifier.height(4.dp))
                                        ArtifactStrip(
                                            cc = cc,
                                            artifact = art,
                                            onView = toggleArtifactsPane,
                                            onDismiss = { onIntent(ChatIntent.DismissArtifactBanner(art.id)) }
                                        )
                                    }
                                }
                            }
                        }

                        // Live typing / thinking bubble — or engine-disconnected error pill
                        if (state.engineConnectionLost && discussion.status == DiscussionStatus.RUNNING) {
                            item {
                                Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
                                    StatusPill(cc, "⚠ Lost connection to engine — retrying…")
                                }
                            }
                        } else if (nextSpeakerAgent != null) {
                            item {
                                Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
                                    AgentThinkingTicker(
                                        cc = cc,
                                        agent = nextSpeakerAgent,
                                        tokens = (discussion.transcript.sumOf { (it.tokensIn ?: 0) + (it.tokensOut ?: 0) })
                                    )
                                }
                            }
                        }

                        // Status messages
                        if (state.pauseRequested && discussion.status == DiscussionStatus.RUNNING) {
                            item {
                                StatusPill(cc, "Pausing — finishing current turn…")
                            }
                        } else if (discussion.status == DiscussionStatus.PAUSED) {
                            item {
                                StatusPill(cc, "Paused — click Resume to continue debate")
                            }
                        }

                        // AI Handoff Prompt
                        val primaryName = discussion.config.primary.label()
                        when (val h = handoff) {
                            is HandoffState.Loading -> item {
                                Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
                                    AgentThinkingTicker(cc, discussion.config.primary, 0, action = "Synthesizing handoff prompt...")
                                }
                            }
                            is HandoffState.Failed -> item {
                                Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
                                    AeratedMessageItem(
                                        cc = cc,
                                        agentName = primaryName,
                                        meta = "handoff prompt failed",
                                        content = h.message,
                                        provider = primaryProvider,
                                        isPrimary = true,
                                        isError = true,
                                        typographySettings = typographySettings
                                    )
                                }
                            }
                            else -> {
                                if (discussion.handoffPrompt != null) {
                                    item {
                                        Column(Modifier.widthIn(max = maxContentWidth).fillMaxWidth()) {
                                            AeratedMessageItem(
                                                cc = cc,
                                                agentName = primaryName,
                                                meta = "handoff prompt",
                                                content = discussion.handoffPrompt,
                                                provider = primaryProvider,
                                                isPrimary = true,
                                                typographySettings = typographySettings
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 36dp padding at the end of chat scroll
                        item {
                            Spacer(Modifier.height(36.dp))
                        }
                    }

                    // ── Floating Claude Bottom Input Bar (docked above bottom) ───
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                    .widthIn(max = maxContentWidth)
                    .fillMaxWidth()
                    .imePadding()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = if (isCompact) 12.dp else 20.dp, vertical = if (isCompact) 8.dp else 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Row 1: Dedicated Live Debate Controls Bar (Antigravity Style)
                val statusColor = when (discussion.status) {
                    DiscussionStatus.RUNNING -> Color(0xFF4CAF50)
                    DiscussionStatus.PAUSED -> Color(0xFFF59E0B)
                    DiscussionStatus.DONE, DiscussionStatus.COMPLETED -> cc.textMuted
                    DiscussionStatus.COMPLETED_WITH_WARNING -> Color(0xFFFFB300)
                    DiscussionStatus.ERROR, DiscussionStatus.FAILED -> Color(0xFFEF4444)
                    DiscussionStatus.DRAFT -> cc.textMuted
                }
                val isPulsing = discussion.status == DiscussionStatus.RUNNING || discussion.status == DiscussionStatus.PAUSED
                val infiniteTransition = rememberInfiniteTransition()
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.2f,
                    targetValue = 0.65f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    )
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Status Indicator, Glow Dot & Label
                        val isDebateError = discussion.status == DiscussionStatus.ERROR
                        val lastErrorTurn = remember(discussion.transcript) { discussion.transcript.findLast { it.isError } }
                        val errorSummary = lastErrorTurn?.content?.trim()
                            ?: latestAppError
                            ?: if (state.invalidFolders.isNotEmpty()) {
                                "Attached workspace context folder is missing or invalid on disk."
                            } else if (state.tokenProgressLabel.isNotEmpty() && state.tokenProgressLabel.contains("100%")) {
                                "Token budget limit reached. Raise budget in Settings > Limits to continue."
                            } else {
                                "Debate execution encountered an error. Click to view details."
                            }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemedTooltipBox(
                                tooltip = if (isDebateError) "Error: ${errorSummary.take(130)}${if (errorSummary.length > 130) "…" else ""}\nClick to view error details" else ""
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = if (isDebateError) {
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { isDebateErrorDialogOpen = true }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    } else {
                                        Modifier
                                    }
                                ) {
                                    // Pulsating Glow dot with colored center & soft halo
                                    Box(
                                        modifier = Modifier.size(13.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isPulsing) {
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .background(statusColor.copy(alpha = pulseAlpha))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(9.dp)
                                                    .clip(CircleShape)
                                                    .background(statusColor.copy(alpha = 0.2f))
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(statusColor)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = when (discussion.status) {
                                            DiscussionStatus.RUNNING -> if (state.pauseRequested) "Pausing at turn end..." else "Debate in progress"
                                            DiscussionStatus.PAUSED -> "Debate paused"
                                            DiscussionStatus.DONE, DiscussionStatus.COMPLETED -> "Debate completed"
                                            DiscussionStatus.COMPLETED_WITH_WARNING -> "Deliberation concluded with warning"
                                            DiscussionStatus.ERROR, DiscussionStatus.FAILED -> "Debate error"
                                            DiscussionStatus.DRAFT -> "Draft debate"
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
                                        color = if (isDebateError) Color(0xFFEF4444) else cc.textMuted
                                    )
                                    if (isDebateError) {
                                        Spacer(Modifier.width(5.dp))
                                        Icon(
                                            Icons.Outlined.Info,
                                            contentDescription = "View Error Details",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(10.dp))
                            val mode = discussion.config.userInterventionPolicy
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = cc.panel,
                                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    when (mode) {
                                        com.dialex.model.UserInterventionPolicy.AUTONOMOUS_AUTOPILOT -> "Autopilot"
                                        com.dialex.model.UserInterventionPolicy.OBSERVER_INTERACTIVE -> "Interactive"
                                        com.dialex.model.UserInterventionPolicy.HUMAN_GATEKEEPER -> "Gatekeeper"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Normal
                                    ),
                                    color = cc.textMuted,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }

                            if (state.tokenProgressLabel.isNotBlank()) {
                                Spacer(Modifier.width(10.dp))
                                ThemedTooltipBox("Token safety budget (${state.tokenProgressLabel})\nClick to adjust limit settings") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .clickable {
                                                if (onOpenTokenSettings != null) onOpenTokenSettings()
                                                else onOpenSettings?.invoke()
                                            }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Speed,
                                            contentDescription = "Token safety limits",
                                            tint = if (state.tokenWarningLevel == TokenWarningLevel.Critical || state.tokenWarningLevel == TokenWarningLevel.Exceeded) Color(0xFFEF4444) else cc.textMuted,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            state.tokenProgressLabel,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Normal),
                                            color = if (state.tokenWarningLevel == TokenWarningLevel.Critical || state.tokenWarningLevel == TokenWarningLevel.Exceeded) Color(0xFFEF4444) else cc.textMuted
                                        )
                                    }
                                }
                            }

                            if (state.totalSpendUsd > 0.0) {
                                Spacer(Modifier.width(6.dp))
                                val costColor = when {
                                    state.totalSpendUsd >= 2.00 -> Color(0xFFEF4444)
                                    state.totalSpendUsd >= 1.00 -> Color(0xFFF59E0B)
                                    else -> Color(0xFF10B981)
                                }
                                val formattedCost = "$" + (if (state.totalSpendUsd < 0.01) "<0.01" else (((state.totalSpendUsd * 100).toInt()) / 100.0).toString())
                                ThemedTooltipBox("Estimated total discussion spend: $formattedCost\nClick to inspect breakdown") {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .clickable { onIntent(ChatIntent.ShowUsageModal) }
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            formattedCost,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = costColor
                                        )
                                    }
                                }
                            }

                            if (state.cachedTokensPercent > 0) {
                                Spacer(Modifier.width(6.dp))
                                ThemedTooltipBox("Prompt Cache Efficiency: ${state.cachedTokensPercent}% of input tokens were served from cache, reducing cost & latency") {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.12f),
                                        border = BorderStroke(0.5.dp, Color(0xFF10B981).copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            "⚡ ${state.cachedTokensPercent}% cached",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                            color = if (cc.isDark) Color(0xFF6EE7B7) else Color(0xFF047857),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }

                            if (state.tensionPairs.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                val openTensions = state.tensionPairs.count { it.status == TensionStatus.OPEN || it.status == TensionStatus.EXPLORED }
                                val badgeBg = if (openTensions > 0) Color(0xFFFEF3C7) else Color(0xFFD1FAE5)
                                val badgeText = if (openTensions > 0) Color(0xFFD97706) else Color(0xFF059669)
                                val badgeIcon = if (openTensions > 0) Icons.Outlined.ElectricBolt else Icons.Outlined.CheckCircle
                                val tooltip = if (openTensions > 0) {
                                    "$openTensions active dialectic tension(s) detected\nClick to inspect Tension Matrix"
                                } else {
                                    "All dialectic tensions synthesized\nClick to inspect Tension Matrix"
                                }
                                ThemedTooltipBox(tooltip) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(badgeBg)
                                            .clickable { onIntent(ChatIntent.ToggleTensionDrawer) }
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            badgeIcon,
                                            contentDescription = "Tension Matrix",
                                            tint = badgeText,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = if (openTensions > 0) "$openTensions Tension${if (openTensions > 1) "s" else ""}" else "Tensions Resolved",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                            color = badgeText
                                        )
                                    }
                                }
                            }
                        }

                        // Right: Live Debate Controls
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (discussion.status) {
                                DiscussionStatus.RUNNING -> {
                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = cc.panel,
                                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .clickable(enabled = !state.isActionInProgress) {
                                                onIntent(ChatIntent.Pause)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Filled.Pause, contentDescription = "Pause", tint = cc.textMuted, modifier = Modifier.size(12.dp))
                                            Text("Pause", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = cc.panel,
                                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .clickable(enabled = !state.isActionInProgress) {
                                                onIntent(ChatIntent.HardStop)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = cc.textMuted, modifier = Modifier.size(12.dp))
                                            Text("Stop", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                                        }
                                    }
                                }
                                DiscussionStatus.PAUSED, DiscussionStatus.ERROR -> {
                                    val canResume = !state.isActionInProgress && state.invalidFolders.isEmpty()
                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = if (canResume) cc.accent else cc.panel,
                                        border = if (canResume) null else BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .clickable(enabled = canResume) {
                                                onIntent(ChatIntent.Resume)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 13.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (state.isActionInProgress) {
                                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp)
                                            } else {
                                                Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = if (canResume) Color.White else cc.textMuted, modifier = Modifier.size(12.dp))
                                            }
                                            Text("Resume", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold), color = if (canResume) Color.White else cc.textMuted)
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = cc.panel,
                                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .clickable(enabled = !state.isActionInProgress) {
                                                onIntent(ChatIntent.HardStop)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 11.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = cc.textMuted, modifier = Modifier.size(12.dp))
                                            Text("Stop", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                                        }
                                    }
                                }
                                DiscussionStatus.DONE, DiscussionStatus.COMPLETED, DiscussionStatus.COMPLETED_WITH_WARNING -> {
                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = cc.panel,
                                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .height(30.dp)
                                            .clip(RoundedCornerShape(7.dp))
                                            .clickable(enabled = !state.isActionInProgress) {
                                                isContinueDebateDialogOpen = true
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Outlined.PlayArrow, contentDescription = "Continue Debate", tint = cc.textMuted, modifier = Modifier.size(13.dp))
                                            Text("Continue Debate", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                                        }
                                    }
                                }
                                else -> {}
                            }

                        }
                    }
                }

                // In-Flight Holding Queue Bubble (Holds queued comment while discussion is running)
                AnimatedVisibility(
                    visible = state.queuedUserComment != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val queuedText = state.queuedUserComment ?: ""
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = cc.accent.copy(alpha = 0.15f),
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Outlined.HourglassTop,
                                                contentDescription = null,
                                                tint = cc.accent,
                                                modifier = Modifier.size(11.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        "Queued for next turn",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 11.5.sp
                                        ),
                                        color = cc.accent
                                    )
                                }

                                IconButton(
                                    onClick = { onIntent(ChatIntent.CancelHeldComment) },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Cancel queued comment",
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.height(4.dp))

                            Text(
                                queuedText,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp
                                ),
                                color = cc.textPrimary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = cc.accent,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            onIntent(ChatIntent.InterruptAndSendHeldComment)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Bolt,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            "Interrupt & Send Now",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 11.5.sp
                                            ),
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Row 2: User Input Box with Round Arrow Send Button
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(cc.panel)
                        .border(BorderStroke(1.dp, cc.border), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // Input Text Field
                    BasicTextField(
                        value = inputPrompt,
                        onValueChange = { inputPrompt = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cc.textPrimary, lineHeight = 20.sp),
                        cursorBrush = SolidColor(cc.accent),
                        minLines = 1,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (inputPrompt.isEmpty()) {
                                Text(
                                    if (discussion.config.userInterventionPolicy == com.dialex.model.UserInterventionPolicy.AUTONOMOUS_AUTOPILOT)
                                        "⚡ Autopilot active — type here to inject a human comment or override..."
                                    else
                                        "Add a comment or inject a point for the agents...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cc.textMuted.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    )

                    Spacer(Modifier.height(8.dp))

                    // Input Controls Row: Mode selector, Attach, Voice, and Round Send Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Mode / Target Agent Pill
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { agentModeDropdownOpen = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    selectedAgentMode,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                    color = cc.textMuted
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }

                            DropdownMenu(
                                expanded = agentModeDropdownOpen,
                                onDismissRequest = { agentModeDropdownOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("All Agents (Open Comment)") },
                                    onClick = {
                                        selectedAgentMode = "All Agents"
                                        agentModeDropdownOpen = false
                                    }
                                )
                                discussion.config.agents.forEach { agent ->
                                    DropdownMenuItem(
                                        text = { Text(agent.label()) },
                                        onClick = {
                                            selectedAgentMode = agent.label()
                                            agentModeDropdownOpen = false
                                        }
                                    )
                                }
                            }
                        }

                        // Right: Attach +, Voice 🎙, Round Send Arrow Button
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { /* Attach */ }, modifier = Modifier.size(if (isCompact) 40.dp else 26.dp)) {
                                Icon(Icons.Default.Add, contentDescription = "Attach file", tint = cc.textMuted, modifier = Modifier.size(if (isCompact) 20.dp else 16.dp))
                            }
                            com.dialex.ui.VoiceInputButton(
                                currentText = inputPrompt,
                                onTextChange = { inputPrompt = it },
                                size = if (isCompact) 40.dp else 26.dp,
                                iconSize = if (isCompact) 20.dp else 16.dp
                            )

                            Spacer(Modifier.width(2.dp))

                            // Round Send Button with Right Arrow (User Comments)
                            val canSendComment = inputPrompt.isNotBlank() && state.invalidFolders.isEmpty()
                            Surface(
                                shape = CircleShape,
                                color = if (canSendComment) cc.accent else cc.panelAlt,
                                border = BorderStroke(0.75.dp, if (canSendComment) cc.accent else cc.border.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .size(if (isCompact) 36.dp else 28.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = canSendComment) {
                                        onIntent(ChatIntent.SendUserComment(inputPrompt))
                                        inputPrompt = ""
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "Send User Comment",
                                        tint = if (canSendComment) Color.White else cc.textMuted.copy(alpha = 0.5f),
                                        modifier = Modifier.size(if (isCompact) 18.dp else 14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Floating Navigation Action Buttons (Deliverable Checkpoint & Scroll to Bottom) ───
            val deliverableTargetIndex = 1 + discussion.transcript.size
            val isDeliverableVisible by remember(hasDeliverable, deliverableTargetIndex) {
                derivedStateOf {
                    listState.layoutInfo.visibleItemsInfo.any { it.index == deliverableTargetIndex }
                }
            }
            val isPastDeliverable by remember(hasDeliverable, deliverableTargetIndex) {
                derivedStateOf {
                    listState.firstVisibleItemIndex >= deliverableTargetIndex
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = if (isCompact) 16.dp else 28.dp,
                        bottom = if (isCompact) 115.dp else 125.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Deliverable Jump FAB
                androidx.compose.animation.AnimatedVisibility(
                    visible = hasDeliverable && !isDeliverableVisible,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    val tooltipText = if (isPastDeliverable) "Go to last deliverable (scroll up)" else "Go to next deliverable (scroll down)"
                    ThemedTooltipBox(tooltipText) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = cc.panel,
                            tonalElevation = 6.dp,
                            shadowElevation = 6.dp,
                            border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable {
                                    toastScope.launch {
                                        listState.animateScrollToItem(deliverableTargetIndex.coerceAtLeast(0))
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPastDeliverable) Icons.Outlined.KeyboardDoubleArrowUp else Icons.Outlined.KeyboardDoubleArrowDown,
                                    contentDescription = tooltipText,
                                    tint = cc.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Deliverable",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 11.5.sp
                                    ),
                                    color = cc.accent
                                )
                            }
                        }
                    }
                }

                // Scroll navigation buttons (Top & Bottom FABs)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scroll to top FAB (hidden when at the top)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = listState.canScrollBackward,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        ThemedTooltipBox("Scroll to top") {
                            FloatingActionButton(
                                onClick = {
                                    toastScope.launch {
                                        listState.animateScrollToItem(0)
                                    }
                                },
                                containerColor = cc.panelAlt,
                                contentColor = cc.textPrimary,
                                modifier = Modifier.size(36.dp),
                                shape = CircleShape
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = "Scroll to top",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Scroll to bottom FAB (hidden when at the bottom)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = listState.canScrollForward,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        ThemedTooltipBox("Scroll to bottom") {
                            FloatingActionButton(
                                onClick = {
                                    toastScope.launch {
                                        val count = listState.layoutInfo.totalItemsCount
                                        if (count > 0) {
                                            listState.animateScrollToItem(count - 1)
                                        }
                                    }
                                },
                                containerColor = cc.panelAlt,
                                contentColor = cc.textPrimary,
                                modifier = Modifier.size(36.dp),
                                shape = CircleShape
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Scroll to bottom",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            com.dialex.ui.ThemedSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp))
        } // End of discussion space Box(Modifier.weight(1f).fillMaxHeight())
    } // End of Row(Modifier.weight(1f).fillMaxWidth())
} // End of Column(Modifier.fillMaxSize())

            if (state.showUsageModal) {
                UsageModal(
                    usageBreakdown = state.usageBreakdown,
                    onDismiss = { onIntent(ChatIntent.DismissUsageModal) },
                    cc = cc
                )
            }

            if (state.isTensionDrawerOpen) {
                TensionMatrixDrawer(
                    tensions = state.tensionPairs,
                    filter = state.tensionFilter,
                    onFilterSelect = { onIntent(ChatIntent.SetTensionFilter(it)) },
                    onDismiss = { onIntent(ChatIntent.SetTensionDrawerOpen(false)) }
                )
            }

            // ── Slide-Over Overlapping Drawer: Artifacts Repository ───
            val isArtifactsOpen = isArtifactsPaneOpen || state.artifactsModalOpen
            val closeArtifacts: () -> Unit = {
                if (state.artifactsModalOpen) onIntent(ChatIntent.ToggleArtifactsModal)
                if (isArtifactsPaneOpen) toggleArtifactsPane()
            }

            // Dimmed Scrim Backdrop
            AnimatedVisibility(
                visible = isArtifactsOpen,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (cc.isDark) 0.45f else 0.25f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = closeArtifacts
                        )
                )
            }

            // Right-anchored Drawer
            AnimatedVisibility(
                visible = isArtifactsOpen,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            ) {
                val drawerWidth = when {
                    maxWidth < 600.dp -> maxWidth * 0.92f
                    maxWidth < 1100.dp -> 480.dp
                    maxWidth < 1500.dp -> 540.dp
                    else -> 600.dp
                }

                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    color = cc.panel,
                    shadowElevation = 16.dp,
                    border = BorderStroke(1.dp, cc.border.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .width(drawerWidth)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* Consume clicks inside drawer */ }
                ) {
                    ArtifactsSlidingPane(
                        discussion = discussion,
                        onClose = closeArtifacts,
                        onExport = { content, name ->
                            val folder = discussion.config.output.outputFolder.ifBlank { "." }
                            val ok = writeTextFile(folder, name, content)
                            toastScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (ok) "Saved $name to $folder" else "Failed to save $name",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        typographySettings = typographySettings,
                        cc = cc,
                        modifier = Modifier.fillMaxSize(),
                        onDeleteArtifact = { onIntent(ChatIntent.DeleteArtifact(it)) }
                    )
                }
            }

            if (isSummaryDialogOpen) {
                DiscussionSummaryDialog(
                    discussion = discussion,
                    onDismiss = toggleSummaryDialog,
                    onExportMarkdown = { content, name ->
                        val folder = discussion.config.output.outputFolder.ifBlank { "." }
                        val ok = writeTextFile(folder, name, content)
                        toastScope.launch {
                            snackbarHostState.showSnackbar(
                                if (ok) "Saved $name to $folder" else "Failed to save $name",
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    typographySettings = typographySettings,
                    cc = cc
                )
            }

            if (isDebateErrorDialogOpen && discussion.status == DiscussionStatus.ERROR) {
                val lastErrorTurn = remember(discussion.transcript) { discussion.transcript.findLast { it.isError } }
                val errorContent = lastErrorTurn?.content?.trim()
                    ?: latestAppError
                    ?: if (state.invalidFolders.isNotEmpty()) {
                        "One or more attached workspace folders could not be located on disk:\n\n" +
                            state.invalidFolders.joinToString("\n") { "• ${it.first.path}: ${it.second}" } +
                            "\n\nPlease resolve the folder paths in Setup, then click Resume."
                    } else if (state.tokenProgressLabel.isNotEmpty() && state.tokenProgressLabel.contains("100%")) {
                        "The discussion has reached its configured safety Token Budget (${state.tokenProgressLabel}).\n\n" +
                            "To continue this debate, open Settings > Limits, increase 'Max tokens per discussion' (or set to 0 to disable), and click Resume."
                    } else {
                        "Debate execution encountered an error.\n\n" +
                            "Possible causes:\n" +
                            "• Model API key missing or expired (check Settings > Providers)\n" +
                            "• Local CLI executable (claude, codex, gemini) not installed or requires auth\n" +
                            "• Backend engine communication issue or timeout\n\n" +
                            "Click 'Resume' to retry the last turn or review App Logs in Settings."
                    }
                DebateErrorDialog(
                    discussion = discussion,
                    errorMessage = errorContent,
                    agentProvider = lastErrorTurn?.agentId,
                    round = lastErrorTurn?.round,
                    onDismiss = { isDebateErrorDialogOpen = false },
                    onResume = {
                        isDebateErrorDialogOpen = false
                        onIntent(ChatIntent.Resume)
                    },
                    cc = cc
                )
            }

            if (isContinueDebateDialogOpen) {
                ContinueDebateDialog(
                    cc = cc,
                    currentMaxRound = discussion.transcript.maxOfOrNull { it.round } ?: 1,
                    configuredMaxRounds = discussion.config.maxRounds,
                    isUnlimited = discussion.config.roundMode == RoundMode.UNLIMITED,
                    onDismiss = { isContinueDebateDialogOpen = false },
                    onConfirm = { additionalRounds, unlimited ->
                        isContinueDebateDialogOpen = false
                        onIntent(ChatIntent.RestartDebate(additionalRounds, unlimited))
                    }
                )
            }

            if (activeAuthCliTool != null) {
                CliAuthDialog(
                    tool = activeAuthCliTool!!,
                    onDismiss = { activeAuthCliTool = null },
                    onSuccess = {
                        activeAuthCliTool = null
                        onIntent(ChatIntent.Resume)
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Aerated Minimal Subcomponents
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContextHeader(
    cc: CcPalette,
    discussion: Discussion,
    onRenameDiscussion: ((String) -> Unit)? = null,
    onRegenerateTitle: (() -> Unit)? = null,
    typographySettings: ChatTypographySettings? = null
) {
    val config = discussion.config
    val title = discussion.name.ifBlank { "Dialex Deliberation" }

    var isTopicExpanded by remember { mutableStateOf(false) }
    var isContextExpanded by remember { mutableStateOf(false) }
    var isDirectivesExpanded by remember { mutableStateOf(false) }
    var isScopesExpanded by remember { mutableStateOf(false) }
    var isCouncilExpanded by remember { mutableStateOf(false) }

    val clipboard = LocalClipboardManager.current
    var topicCopied by remember { mutableStateOf(false) }
    var contextCopied by remember { mutableStateOf(false) }
    var directivesCopied by remember { mutableStateOf(false) }

    LaunchedEffect(topicCopied) {
        if (topicCopied) {
            kotlinx.coroutines.delay(1800)
            topicCopied = false
        }
    }
    LaunchedEffect(contextCopied) {
        if (contextCopied) {
            kotlinx.coroutines.delay(1800)
            contextCopied = false
        }
    }
    LaunchedEffect(directivesCopied) {
        if (directivesCopied) {
            kotlinx.coroutines.delay(1800)
            directivesCopied = false
        }
    }

    var isEditingTitle by remember { mutableStateOf(false) }
    var titleEditText by remember(title) { mutableStateOf(title) }
    val titleFocusRequester = remember { FocusRequester() }
    var hasBeenFocused by remember { mutableStateOf(false) }

    fun commitTitleRename() {
        val trimmed = titleEditText.trim()
        if (trimmed.isNotBlank() && trimmed != title) {
            onRenameDiscussion?.invoke(trimmed)
        }
        isEditingTitle = false
        hasBeenFocused = false
    }

    LaunchedEffect(isEditingTitle) {
        if (isEditingTitle) {
            hasBeenFocused = false
            titleEditText = title
            titleFocusRequester.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Summary Title Header Card ─────────────────────────────────────────
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cc.panel.copy(alpha = 0.6f),
            border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        discussion.status == DiscussionStatus.RUNNING -> cc.accent
                                        discussion.status.isCompleted -> Color(0xFF4CAF50)
                                        discussion.status.isFailed -> Color(0xFFE53935)
                                        else -> cc.textMuted
                                    }
                                )
                        )
                        if (isEditingTitle) {
                            BasicTextField(
                                value = titleEditText,
                                onValueChange = { titleEditText = it },
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    color = cc.textPrimary
                                ),
                                singleLine = true,
                                cursorBrush = SolidColor(cc.accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { commitTitleRename() }),
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .focusRequester(titleFocusRequester)
                                    .background(cc.panelAlt, RoundedCornerShape(6.dp))
                                    .border(1.dp, cc.accent, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            hasBeenFocused = true
                                        } else if (hasBeenFocused && isEditingTitle) {
                                            commitTitleRename()
                                        }
                                    }
                                    .onKeyEvent { keyEvent ->
                                        if (keyEvent.type == KeyEventType.KeyDown) {
                                            when (keyEvent.key) {
                                                Key.Enter -> {
                                                    commitTitleRename()
                                                    true
                                                }
                                                Key.Escape -> {
                                                    titleEditText = title
                                                    isEditingTitle = false
                                                    hasBeenFocused = false
                                                    true
                                                }
                                                else -> false
                                            }
                                        } else false
                                    }
                            )
                            IconButton(
                                onClick = { commitTitleRename() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save Title", tint = cc.accent, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    titleEditText = title
                                    isEditingTitle = false
                                    hasBeenFocused = false
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                            }
                        } else {
                            if (onRenameDiscussion != null) {
                                ThemedTooltipBox(
                                    tooltip = "Double-click to rename",
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 16.sp
                                        ),
                                        color = cc.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .combinedClickable(
                                                onDoubleClick = {
                                                    isEditingTitle = true
                                                },
                                                onClick = {}
                                            )
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            } else {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 16.sp
                                    ),
                                    color = cc.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                        }
                    }

                    if (!isEditingTitle) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (onRegenerateTitle != null) {
                                ThemedTooltipBox("Generate summary title using compaction model") {
                                    IconButton(
                                        onClick = onRegenerateTitle,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Regenerate Summary Title",
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                            if (onRenameDiscussion != null) {
                                ThemedTooltipBox("Rename Discussion") {
                                    IconButton(
                                        onClick = { isEditingTitle = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit Title",
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Info Accordions: Full Topic & Context ────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Full Topic Accordion
                    if (config.topic.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = cc.panelAlt.copy(alpha = 0.5f),
                            border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isTopicExpanded = !isTopicExpanded },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Outlined.Info, contentDescription = null, tint = cc.accent, modifier = Modifier.size(13.dp))
                                        Text(
                                            "Full Topic",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                            color = cc.textPrimary
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (topicCopied) cc.accent.copy(alpha = 0.15f) else cc.panel,
                                            border = BorderStroke(0.5.dp, if (topicCopied) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.4f)),
                                            modifier = Modifier.clickable {
                                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(config.topic))
                                                topicCopied = true
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                            ) {
                                                Icon(
                                                    if (topicCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                                    contentDescription = "Copy Topic",
                                                    tint = if (topicCopied) cc.accent else cc.textMuted,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Text(
                                                    if (topicCopied) "Copied" else "Copy",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                                    color = if (topicCopied) cc.accent else cc.textMuted
                                                )
                                            }
                                        }
                                        Icon(
                                            if (isTopicExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = if (isTopicExpanded) "Collapse" else "Expand",
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (isTopicExpanded) {
                                    Spacer(Modifier.height(6.dp))
                                    SelectionContainer {
                                        Text(
                                            config.topic,
                                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                            color = cc.textPrimary
                                        )
                                    }
                                } else {
                                    Spacer(Modifier.height(4.dp))
                                    SelectionContainer {
                                        Text(
                                            config.topic,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                            color = cc.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Shared Context Accordion
                    if (config.commonContext.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = cc.panelAlt.copy(alpha = 0.5f),
                            border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isContextExpanded = !isContextExpanded },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Outlined.Description, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(13.dp))
                                        Text(
                                            "Shared Context",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                            color = cc.textPrimary
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (contextCopied) cc.accent.copy(alpha = 0.15f) else cc.panel,
                                            border = BorderStroke(0.5.dp, if (contextCopied) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.4f)),
                                            modifier = Modifier.clickable {
                                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(config.commonContext))
                                                contextCopied = true
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                            ) {
                                                Icon(
                                                    if (contextCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                                    contentDescription = "Copy Context",
                                                    tint = if (contextCopied) cc.accent else cc.textMuted,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Text(
                                                    if (contextCopied) "Copied" else "Copy",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                                    color = if (contextCopied) cc.accent else cc.textMuted
                                                )
                                            }
                                        }
                                        Icon(
                                            if (isContextExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = if (isContextExpanded) "Collapse" else "Expand",
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (isContextExpanded) {
                                    Spacer(Modifier.height(6.dp))
                                    SelectionContainer {
                                        Text(
                                            config.commonContext,
                                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                            color = cc.textMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Master Directives Accordion
                    if (config.commonInfo.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = cc.panelAlt.copy(alpha = 0.5f),
                            border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isDirectivesExpanded = !isDirectivesExpanded },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(13.dp))
                                        Text(
                                            "Directives & Instructions",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                            color = cc.textPrimary
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (directivesCopied) cc.accent.copy(alpha = 0.15f) else cc.panel,
                                            border = BorderStroke(0.5.dp, if (directivesCopied) cc.accent.copy(alpha = 0.5f) else cc.border.copy(alpha = 0.4f)),
                                            modifier = Modifier.clickable {
                                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(config.commonInfo))
                                                directivesCopied = true
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                                            ) {
                                                Icon(
                                                    if (directivesCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                                    contentDescription = "Copy Directives",
                                                    tint = if (directivesCopied) cc.accent else cc.textMuted,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Text(
                                                    if (directivesCopied) "Copied" else "Copy",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                                    color = if (directivesCopied) cc.accent else cc.textMuted
                                                )
                                            }
                                        }
                                        Icon(
                                            if (isDirectivesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = if (isDirectivesExpanded) "Collapse" else "Expand",
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (isDirectivesExpanded) {
                                    Spacer(Modifier.height(6.dp))
                                    SelectionContainer {
                                        Text(
                                            config.commonInfo,
                                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                            color = cc.textMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Workspace Scopes & Attached Files Accordion
                    if (discussion.attachedFolders.isNotEmpty() || discussion.attachedFiles.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = cc.panelAlt.copy(alpha = 0.5f),
                            border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().clickable { isScopesExpanded = !isScopesExpanded }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(13.dp))
                                        Text(
                                            "Workspace Scopes & Attachments (${discussion.attachedFolders.size + discussion.attachedFiles.size})",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                            color = cc.textPrimary
                                        )
                                    }
                                    Icon(
                                        if (isScopesExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = if (isScopesExpanded) "Collapse" else "Expand",
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (isScopesExpanded) {
                                    Spacer(Modifier.height(6.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        discussion.attachedFolders.forEach { f ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Icon(Icons.Outlined.Folder, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(12.dp))
                                                Text(f.path, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = cc.textPrimary, modifier = Modifier.weight(1f, fill = false))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (f.isTrusted) cc.accent.copy(alpha = 0.12f) else cc.panel,
                                                    border = BorderStroke(0.5.dp, if (f.isTrusted) cc.accent.copy(alpha = 0.35f) else cc.border)
                                                ) {
                                                    Text(
                                                        if (f.isTrusted) "🛡️ Trusted" else "⚠️ Restricted",
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                                        color = if (f.isTrusted) cc.accent else cc.textMuted
                                                    )
                                                }
                                                if (f.isReadOnly) {
                                                    Surface(shape = RoundedCornerShape(4.dp), color = cc.panel, border = BorderStroke(0.5.dp, cc.border)) {
                                                        Text("Read-Only", modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp), color = cc.textMuted)
                                                    }
                                                }
                                            }
                                        }
                                        discussion.attachedFiles.forEach { f ->
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Icon(Icons.Outlined.Description, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(12.dp))
                                                Text(f.name, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = cc.textPrimary)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Council Participants Accordion
                    if (config.agents.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = cc.panelAlt.copy(alpha = 0.5f),
                            border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth().clickable { isCouncilExpanded = !isCouncilExpanded }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val consensusResult = remember(discussion.transcript.size, discussion.isConsensusReached) {
                                        val currentMaxRound = discussion.transcript.filter { !it.isError && !it.isUserComment && !it.isSystem }.maxOfOrNull { it.round } ?: 1
                                        ConsensusDetector.evaluateConsensus(currentMaxRound, discussion.transcript, discussion.config)
                                    }
                                    val isConsensusAchieved = discussion.isConsensusReached || discussion.earlyExitReason == "CONSENSUS" || consensusResult is ConsensusEvaluationResult.Achieved
                                    val isConverging = consensusResult is ConsensusEvaluationResult.Ongoing && consensusResult.agreedCount > 0

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Outlined.Group, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(13.dp))
                                        Text(
                                            "Council (${config.agents.size} Models) · Mode: ${if (config.roundMode == RoundMode.FIXED) "${config.maxRounds} Rounds" else "Unlimited"}",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                            color = cc.textPrimary
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = when {
                                                isConsensusAchieved -> Color(0xFF2E7D32).copy(alpha = 0.15f)
                                                isConverging -> Color(0xFFE65100).copy(alpha = 0.15f)
                                                else -> Color(0xFFF57F17).copy(alpha = 0.12f)
                                            },
                                            border = BorderStroke(
                                                0.5.dp,
                                                when {
                                                    isConsensusAchieved -> Color(0xFF2E7D32).copy(alpha = 0.4f)
                                                    isConverging -> Color(0xFFE65100).copy(alpha = 0.4f)
                                                    else -> Color(0xFFF57F17).copy(alpha = 0.35f)
                                                }
                                            )
                                        ) {
                                            Text(
                                                text = when {
                                                    isConsensusAchieved -> "🟢 Consensus Reached"
                                                    isConverging -> "🟠 Converging (${(consensusResult as ConsensusEvaluationResult.Ongoing).agreedCount}/${consensusResult.totalCount} Agreed)"
                                                    else -> "🟡 Debating (0/${config.agents.size} Agreed)"
                                                },
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                                                color = when {
                                                    isConsensusAchieved -> if (cc.isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                                                    isConverging -> if (cc.isDark) Color(0xFFFFB74D) else Color(0xFFE65100)
                                                    else -> if (cc.isDark) Color(0xFFFFF176) else Color(0xFFF57F17)
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    Icon(
                                        if (isCouncilExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = if (isCouncilExpanded) "Collapse" else "Expand",
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                if (isCouncilExpanded) {
                                    Spacer(Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        config.agents.forEach { agent ->
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = cc.panel,
                                                border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.5f))
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    Text(
                                                        agent.role.ifBlank { agent.label() },
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
                                                        color = cc.textPrimary
                                                    )
                                                    Text(
                                                        "· ${agent.provider.brandName()}",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                        color = cc.textMuted
                                                    )
                                                    val isWebSearchActive = config.permissions.isWebSearchAllowedFor(agent.id) &&
                                                        (agent.allowWebSearch ?: config.permissions.allowWebSearch)
                                                    if (isWebSearchActive) {
                                                        Surface(
                                                            shape = RoundedCornerShape(3.dp),
                                                            color = cc.accent.copy(alpha = 0.12f),
                                                            border = BorderStroke(0.5.dp, cc.accent.copy(alpha = 0.35f))
                                                        ) {
                                                            Text(
                                                                "🌐 Web",
                                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Medium),
                                                                color = cc.accent,
                                                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckpointDivider(
    checkpointNumber: Int,
    cc: CcPalette,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.weight(1f).height(2.dp)) {
            drawLine(
                color = cc.accent.copy(alpha = 0.55f),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                strokeWidth = 1.5f
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cc.panelAlt,
            border = BorderStroke(0.75.dp, cc.accent.copy(alpha = 0.45f)),
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(cc.accent)
                )
                Text(
                    text = "Checkpoint $checkpointNumber",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    ),
                    color = cc.accent
                )
            }
        }

        Canvas(modifier = Modifier.weight(1f).height(2.dp)) {
            drawLine(
                color = cc.accent.copy(alpha = 0.55f),
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f),
                strokeWidth = 1.5f
            )
        }
    }
}

@Composable
private fun RoundDivider(
    round: Int,
    totalRounds: Int,
    cc: CcPalette,
    modifier: Modifier = Modifier
) {
    val phaseLabel = when (round) {
        1 -> "Opening Statements"
        totalRounds -> "Closing Arguments & Synthesis"
        else -> "Cross-Examination & Rebuttals"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = cc.border.copy(alpha = 0.45f),
            thickness = 0.75.dp
        )

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cc.panelAlt,
            border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(cc.accent)
                )
                Text(
                    text = "Round $round" + if (totalRounds > 0) " of $totalRounds" else "",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.5.sp
                    ),
                    color = cc.textPrimary
                )
                Text(
                    text = "· $phaseLabel",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = cc.textMuted
                )
            }
        }

        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = cc.border.copy(alpha = 0.45f),
            thickness = 0.75.dp
        )
    }
}

private enum class TurnErrorCategory {
    SESSION_OR_RATE_LIMIT,
    AUTH_REQUIRED,
    CLI_PROCESS_FAILURE,
    NETWORK_OR_SERVER,
    GENERIC
}

private data class ParsedTurnError(
    val category: TurnErrorCategory,
    val badgeLabel: String,
    val badgeIcon: String,
    val badgeColor: Color,
    val title: String,
    val summary: String,
    val resetSchedule: String? = null,
    val rawError: String,
    val cliBinary: String = ""
)

private fun parseTurnError(content: String, provider: Provider): ParsedTurnError {
    val cliBinary = extractCliBinary(content, provider)
    val lower = content.lowercase()

    // 1. Reset schedule extraction if present (e.g., "resets 2am (Asia/Calcutta)", "retry in 30s")
    val resetRegex = Regex("""(?:resets?|resets at|reset in|retry in|retry after|try again in)\s+([^\n\r·•\.]+)""", RegexOption.IGNORE_CASE)
    val resetMatch = resetRegex.find(content)?.groupValues?.get(1)?.trim()

    // 2. Token / Session / Quota / Rate limit detection
    val isSessionLimit = lower.contains("session limit") || lower.contains("session_limit") || lower.contains("sessionlimit")
    val isRateLimit = lower.contains("rate limit") || lower.contains("rate_limit") || lower.contains("429") || lower.contains("too many requests")
    val isQuotaExhausted = lower.contains("insufficient_quota") || lower.contains("exceeded your current quota") ||
            lower.contains("credit balance is too low") || lower.contains("insufficient credits") || lower.contains("resource_exhausted") ||
            lower.contains("quota exceeded")
    val isTokenBudgetExhausted = lower.contains("token budget") || lower.contains("token limit") || lower.contains("tokens exhausted")
    val isContextLengthExceeded = lower.contains("context length") || lower.contains("maximum context length") || lower.contains("prompt is too long")

    if (isSessionLimit || isRateLimit || isQuotaExhausted || isTokenBudgetExhausted || isContextLengthExceeded) {
        val badgeLabel = when {
            isSessionLimit -> "Session Limit Hit"
            isQuotaExhausted -> "Quota Exhausted"
            isTokenBudgetExhausted -> "Token Budget Reached"
            isContextLengthExceeded -> "Context Window Exceeded"
            else -> "Rate Limited (429)"
        }
        val badgeIcon = when {
            isQuotaExhausted -> "💳"
            isContextLengthExceeded -> "📏"
            else -> "⏳"
        }
        val title = when {
            isSessionLimit -> "Session Limit Reached"
            isQuotaExhausted -> "Account Quota Exhausted"
            isTokenBudgetExhausted -> "Token Budget Limit Reached"
            isContextLengthExceeded -> "Context Window Exceeded"
            else -> "API Rate Limit Hit"
        }
        val friendlyMsg = when {
            isSessionLimit -> "The provider CLI session ($cliBinary) reached its active quota limit.${if (resetMatch != null) " Access will reset automatically at the scheduled time below." else " Please wait until reset or switch provider/model in Settings."}"
            isQuotaExhausted -> "The provider account has exhausted its available credits or billing quota. Please check your provider billing dashboard or configure an alternate API key."
            isTokenBudgetExhausted -> "This debate reached its configured safety Token Budget. You can raise or disable the limit in Settings > Limits."
            isContextLengthExceeded -> "The conversation history exceeds the maximum context length supported by this model. Consider compacting the history or reducing file attachments."
            else -> "Too many requests sent to the provider. The request was temporarily throttled."
        }
        return ParsedTurnError(
            category = TurnErrorCategory.SESSION_OR_RATE_LIMIT,
            badgeLabel = badgeLabel,
            badgeIcon = badgeIcon,
            badgeColor = if (isQuotaExhausted) Color(0xFFEF4444) else Color(0xFFF59E0B),
            title = title,
            summary = friendlyMsg,
            resetSchedule = resetMatch,
            rawError = content,
            cliBinary = cliBinary
        )
    }

    // 3. Auth / Login required
    val isAuth = lower.contains("auth") || lower.contains("log in") || lower.contains("login") ||
            lower.contains("unauthorized") || lower.contains("401") || lower.contains("re-authentication") ||
            lower.contains("api key missing") || lower.contains("invalid api key") ||
            lower.contains("input must be provided") || lower.contains("stdin")

    if (isAuth) {
        return ParsedTurnError(
            category = TurnErrorCategory.AUTH_REQUIRED,
            badgeLabel = "Login Required",
            badgeIcon = "🔑",
            badgeColor = Color(0xFFE11D48),
            title = "Authentication Required",
            summary = "The local CLI tool ($cliBinary) or API provider requires authentication. Click below to log in or configure your API credentials in Settings.",
            resetSchedule = null,
            rawError = content,
            cliBinary = cliBinary
        )
    }

    // 4. CLI / Process failure
    val isProcessError = lower.contains("command not found") || lower.contains("executable file not found") ||
            lower.contains("exit status") || lower.contains("exit code") || lower.contains("exited with an error")

    if (isProcessError) {
        return ParsedTurnError(
            category = TurnErrorCategory.CLI_PROCESS_FAILURE,
            badgeLabel = "CLI Error",
            badgeIcon = "⚙️",
            badgeColor = Color(0xFFEF4444),
            title = "CLI Process Error",
            summary = "The local execution tool encountered an exit error or failed to launch. Verify '$cliBinary' is installed in your PATH and functioning correctly.",
            resetSchedule = null,
            rawError = content,
            cliBinary = cliBinary
        )
    }

    // 5. Network / Server error
    val isNetwork = lower.contains("timeout") || lower.contains("connection refused") ||
            lower.contains("network") || lower.contains("502") || lower.contains("503") || lower.contains("504")

    if (isNetwork) {
        return ParsedTurnError(
            category = TurnErrorCategory.NETWORK_OR_SERVER,
            badgeLabel = "Network Error",
            badgeIcon = "🌐",
            badgeColor = Color(0xFFEF4444),
            title = "Network Connection Error",
            summary = "Failed to establish a connection to the provider or backend engine. Check your network connection or click Retry to rerun the turn.",
            resetSchedule = null,
            rawError = content,
            cliBinary = cliBinary
        )
    }

    // Default generic
    return ParsedTurnError(
        category = TurnErrorCategory.GENERIC,
        badgeLabel = "Turn Dropped",
        badgeIcon = "⚠️",
        badgeColor = Color(0xFFEF4444),
        title = "Turn Dropped",
        summary = "An unexpected error interrupted this turn's response.",
        resetSchedule = null,
        rawError = content,
        cliBinary = cliBinary
    )
}

private fun extractCliBinary(content: String, provider: Provider): String {
    val cliRegex = Regex("""CLI\s+['"]?([^'"\s]+)""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)
    if (!cliRegex.isNullOrBlank()) return cliRegex
    val runMatch = Regex("""Run\s+['"]?(\w+)['"]?\s+to\s+log\s+in""", RegexOption.IGNORE_CASE).find(content)?.groupValues?.get(1)
    if (!runMatch.isNullOrBlank()) return runMatch
    val quoteMatch = Regex("""'(.*?)'""").find(content)?.groupValues?.get(1)
    if (!quoteMatch.isNullOrBlank()) {
        val firstWord = quoteMatch.trim().split(Regex("""\s+""")).firstOrNull().orEmpty()
        if (firstWord.isNotBlank() && firstWord.length < 20) return firstWord
    }
    return when (provider) {
        Provider.ANTHROPIC -> "claude"
        Provider.GEMINI -> "agy"
        Provider.OPENAI -> "codex"
        else -> "agy"
    }
}

@Composable
private fun TurnErrorBlock(
    errorInfo: ParsedTurnError,
    cc: CcPalette,
    typographySettings: ChatTypographySettings,
    isCliLoggedIn: Boolean,
    onLoginCli: ((String) -> Unit)?,
    onRetry: (() -> Unit)?
) {
    var isExpandedDetails by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = errorInfo.badgeColor.copy(alpha = if (cc.isDark) 0.08f else 0.05f),
        border = BorderStroke(1.dp, errorInfo.badgeColor.copy(alpha = if (cc.isDark) 0.35f else 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Title Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(errorInfo.badgeColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(errorInfo.badgeIcon, fontSize = 14.sp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = errorInfo.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        ),
                        color = cc.textPrimary
                    )
                }
            }

            // Friendly Message
            Text(
                text = errorInfo.summary,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = typographySettings.fontSizeSp.sp,
                    lineHeight = (typographySettings.fontSizeSp * 1.35f).sp
                ),
                color = cc.textPrimary.copy(alpha = 0.88f)
            )

            // Reset Schedule Banner (e.g. resets 2am (Asia/Calcutta))
            if (!errorInfo.resetSchedule.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = errorInfo.badgeColor.copy(alpha = if (cc.isDark) 0.15f else 0.10f),
                    border = BorderStroke(0.75.dp, errorInfo.badgeColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Outlined.AccessTime,
                            contentDescription = null,
                            tint = errorInfo.badgeColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Resets: ",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Normal,
                                fontSize = 11.5.sp
                            ),
                            color = cc.textMuted
                        )
                        Text(
                            text = errorInfo.resetSchedule,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = errorInfo.badgeColor
                        )
                    }
                }
            }

            // Collapsible Technical Log / Raw Error
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { isExpandedDetails = !isExpandedDetails }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        if (isExpandedDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = cc.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (isExpandedDetails) "Hide technical error details" else "View technical error details",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = cc.textMuted
                    )
                }

                if (isExpandedDetails) {
                    Spacer(Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cc.panel.copy(alpha = 0.8f),
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = errorInfo.rawError,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = cc.textMuted
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(errorInfo.rawError))
                                            copied = true
                                        }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = if (copied) cc.accent else cc.textMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = if (copied) "Copied" else "Copy error",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = if (copied) cc.accent else cc.textMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                if (isCliLoggedIn) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cc.accent.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.5f)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                "${errorInfo.cliBinary} Ready",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                ),
                                color = cc.textPrimary
                            )
                        }
                    }

                    if (onRetry != null) {
                        GradientButton(
                            text = "Resume Discussion",
                            icon = Icons.Outlined.PlayArrow,
                            onClick = onRetry,
                            height = 32.dp,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                } else if (errorInfo.category == TurnErrorCategory.AUTH_REQUIRED && onLoginCli != null) {
                    GradientButton(
                        text = "Log in with ${errorInfo.cliBinary}",
                        icon = Icons.AutoMirrored.Outlined.Login,
                        onClick = { onLoginCli.invoke(errorInfo.cliBinary) },
                        height = 32.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    )

                    if (onRetry != null) {
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Transparent,
                                contentColor = cc.textPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Retry", tint = cc.textPrimary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Retry", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp), color = cc.textPrimary)
                        }
                    }
                } else if (onRetry != null) {
                    GradientButton(
                        text = "Retry Turn",
                        icon = Icons.Outlined.Refresh,
                        onClick = onRetry,
                        height = 32.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AeratedMessageItem(
    cc: CcPalette,
    agentName: String,
    seatLabel: String = "",
    modelName: String = "",
    personaRole: String? = null,
    meta: String,
    content: String,
    provider: Provider,
    isPrimary: Boolean,
    memberIndex: Int = 0,
    isError: Boolean = false,
    tokensIn: Int? = null,
    tokensOut: Int? = null,
    typographySettings: ChatTypographySettings = ChatTypographySettings.Default,
    isModeratorIntervention: Boolean = false,
    isUserComment: Boolean = false,
    isLoopRecovered: Boolean = false,
    isStalledConcession: Boolean = false,
    timestampMs: Long = 0L,
    searchQuery: String? = null,
    activeOccurrenceIndex: Int = -1,
    isSearchMatch: Boolean = false,
    isActiveSearchMatch: Boolean = false,
    matchIndexLabel: String? = null,
    isCliLoggedIn: Boolean = false,
    onLoginCli: ((String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null
) {
    val errorColor = MaterialTheme.colorScheme.error
    val moderatorColor = Color(0xFFFF9800)
    val userColor = Color(0xFF1E88E5)
    val searchBlue = Color(0xFF0288D1)
    val searchBlueSoft = Color(0xFF64B5F6)
    val memberAccentColor = when {
        isUserComment -> userColor
        isModeratorIntervention -> moderatorColor
        else -> com.dialex.theme.memberColorFor(memberIndex, cc)
    }

    val chatDisplaySettings = LocalChatDisplaySettings.current.value
    val bubbleBgColor = if (chatDisplaySettings.separateAgentBubbleBackgrounds) {
        when {
            isUserComment -> userColor.copy(alpha = if (cc.isDark) 0.08f else 0.05f).compositeOver(cc.panelAlt)
            isModeratorIntervention -> moderatorColor.copy(alpha = if (cc.isDark) 0.08f else 0.05f).compositeOver(cc.panelAlt)
            else -> memberAccentColor.copy(alpha = if (cc.isDark) 0.08f else 0.05f).compositeOver(cc.panelAlt)
        }
    } else {
        cc.panelAlt
    }

    val isConcurred = remember(content, isUserComment, isError) {
        !isUserComment && !isError && ConsensusDetector.isTurnInConsensus(content, ConsensusStrategy.HEURISTIC_HYBRID)
    }
    val consensusGreen = Color(0xFF2E7D32)

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .padding(vertical = 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bubbleBgColor,
            border = BorderStroke(
                if (isActiveSearchMatch) 1.5.dp else if (isConcurred) 1.25.dp else 1.dp,
                when {
                    isActiveSearchMatch -> searchBlue
                    isSearchMatch -> searchBlueSoft.copy(alpha = 0.55f)
                    isUserComment -> userColor.copy(alpha = 0.5f)
                    isModeratorIntervention -> moderatorColor.copy(alpha = 0.5f)
                    isConcurred -> consensusGreen.copy(alpha = if (cc.isDark) 0.65f else 0.45f)
                    chatDisplaySettings.separateAgentBubbleBackgrounds -> memberAccentColor.copy(alpha = if (cc.isDark) 0.25f else 0.18f)
                    else -> cc.border.copy(alpha = 0.45f)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
            ) {
            // Signature vertical accent bar in member color
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .fillMaxHeight()
                    .background(if (isError) errorColor else memberAccentColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                val hasSubHeader = !isUserComment && (modelName.isNotBlank() || (!personaRole.isNullOrBlank() && !personaRole.equals(agentName, ignoreCase = true)))

                // Header row: Avatar initial, Agent Name, Seat Badge, Search Match Badge, Tokens & Time
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (hasSubHeader) 3.dp else 6.dp)
                ) {
                    // Left metadata row (Avatar, Name, Seat, Match Badge)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Initial avatar badge
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(memberAccentColor.copy(alpha = 0.15f))
                                .border(0.75.dp, memberAccentColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isUserComment) {
                                Icon(
                                    Icons.Outlined.Person,
                                    contentDescription = "User",
                                    tint = userColor,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else if (isModeratorIntervention) {
                                Icon(
                                    Icons.Outlined.Gavel,
                                    contentDescription = "Moderator",
                                    tint = moderatorColor,
                                    modifier = Modifier.size(13.dp)
                                )
                            } else {
                                Text(
                                    text = agentName.take(1).uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = memberAccentColor
                                )
                            }
                        }

                        Text(
                            text = agentName,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = (typographySettings.fontSizeSp * 0.95f).sp
                            ),
                            color = cc.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (isConcurred) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = consensusGreen.copy(alpha = if (cc.isDark) 0.22f else 0.12f),
                                border = BorderStroke(0.5.dp, consensusGreen.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "🤝 Concurred",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (cc.isDark) Color(0xFF81C784) else consensusGreen,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        if (isUserComment) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = userColor.copy(alpha = 0.15f),
                                border = BorderStroke(0.75.dp, userColor.copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Person, contentDescription = null, tint = userColor, modifier = Modifier.size(11.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        "HUMAN COMMENT",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.5.sp
                                        ),
                                        color = userColor,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        } else if (isModeratorIntervention) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = moderatorColor.copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, moderatorColor.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                ) {
                                    Text(
                                        text = "🏛️ STEERAGE DIRECTIVE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp
                                        ),
                                        color = moderatorColor,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        if (seatLabel.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = memberAccentColor.copy(alpha = 0.10f),
                                border = BorderStroke(0.5.dp, memberAccentColor.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = seatLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 10.5.sp
                                    ),
                                    color = memberAccentColor,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (isSearchMatch && matchIndexLabel != null) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isActiveSearchMatch) searchBlue.copy(alpha = 0.22f) else searchBlueSoft.copy(alpha = 0.14f),
                                border = BorderStroke(0.75.dp, if (isActiveSearchMatch) searchBlue else searchBlueSoft.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = matchIndexLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.5.sp
                                    ),
                                    color = if (isActiveSearchMatch) searchBlue else (if (cc.isDark) Color(0xFF90CAF9) else searchBlue),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (isLoopRecovered) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF3B82F6).copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, Color(0xFF3B82F6).copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "🔄 Anti-Loop Diverged",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.5.sp
                                    ),
                                    color = if (cc.isDark) Color(0xFF93C5FD) else Color(0xFF1D4ED8),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (isError) {
                            val errorInfo = remember(content, provider) { parseTurnError(content, provider) }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = errorInfo.badgeColor.copy(alpha = if (cc.isDark) 0.18f else 0.12f),
                                border = BorderStroke(0.75.dp, errorInfo.badgeColor.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "${errorInfo.badgeIcon} ${errorInfo.badgeLabel.uppercase()}",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    ),
                                    color = errorInfo.badgeColor,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        if (isStalledConcession) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFF59E0B).copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, Color(0xFFF59E0B).copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "⚖️ Position Maintained",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.5.sp
                                    ),
                                    color = if (cc.isDark) Color(0xFFFCD34D) else Color(0xFFB45309),
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(8.dp))

                    // Right metadata: timestamp & token count
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (timestampMs > 0L) {
                            Text(
                                text = formatMessageTimestamp(timestampMs),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = cc.textMuted.copy(alpha = 0.7f),
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        if (tokensIn != null || tokensOut != null) {
                            Text(
                                text = "${formatTokenCount(tokensIn ?: 0)} in / ${formatTokenCount(tokensOut ?: 0)} out",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                color = cc.textMuted.copy(alpha = 0.65f),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }

                // Sub-header row: Model pill & Persona role on next line
                if (hasSubHeader) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, bottom = 6.dp)
                    ) {
                        if (modelName.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = cc.panel,
                                border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = modelName,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontSize = 10.sp
                                    ),
                                    color = cc.textMuted,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }

                        if (!personaRole.isNullOrBlank() && !personaRole.equals(agentName, ignoreCase = true)) {
                            if (modelName.isNotBlank()) {
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = "· $personaRole",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontStyle = FontStyle.Italic,
                                    fontSize = 10.5.sp
                                ),
                                color = cc.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (isError) {
                    val errorInfo = remember(content, provider) { parseTurnError(content, provider) }
                    TurnErrorBlock(
                        errorInfo = errorInfo,
                        cc = cc,
                        typographySettings = typographySettings,
                        isCliLoggedIn = isCliLoggedIn,
                        onLoginCli = onLoginCli,
                        onRetry = onRetry
                    )
                } else {
                    // Strip redundant leading self-speaker prefixes emitted by LLMs (e.g. "[Claude]", "**Claude:**", "Claude:")
                    val displayContent = remember(content, agentName, provider) {
                        var cleaned = content.trim()
                        if (!isUserComment) {
                            val prefixes = listOf(
                                "[$agentName]",
                                "**$agentName:**",
                                "**$agentName**:",
                                "$agentName:",
                                "[${provider.brandName()}]",
                                "**${provider.brandName()}:**",
                                "**${provider.brandName()}**:",
                                "${provider.brandName()}:",
                                "[${provider.name}]",
                                "${provider.name}:"
                            )
                            for (p in prefixes) {
                                if (cleaned.startsWith(p, ignoreCase = true)) {
                                    cleaned = cleaned.substring(p.length).trimStart()
                                    break
                                }
                            }
                        }
                        cleaned
                    }

                    // Message text in clean, readable typography
                    SelectionContainer {
                        MarkdownText(
                            text = displayContent,
                            cc = cc,
                            textColor = cc.textPrimary,
                            baseFontSize = typographySettings.fontSizeSp.sp,
                            lineHeightMultiplier = typographySettings.lineSpacingMultiplier,
                            searchQuery = searchQuery,
                            activeOccurrenceIndex = activeOccurrenceIndex
                        )
                    }
                }
            }
        }
    }

    // Below the bubble: Animated hover row with generated Date/Time (grey/small) and Copy button
    AnimatedVisibility(
            visible = isHovered,
            enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(150)) + expandVertically(animationSpec = androidx.compose.animation.core.tween(150)),
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(150)) + shrinkVertically(animationSpec = androidx.compose.animation.core.tween(150))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatMessageTimestamp(timestampMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = cc.textMuted.copy(alpha = 0.75f)
                )

                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(content))
                            copied = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy message",
                            tint = if (copied) cc.accent else cc.textMuted,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = if (copied) "Copied!" else "Copy",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = if (copied) cc.accent else cc.textMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentThinkingTicker(
    cc: CcPalette,
    agent: Agent,
    tokens: Int,
    action: String? = null
) {
    var elapsedSeconds by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds++
        }
    }

    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val timeFormatted = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Greyscale small icon for all available agents
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.5.dp))
                .background(cc.panelAlt)
                .border(0.75.dp, cc.border.copy(alpha = 0.35f), RoundedCornerShape(4.5.dp)),
            contentAlignment = Alignment.Center
        ) {
            when (agent.provider) {
                Provider.ANTHROPIC -> Icon(Icons.Outlined.AutoAwesome, contentDescription = "Claude", tint = cc.textMuted, modifier = Modifier.size(11.dp))
                Provider.OPENAI -> Icon(Icons.Outlined.Psychology, contentDescription = "ChatGPT", tint = cc.textMuted, modifier = Modifier.size(11.dp))
                Provider.GEMINI -> Text("✦", color = cc.textMuted, fontSize = 11.sp)
                Provider.GROK -> Icon(Icons.Outlined.Bolt, contentDescription = "Grok", tint = cc.textMuted, modifier = Modifier.size(11.dp))
                Provider.DEEPSEEK -> Icon(Icons.Outlined.TravelExplore, contentDescription = "DeepSeek", tint = cc.textMuted, modifier = Modifier.size(11.dp))
                Provider.MISTRAL -> Icon(Icons.Outlined.Air, contentDescription = "Mistral", tint = cc.textMuted, modifier = Modifier.size(11.dp))
                Provider.OLLAMA -> Icon(Icons.Outlined.SmartToy, contentDescription = "Ollama", tint = cc.textMuted, modifier = Modifier.size(11.dp))
                Provider.CUSTOM -> Icon(Icons.Outlined.SmartToy, contentDescription = "Custom", tint = cc.textMuted, modifier = Modifier.size(11.dp))
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "$timeFormatted · ${formatTokenCount(tokens)} tokens · ${action ?: "${agent.label()} thinking..."}",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = cc.textMuted
        )
    }
}

@Composable
private fun StatusPill(cc: CcPalette, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = cc.panelAlt.copy(alpha = 0.7f)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontStyle = FontStyle.Italic),
                color = cc.textMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun CompactionInfoBar(
    cc: CcPalette,
    startTurn: Int,
    endTurn: Int,
    summaryText: String? = null
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cc.panelAlt.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(cc.accent.copy(alpha = 0.15f))
                            .border(0.75.dp, cc.accent.copy(alpha = 0.35f), RoundedCornerShape(5.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.AutoAwesome,
                            contentDescription = "Context Compaction",
                            tint = cc.accent,
                            modifier = Modifier.size(13.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Context Compacted",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                                color = cc.textPrimary
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = cc.accent.copy(alpha = 0.12f),
                                border = BorderStroke(0.5.dp, cc.accent.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "Turns $startTurn–$endTurn",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                    color = cc.accent,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = "Earlier debate context was summarized to optimize memory window and preserve token headroom.",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                            color = cc.textMuted
                        )
                    }
                }

                if (!summaryText.isNullOrBlank()) {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = if (expanded) "Collapse Summary" else "Expand Summary",
                            tint = cc.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (expanded && !summaryText.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = cc.border.copy(alpha = 0.4f), thickness = 0.5.dp)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                    color = cc.textMuted,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(cc.panelAlt.copy(alpha = 0.5f))
                        .padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun BubbleActions(cc: CcPalette, agentName: String, meta: String, content: String) {
    val clipboard = LocalClipboardManager.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()

    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton(
            onClick = {
                scope.launch {
                    clipboard.setText(AnnotatedString(content))
                    toast("Copied to clipboard")
                }
            },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", tint = cc.textMuted.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
        }
        IconButton(
            onClick = {
                scope.launch {
                    toast("Copied for sharing")
                }
            },
            modifier = Modifier.size(24.dp)
        ) {
            Icon(Icons.Outlined.IosShare, contentDescription = "Share", tint = cc.textMuted.copy(alpha = 0.6f), modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun DeliverableBlock(
    cc: CcPalette,
    discussion: Discussion,
    typographySettings: ChatTypographySettings,
    milestoneRound: Int? = null,
    generatingFormat: DeliverableFormat? = null,
    onOpenSummary: (() -> Unit)? = null,
    onOpenArtifacts: (() -> Unit)? = null,
    onGenerateFormat: ((DeliverableFormat) -> Unit)? = null,
    onExportDeliverable: ((content: String, name: String) -> Unit)? = null
) {
    val moderatorProvider = discussion.config.moderation.moderatorProvider ?: discussion.config.primary.provider
    val moderatorAgent = discussion.config.agents.find { it.provider == moderatorProvider } ?: discussion.config.primary
    val moderatorName = discussion.labelFor(moderatorProvider)
    val moderatorColor = Color(0xFFFF9800)

    var activeFormat by remember(discussion.id, milestoneRound) {
        mutableStateOf<DeliverableFormat?>(generatingFormat)
    }

    LaunchedEffect(generatingFormat) {
        if (generatingFormat != null) {
            activeFormat = generatingFormat
        }
    }

    val formats = listOf(
        DeliverableFormat.ACTION_PLAN to ("Action Plan" to Icons.AutoMirrored.Outlined.Assignment),
        DeliverableFormat.DECISION_MATRIX to ("Decision Matrix" to Icons.Outlined.Balance),
        DeliverableFormat.PRO_CON_LIST to ("Pro/Con List" to Icons.AutoMirrored.Outlined.FormatListBulleted),
        DeliverableFormat.EXECUTIVE_BRIEF to ("Executive Brief" to Icons.AutoMirrored.Outlined.Article),
        DeliverableFormat.DECISION_SUMMARY to ("Decision Summary" to Icons.AutoMirrored.Outlined.FactCheck),
        DeliverableFormat.CUSTOM to ("Custom" to Icons.Outlined.AutoAwesome),
    )

    fun resolveContentForFormat(fmt: DeliverableFormat): String? {
        if (milestoneRound != null) {
            val art = discussion.artifacts.firstOrNull { a ->
                (a.round == milestoneRound || a.timestamp == "Round $milestoneRound" || a.id.endsWith("_r$milestoneRound") || a.name.contains("(Round $milestoneRound)")) &&
                (a.name.contains(fmt.name.replace('_', ' '), ignoreCase = true) ||
                 a.id.startsWith(fmt.name.lowercase()) ||
                 (fmt == DeliverableFormat.ACTION_PLAN && a.name.contains("Action Plan", ignoreCase = true)) ||
                 (fmt == DeliverableFormat.DECISION_MATRIX && a.name.contains("Matrix", ignoreCase = true)) ||
                 (fmt == DeliverableFormat.PRO_CON_LIST && a.name.contains("Pro/Con", ignoreCase = true)) ||
                 (fmt == DeliverableFormat.EXECUTIVE_BRIEF && a.name.contains("Brief", ignoreCase = true)) ||
                 (fmt == DeliverableFormat.DECISION_SUMMARY && (a.name.contains("Summary", ignoreCase = true) || a.type.equals("Summary", ignoreCase = true))))
            }
            if (art != null && art.content.isNotBlank()) return art.content

            val roundDel = discussion.artifacts.firstOrNull { a ->
                (a.round == milestoneRound || a.timestamp == "Round $milestoneRound" || a.id.endsWith("_r$milestoneRound") || a.name.contains("(Round $milestoneRound)")) &&
                (a.type.equals("Deliverable", ignoreCase = true) || a.id.startsWith("art_del") || a.type.equals("Summary", ignoreCase = true) || a.id.startsWith("art_sum"))
            }
            if (roundDel != null && roundDel.content.isNotBlank()) return roundDel.content

            val maxAgentRound = discussion.transcript.filterNot { it.isUserComment || it.isError }.maxOfOrNull { it.round } ?: 1
            if (milestoneRound >= maxAgentRound && (!discussion.summary.isNullOrBlank() || !discussion.conclusion.isNullOrBlank())) {
                return discussion.summary ?: discussion.conclusion
            }
            return null
        }

        val art = discussion.artifacts.firstOrNull { a ->
            !a.id.contains("_r") && !a.name.contains("(Round ") &&
            (a.name.contains(fmt.name.replace('_', ' '), ignoreCase = true) ||
            a.id.startsWith(fmt.name.lowercase()) ||
            (fmt == DeliverableFormat.ACTION_PLAN && a.name.contains("Action Plan", ignoreCase = true)) ||
            (fmt == DeliverableFormat.DECISION_MATRIX && a.name.contains("Matrix", ignoreCase = true)) ||
            (fmt == DeliverableFormat.PRO_CON_LIST && a.name.contains("Pro/Con", ignoreCase = true)) ||
            (fmt == DeliverableFormat.EXECUTIVE_BRIEF && a.name.contains("Brief", ignoreCase = true)) ||
            (fmt == DeliverableFormat.DECISION_SUMMARY && (a.name.contains("Summary", ignoreCase = true) || a.type.equals("Summary", ignoreCase = true))))
        }
        if (art != null && art.content.isNotBlank()) return art.content

        if (discussion.config.deliverable.format == fmt && !discussion.deliverable.isNullOrBlank()) {
            return discussion.deliverable
        }

        if (fmt == DeliverableFormat.DECISION_SUMMARY && (!discussion.summary.isNullOrBlank() || !discussion.conclusion.isNullOrBlank())) {
            return discussion.summary ?: discussion.conclusion
        }

        return null
    }

    val outcomeSummary = (if (milestoneRound != null) resolveContentForFormat(DeliverableFormat.DECISION_SUMMARY) else (discussion.summary ?: discussion.conclusion))
        ?: resolveContentForFormat(DeliverableFormat.DECISION_SUMMARY)
        ?: discussion.summary
        ?: discussion.conclusion
        ?: discussion.deliverable
        ?: ""

    val isConsensus = discussion.transcript.takeLast(discussion.config.agents.size.coerceAtLeast(1))
        .any { it.content.trim().lowercase().startsWith("agreed") }

    val milestoneArtifact = if (milestoneRound != null) {
        discussion.artifacts.find { it.round == milestoneRound || it.id.contains("_r$milestoneRound") || it.timestamp.contains("Round $milestoneRound") || it.name.contains("(Round $milestoneRound)") }
    } else {
        discussion.artifacts.find { it.type.equals("deliverable", ignoreCase = true) || it.type.equals("summary", ignoreCase = true) }
    }
    val generatedTimeLabel = when {
        milestoneArtifact?.timestampMs != null && milestoneArtifact.timestampMs > 0L -> formatMessageTimestamp(milestoneArtifact.timestampMs)
        milestoneArtifact?.timestamp?.isNotBlank() == true && !milestoneArtifact.timestamp.startsWith("Round") && !milestoneArtifact.timestamp.equals("Live", ignoreCase = true) -> milestoneArtifact.timestamp
        milestoneRound != null -> discussion.transcript.findLast { it.round == milestoneRound }?.let { if (it.timestampMs > 0L) formatMessageTimestamp(it.timestampMs) else null }
        else -> discussion.transcript.lastOrNull()?.let { if (it.timestampMs > 0L) formatMessageTimestamp(it.timestampMs) else null }
    }

    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val activeSelectedFormat = activeFormat
    val activeDeliverableContent = activeSelectedFormat?.let { resolveContentForFormat(it) }

    if (milestoneRound == null && (discussion.isConsensusReached || discussion.earlyExitReason == "CONSENSUS")) {
        val maxRound = discussion.transcript.filter { !it.isError && !it.isSystem }.maxOfOrNull { it.round } ?: 1
        val configuredRounds = discussion.config.maxRounds
        val savedRounds = (configuredRounds - maxRound).coerceAtLeast(0)
        val agentCount = discussion.config.agents.size
        val savedTurns = savedRounds * agentCount
        val estimatedTokensSaved = savedTurns * 2500
        val estimatedSpendSavedUsd = (estimatedTokensSaved / 1000.0) * 0.04

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF2E7D32).copy(alpha = if (cc.isDark) 0.15f else 0.08f),
            border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "⚡ Early Consensus Reached in Round $maxRound of $configuredRounds",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (cc.isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
                )
                Spacer(Modifier.height(4.dp))
                val spendFormatted = ((estimatedSpendSavedUsd * 100).toInt() / 100.0).toString()
                Text(
                    "Saved approximately $savedTurns turns (~$estimatedTokensSaved tokens / ~$$spendFormatted API spend) by avoiding redundant rounds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textPrimary
                )
            }
        }
    }

    if (milestoneRound == null && (discussion.status == DiscussionStatus.COMPLETED_WITH_WARNING || discussion.warning != null)) {
        val completedTurns = discussion.transcript.count { !it.isError && !it.isSystem && !it.isUserComment }
        val maxRound = discussion.transcript.filter { !it.isError }.maxOfOrNull { it.round } ?: 1
        val interruptedRound = discussion.transcript.findLast { it.isError }?.round ?: (maxRound + 1)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cc.accent.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, cc.accent.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "ℹ️ Deliberation Concluded at Round $maxRound",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = cc.textPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    discussion.warning ?: "A network interruption occurred in Round $interruptedRound. The Deliberation Moderator successfully synthesized the final outcome from the $completedTurns completed turns.",
                    style = MaterialTheme.typography.bodySmall,
                    color = cc.textMuted
                )
            }
        }
    }
    val isGeneratingActive = activeSelectedFormat != null && generatingFormat == activeSelectedFormat

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = moderatorColor.copy(alpha = if (cc.isDark) 0.05f else 0.03f).compositeOver(cc.panelAlt),
        border = BorderStroke(1.25.dp, moderatorColor.copy(alpha = if (cc.isDark) 0.55f else 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
        ) {
            // Signature vertical moderator accent bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(moderatorColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(18.dp)
            ) {
                // ── 1. Header: Moderator Identity, Badge, & Tools ─────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(moderatorColor.copy(alpha = 0.15f))
                                .border(1.dp, moderatorColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.AutoAwesome,
                                contentDescription = "Moderator",
                                tint = moderatorColor,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Moderator ($moderatorName)",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp
                                    ),
                                    color = cc.textPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = moderatorColor.copy(alpha = 0.15f),
                                    border = BorderStroke(0.75.dp, moderatorColor.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = if (milestoneRound != null) "ROUND $milestoneRound OUTCOME" else if (isConsensus) "CONSENSUS OUTCOME" else "OUTCOME RESOLUTION",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.5.sp,
                                            letterSpacing = 0.4.sp
                                        ),
                                        color = moderatorColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Deliberation synthesis & reply to topic",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Normal,
                                        fontSize = 11.sp
                                    ),
                                    color = cc.textMuted
                                )
                                if (!generatedTimeLabel.isNullOrBlank()) {
                                    Text(
                                        "· $generatedTimeLabel",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = cc.textMuted
                                    )
                                }
                            }
                        }
                    }

                    // Header Tools
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ThemedTooltipBox(if (copied) "Copied!" else "Copy Consensus Outcome") {
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(outcomeSummary))
                                    copied = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy Summary",
                                    tint = if (copied) cc.accent else cc.textMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        if (onExportDeliverable != null && outcomeSummary.isNotBlank()) {
                            ThemedTooltipBox("Save Outcome to disk") {
                                IconButton(
                                    onClick = {
                                        val fileName = "${discussion.name.replace(' ', '_').lowercase()}_outcome.md"
                                        onExportDeliverable(outcomeSummary, fileName)
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Download,
                                        contentDescription = "Export Outcome",
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }

                        if (onOpenArtifacts != null) {
                            ThemedTooltipBox("Open in Artifacts Panel") {
                                IconButton(
                                    onClick = onOpenArtifacts,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.OpenInNew,
                                        contentDescription = "Open in Artifacts Panel",
                                        tint = cc.textMuted,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (discussion.attachedFiles.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .padding(top = 4.dp, bottom = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onOpenArtifacts?.invoke() }
                    ) {
                        Icon(
                            Icons.Outlined.Description,
                            contentDescription = "Grounded Documents",
                            tint = moderatorColor,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            "Grounded in ${discussion.attachedFiles.size} attached document${if (discussion.attachedFiles.size > 1) "s" else ""} · Authoritative source citation",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                            color = moderatorColor
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)
                Spacer(Modifier.height(12.dp))

                // ── 2. Crisp Outcome Summary & Answer to Topic ─────────
                if (outcomeSummary.isNotBlank()) {
                    SelectionContainer {
                        MarkdownText(
                            text = outcomeSummary,
                            cc = cc,
                            textColor = cc.textPrimary,
                            baseFontSize = typographySettings.fontSizeSp.sp,
                            lineHeightMultiplier = typographySettings.lineSpacingMultiplier
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = moderatorColor)
                        Text(
                            "Synthesizing consensus outcome summary…",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            color = cc.textMuted
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── 3. Generative Deliverables Buttons Section ─────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(cc.panel.copy(alpha = 0.6f))
                        .border(0.75.dp, cc.border.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Layers,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Generate Deliverables",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                ),
                                color = cc.textPrimary
                            )
                        }
                        Text(
                            "One-click synthesis from debate consensus",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = cc.textMuted
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Horizontal Scrollable Generative Buttons Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        formats.forEach { (fmt, info) ->
                            val (label, icon) = info
                            val fmtContent = resolveContentForFormat(fmt)
                            val isAvailable = !fmtContent.isNullOrBlank()
                            val isGeneratingThis = generatingFormat == fmt
                            val isSelected = activeSelectedFormat == fmt && isAvailable

                            val pillShape = RoundedCornerShape(8.dp)
                            Surface(
                                shape = pillShape,
                                color = when {
                                    isSelected -> cc.accent
                                    isAvailable -> if (cc.isDark) Color(0xFF242531) else Color(0xFFEBECEF)
                                    else -> cc.panelAlt
                                },
                                border = BorderStroke(
                                    width = if (isSelected) 1.dp else 0.75.dp,
                                    color = when {
                                        isSelected -> cc.accent
                                        isAvailable -> cc.accent.copy(alpha = 0.5f)
                                        else -> cc.border.copy(alpha = 0.4f)
                                    }
                                ),
                                modifier = Modifier
                                    .clip(pillShape)
                                    .clickable {
                                        if (isAvailable) {
                                            activeFormat = if (activeSelectedFormat == fmt) null else fmt
                                        } else if (!isGeneratingThis) {
                                            activeFormat = fmt
                                            onGenerateFormat?.invoke(fmt)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    when {
                                        isGeneratingThis -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 1.8.dp,
                                                color = if (isSelected) Color.White else cc.accent
                                            )
                                        }
                                        isAvailable -> {
                                            Icon(
                                                Icons.Outlined.Check,
                                                contentDescription = "Generated",
                                                tint = if (isSelected) Color.White else cc.accent,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                        else -> {
                                            Icon(
                                                Icons.Outlined.Add,
                                                contentDescription = "Generate $label",
                                                tint = cc.textMuted,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected || isAvailable) FontWeight.SemiBold else FontWeight.Medium
                                        ),
                                        color = when {
                                            isSelected -> Color.White
                                            isAvailable -> cc.textPrimary
                                            else -> cc.textMuted
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // ── 4. Expandable Active Deliverable Viewer Card ─────────
                    if (isGeneratingActive && activeDeliverableContent.isNullOrBlank()) {
                        val activeInfo = formats.firstOrNull { it.first == activeSelectedFormat }?.second ?: ("Deliverable" to Icons.AutoMirrored.Outlined.FactCheck)
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(cc.panelAlt, RoundedCornerShape(8.dp))
                                .border(0.75.dp, cc.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = cc.accent)
                            Column {
                                Text(
                                    "Synthesizing ${activeInfo.first}…",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                                    color = cc.textPrimary
                                )
                                Text(
                                    "Distilling debate consensus into structured ${activeInfo.first.lowercase()}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                    color = cc.textMuted
                                )
                            }
                        }
                    } else if (activeSelectedFormat != null && !activeDeliverableContent.isNullOrBlank()) {
                        val activeInfo = formats.firstOrNull { it.first == activeSelectedFormat }?.second ?: ("Deliverable" to Icons.AutoMirrored.Outlined.FactCheck)
                        val activeLabel = activeInfo.first
                        var deliverableCopied by remember { mutableStateOf(false) }
                        LaunchedEffect(deliverableCopied) {
                            if (deliverableCopied) {
                                delay(2000)
                                deliverableCopied = false
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = cc.panelAlt,
                            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(activeInfo.second, contentDescription = null, tint = cc.accent, modifier = Modifier.size(15.dp))
                                        Text(
                                            activeLabel,
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                                            color = cc.textPrimary
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        ThemedTooltipBox(if (deliverableCopied) "Copied!" else "Copy $activeLabel") {
                                            IconButton(
                                                onClick = {
                                                    clipboard.setText(AnnotatedString(activeDeliverableContent))
                                                    deliverableCopied = true
                                                },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(
                                                    if (deliverableCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                                    contentDescription = "Copy",
                                                    tint = if (deliverableCopied) cc.accent else cc.textMuted,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                        if (onExportDeliverable != null) {
                                            ThemedTooltipBox("Save $activeLabel to disk") {
                                                IconButton(
                                                    onClick = {
                                                        val fileName = "${discussion.name.replace(' ', '_').lowercase()}_${activeSelectedFormat.name.lowercase()}.md"
                                                        onExportDeliverable(activeDeliverableContent, fileName)
                                                    },
                                                    modifier = Modifier.size(26.dp)
                                                ) {
                                                    Icon(Icons.Outlined.Download, contentDescription = "Save", tint = cc.textMuted, modifier = Modifier.size(14.dp))
                                                }
                                            }
                                        }
                                        ThemedTooltipBox("Close deliverable view") {
                                            IconButton(
                                                onClick = { activeFormat = null },
                                                modifier = Modifier.size(26.dp)
                                            ) {
                                                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)
                                Spacer(Modifier.height(8.dp))

                                SelectionContainer {
                                    MarkdownText(
                                        text = activeDeliverableContent,
                                        cc = cc,
                                        textColor = cc.textPrimary,
                                        baseFontSize = typographySettings.fontSizeSp.sp,
                                        lineHeightMultiplier = typographySettings.lineSpacingMultiplier
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun ArtifactStrip(
    cc: CcPalette,
    artifact: DiscussionArtifact,
    onView: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (cc.isDark) Color(0xFF1A1B22) else Color(0xFFF8F9FA),
        border = BorderStroke(0.75.dp, if (cc.isDark) Color(0xFF2E2E38) else Color(0xFFE5E7EB)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    when (artifact.type.lowercase()) {
                        "deliverable" -> Icons.AutoMirrored.Outlined.Article
                        "summary" -> Icons.Outlined.Summarize
                        else -> Icons.AutoMirrored.Outlined.ReceiptLong
                    },
                    contentDescription = null,
                    tint = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280),
                    modifier = Modifier.size(14.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "${artifact.type.lowercase().replaceFirstChar { it.uppercase() }} generated",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = cc.textPrimary
                        )
                        val timeLabel = when {
                            artifact.timestampMs > 0L -> formatMessageTimestamp(artifact.timestampMs)
                            artifact.timestamp.isNotBlank() && !artifact.timestamp.equals("Live", ignoreCase = true) -> artifact.timestamp
                            else -> ""
                        }
                        if (timeLabel.isNotBlank()) {
                            Text(
                                "· $timeLabel",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = cc.textMuted
                            )
                        }
                    }
                    Text(
                        artifact.name + if (artifact.sizeBytes > 0) " · ${maxOf(1, artifact.sizeBytes / 1024)} KB" else "",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = cc.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (cc.isDark) Color(0xFF282832) else Color(0xFFE5E7EB),
                    border = BorderStroke(0.5.dp, if (cc.isDark) Color(0xFF3E3E48) else Color(0xFFD1D5DB)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { onView() }
                ) {
                    Text(
                        "View Artifact",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                        color = cc.textPrimary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                ThemedTooltipBox("Dismiss banner") {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss artifact banner",
                            tint = cc.textMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactsSlidingPane(
    discussion: Discussion,
    onClose: () -> Unit,
    onExport: (content: String, fileName: String) -> Unit,
    typographySettings: ChatTypographySettings,
    cc: CcPalette,
    modifier: Modifier = Modifier,
    onDeleteArtifact: ((String) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    var copiedContent by remember { mutableStateOf(false) }
    var selectedArtifact by remember { mutableStateOf<DiscussionArtifact?>(null) }
    var isRawView by remember { mutableStateOf(false) }

    val allArtifacts = remember(discussion) {
        discussion.resolveAllArtifacts().sortedWith(compareBy<DiscussionArtifact> { if (it.timestampMs > 0L) it.timestampMs else 0L }.thenBy { it.id })
    }

    Surface(
        color = cc.panel,
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selectedArtifact == null) {
                // ── LIST VIEW (ANTIGRAVITY STYLE) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (cc.isDark) Color(0xFF282832) else Color(0xFFE5E7EB)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Inventory2, contentDescription = null, tint = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280), modifier = Modifier.size(16.dp))
                        }
                        Column {
                            Text(
                                "Artifacts",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                                color = cc.textPrimary
                            )
                            Text(
                                "${allArtifacts.size} saved file${if (allArtifacts.size != 1) "s" else ""}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(17.dp))
                    }
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)

                if (allArtifacts.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Outlined.FolderZip, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(36.dp))
                            Text("No artifacts yet", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = cc.textPrimary)
                            Text(
                                "Deliverables, summaries, and transcripts appear here as the debate progresses.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = cc.textMuted,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(allArtifacts.size) { idx ->
                            val art = allArtifacts[idx]
                            val effectiveRound = discussion.effectiveRoundFor(art)
                            val formattedDateTime = when {
                                art.timestampMs > 0L -> com.dialex.util.formatDateTime(art.timestampMs)
                                art.timestamp.isNotBlank() && !art.timestamp.equals("Live", ignoreCase = true) -> art.timestamp
                                else -> "Attached"
                            }
                            val sizeLabel = "${maxOf(1, art.sizeBytes / 1024)} KB"

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = cc.panelAlt,
                                border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedArtifact = art
                                        isRawView = false
                                        copiedContent = false
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (cc.isDark) Color(0xFF25262E) else Color(0xFFF3F4F6)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                when (art.type.lowercase()) {
                                                    "deliverable" -> Icons.AutoMirrored.Outlined.Article
                                                    "summary" -> Icons.Outlined.Summarize
                                                    else -> Icons.AutoMirrored.Outlined.ReceiptLong
                                                },
                                                contentDescription = null,
                                                tint = if (cc.isDark) Color(0xFF9CA3AF) else Color(0xFF6B7280),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                art.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                                color = cc.textPrimary,
                                                maxLines = 1
                                            )
                                            Spacer(Modifier.height(3.dp))
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Surface(
                                                    shape = RoundedCornerShape(3.dp),
                                                    color = if (cc.isDark) Color(0xFF282832) else Color(0xFFE5E7EB)
                                                ) {
                                                    Text(
                                                        art.type.uppercase(),
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                        color = if (cc.isDark) Color(0xFFD1D5DB) else Color(0xFF4B5563),
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                    )
                                                }
                                                if (effectiveRound != null) {
                                                    Surface(
                                                        shape = RoundedCornerShape(3.dp),
                                                        color = MaterialTheme.colorScheme.primary.copy(alpha = if (cc.isDark) 0.22f else 0.12f)
                                                    ) {
                                                        Text(
                                                            "ROUND $effectiveRound",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    "$sizeLabel · $formattedDateTime",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                    color = cc.textMuted
                                                )
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (onDeleteArtifact != null && art.id.isNotBlank() && !art.id.startsWith("pseudo_")) {
                                            IconButton(
                                                onClick = { onDeleteArtifact(art.id) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Outlined.Delete,
                                                    contentDescription = "Delete artifact",
                                                    tint = cc.textMuted.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "Open Document",
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ── DOCUMENT VIEWER VIEW (ANTIGRAVITY STYLE) ──
                val currentArt = selectedArtifact!!

                // Document Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = cc.panelAlt,
                            border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { selectedArtifact = null }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "All Artifacts", tint = cc.textPrimary, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("All", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                            }
                        }

                        Text(
                            currentArt.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = cc.textPrimary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Raw / Preview Toggle
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isRawView) (if (cc.isDark) Color(0xFF2E2E38) else Color(0xFFE5E7EB)) else cc.panelAlt,
                            border = BorderStroke(0.5.dp, if (isRawView) (if (cc.isDark) Color(0xFF6B7280) else Color(0xFF9CA3AF)) else cc.border.copy(alpha = 0.4f)),
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { isRawView = !isRawView }
                        ) {
                            Text(
                                if (isRawView) "Raw" else "Preview",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isRawView) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isRawView) cc.textPrimary else cc.textMuted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }

                        // Copy Button (Direct 1-Click)
                        ThemedTooltipBox(if (copiedContent) "Copied!" else "Copy to clipboard") {
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(currentArt.content))
                                    copiedContent = true
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    if (copiedContent) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = if (copiedContent) Color(0xFF4CAF50) else cc.textMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }

                        // More Copy Options Menu
                        var detailOverflowOpen by remember { mutableStateOf(false) }
                        Box {
                            ThemedTooltipBox("More options") {
                                IconButton(
                                    onClick = { detailOverflowOpen = true },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Outlined.MoreVert, contentDescription = "More Options", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                }
                            }

                            DropdownMenu(
                                expanded = detailOverflowOpen,
                                onDismissRequest = { detailOverflowOpen = false },
                                modifier = Modifier.background(cc.panel)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                "Copy with Topic & Title",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = cc.textPrimary
                                            )
                                            Text(
                                                "Includes 'Topic: ...' and '${currentArt.name}:'",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = cc.textMuted
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null, tint = cc.accent, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        detailOverflowOpen = false
                                        val topicText = discussion.config.topic.ifBlank { discussion.name }
                                        val formatted = buildString {
                                            append("Topic: ")
                                            append(topicText)
                                            append("\n\n")
                                            append("${currentArt.name}:\n")
                                            append(currentArt.content)
                                        }
                                        clipboard.setText(AnnotatedString(formatted))
                                        copiedContent = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                "Copy Raw Content",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                color = cc.textPrimary
                                            )
                                            Text(
                                                "Markdown body only",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = cc.textMuted
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = cc.textMuted, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        detailOverflowOpen = false
                                        clipboard.setText(AnnotatedString(currentArt.content))
                                        copiedContent = true
                                    }
                                )
                            }
                        }

                        // Download / Export Button
                        ThemedTooltipBox("Download / Export") {
                            IconButton(
                                onClick = { onExport(currentArt.content, currentArt.name) },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Outlined.FileDownload, contentDescription = "Download", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                            }
                        }

                        // Delete Button (only for real artifacts, not pseudo ones)
                        if (onDeleteArtifact != null && currentArt.id.isNotBlank() && !currentArt.id.startsWith("pseudo_")) {
                            ThemedTooltipBox("Delete artifact") {
                                IconButton(
                                    onClick = {
                                        onDeleteArtifact(currentArt.id)
                                        selectedArtifact = null
                                    },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete artifact", tint = cc.textMuted, modifier = Modifier.size(15.dp))
                                }
                            }
                        }

                        // Close Entire Pane
                        ThemedTooltipBox("Close Artifacts Pane") {
                            IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)

                // Document Metadata Subheader
                val currentEffectiveRound = discussion.effectiveRoundFor(currentArt)
                val roundPrefix = if (currentEffectiveRound != null) "Round $currentEffectiveRound · " else ""
                val formattedViewerDateTime = when {
                    currentArt.timestampMs > 0L -> com.dialex.util.formatDateTime(currentArt.timestampMs)
                    currentArt.timestamp.isNotBlank() && !currentArt.timestamp.equals("Live", ignoreCase = true) -> currentArt.timestamp
                    else -> "Attached"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(cc.panelAlt.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "$roundPrefix${currentArt.format.uppercase()} · ${maxOf(1, currentArt.sizeBytes / 1024)} KB · ~${currentArt.content.length / 4} tokens",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = cc.textMuted
                    )
                    Text(
                        formattedViewerDateTime,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = cc.textMuted
                    )
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.25f), thickness = 0.5.dp)

                // Document Viewport
                SelectionContainer(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (isRawView) {
                        Text(
                            text = currentArt.content,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = cc.textPrimary
                        )
                    } else {
                        MarkdownText(
                            text = currentArt.content,
                            cc = cc,
                            textColor = cc.textPrimary,
                            baseFontSize = 13.sp,
                            lineHeightMultiplier = 1.35f
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscussionSummaryDialog(
    discussion: Discussion,
    onDismiss: () -> Unit,
    onExportMarkdown: (content: String, fileName: String) -> Unit,
    typographySettings: ChatTypographySettings,
    cc: CcPalette
) {
    val clipboard = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier
                .widthIn(min = 460.dp, max = 680.dp)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cc.accent.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Summarize, contentDescription = null, tint = cc.accent, modifier = Modifier.size(18.dp))
                        }
                        Column {
                            Text(
                                "Executive Summary & Consensus",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = cc.textPrimary
                            )
                            Text(
                                discussion.name.ifBlank { "Dialex Debate" },
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(17.dp))
                    }
                }

                HorizontalDivider(color = cc.border.copy(alpha = 0.35f), thickness = 0.75.dp)

                // Topic Callout Banner
                if (discussion.config.topic.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "TOPIC",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                color = cc.accent,
                                modifier = Modifier.padding(top = 1.dp)
                            )
                            Text(
                                discussion.config.topic,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textPrimary,
                                maxLines = 2
                            )
                        }
                    }
                }

    // Build list of only available deliverables (label -> content)
    val availableDeliverables = remember(discussion) {
        val list = mutableListOf<Pair<String, String>>()
        val summaryContent = discussion.summary ?: discussion.conclusion
        if (summaryContent != null) list.add("Summary" to summaryContent)
        discussion.deliverable?.let { del ->
            val label = discussion.config.deliverable.format.name
                .lowercase().replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
            val key = if (label.equals("Summary", ignoreCase = true) || label.equals("Decision Summary", ignoreCase = true)) "Decision Summary" else label
            if (list.none { it.first.equals(key, ignoreCase = true) }) {
                list.add(key to del)
            }
        }
        discussion.artifacts
            .filter { it.type.equals("Deliverable", ignoreCase = true) && it.content.isNotBlank() }
            .forEach { art ->
                if (list.none { it.first.equals(art.name, ignoreCase = true) }) {
                    list.add(art.name to art.content)
                }
            }
        list.ifEmpty { listOf("Summary" to (discussion.summary ?: discussion.conclusion ?: discussion.deliverable ?: "Summary synthesis in progress…")) }
    }

    // selectedLabel drives both pill highlight and content shown
    var selectedLabel by remember(availableDeliverables) {
        mutableStateOf(availableDeliverables.firstOrNull()?.first ?: "Summary")
    }
    val rawSummary = remember(selectedLabel, availableDeliverables) {
        availableDeliverables.firstOrNull { it.first == selectedLabel }?.second
            ?: availableDeliverables.firstOrNull()?.second
            ?: "Summary synthesis in progress…"
    }

    // Format Selector Pills — only available items
    if (availableDeliverables.size > 1) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            availableDeliverables.forEach { (label, _) ->
                val isSelected = selectedLabel == label
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) (if (cc.isDark) Color(0xFF282832) else Color(0xFFE5E7EB)) else cc.panelAlt,
                    border = BorderStroke(0.75.dp, if (isSelected) (if (cc.isDark) Color(0xFF6B7280) else Color(0xFF9CA3AF)) else cc.border.copy(alpha = 0.45f)),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { selectedLabel = label }
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = if (isSelected) cc.textPrimary else cc.textMuted,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

                // Scrollable Summary Content Box
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cc.panelAlt.copy(alpha = 0.6f),
                    border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer(
                        modifier = Modifier
                            .heightIn(min = 140.dp, max = 360.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        MarkdownText(
                            text = rawSummary,
                            cc = cc,
                            textColor = cc.textPrimary,
                            baseFontSize = 13.sp,
                            lineHeightMultiplier = 1.35f
                        )
                    }
                }

                // Action Bar Footer
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Export Markdown
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = cc.panelAlt,
                        border = BorderStroke(0.75.dp, cc.border),
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val fileName = discussion.resolveExportFileName("summary")
                                onExportMarkdown(rawSummary, fileName)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = "Export", tint = cc.textPrimary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Export .md", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium), color = cc.textPrimary)
                        }
                    }

                    // Right: Copy Summary CTA & Done button
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isCopied) Color(0xFF4CAF50).copy(alpha = 0.15f) else cc.accent,
                            border = BorderStroke(0.75.dp, if (isCopied) Color(0xFF4CAF50) else cc.accent),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    clipboard.setText(AnnotatedString(rawSummary))
                                    isCopied = true
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                    contentDescription = "Copy Summary",
                                    tint = if (isCopied) Color(0xFF4CAF50) else Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if (isCopied) "Copied to Clipboard!" else "Copy Summary",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = if (isCopied) Color(0xFF4CAF50) else Color.White
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = cc.panelAlt,
                            border = BorderStroke(0.75.dp, cc.border),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onDismiss)
                        ) {
                            Text(
                                "Done",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                                color = cc.textPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageModal(usageBreakdown: List<AgentUsage>, onDismiss: () -> Unit, cc: CcPalette) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(cc.panel)
                .border(BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Token Usage Breakdown",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = cc.textPrimary
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = cc.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                usageBreakdown.forEach { usage ->
                    Surface(
                        color = cc.panelAlt,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    usage.agentName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                    color = cc.textPrimary
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${formatTokenCount(usage.tokensIn)} in · ${formatTokenCount(usage.tokensOut)} out",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                    color = cc.textMuted
                                )
                            }
                            Text(
                                "~\$${"%.3f".format(usage.estimatedCostUsd)}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = cc.textPrimary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                GradientButton(
                    text = "Close",
                    onClick = onDismiss,
                    height = 34.dp
                )
            }
        }
    }
}

@Composable
private fun DebateErrorDialog(
    discussion: Discussion,
    errorMessage: String,
    agentProvider: Provider?,
    round: Int?,
    onDismiss: () -> Unit,
    onResume: () -> Unit,
    cc: CcPalette
) {
    val clipboard = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }
    val errorInfo = remember(errorMessage, agentProvider) {
        parseTurnError(errorMessage, agentProvider ?: Provider.ANTHROPIC)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier
                .widthIn(min = 460.dp, max = 640.dp)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(errorInfo.badgeColor.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(errorInfo.badgeIcon, fontSize = 17.sp)
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    errorInfo.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                                    color = cc.textPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = errorInfo.badgeColor.copy(alpha = 0.15f),
                                    border = BorderStroke(0.5.dp, errorInfo.badgeColor.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = errorInfo.badgeLabel.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                        color = errorInfo.badgeColor,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            val metaText = buildString {
                                if (agentProvider != null) {
                                    append(agentProvider.brandName())
                                }
                                if (round != null && round > 0) {
                                    if (isNotEmpty()) append(" • ")
                                    append("Round $round")
                                }
                                if (isEmpty()) append(discussion.name.ifBlank { "Dialex Debate" })
                            }
                            Text(
                                metaText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(18.dp))
                    }
                }

                // Friendly advice banner
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = errorInfo.badgeColor.copy(alpha = if (cc.isDark) 0.08f else 0.05f),
                    border = BorderStroke(0.75.dp, errorInfo.badgeColor.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = errorInfo.summary,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = cc.textPrimary.copy(alpha = 0.88f)
                        )
                        if (!errorInfo.resetSchedule.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Outlined.AccessTime, contentDescription = null, tint = errorInfo.badgeColor, modifier = Modifier.size(14.dp))
                                Text(
                                    "Resets at: ${errorInfo.resetSchedule}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                                    color = errorInfo.badgeColor
                                )
                            }
                        }
                    }
                }

                // Error Details Container
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cc.panelAlt,
                    border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.5.sp,
                                    lineHeight = 17.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                color = cc.textMuted
                            )
                        }
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(errorMessage))
                            isCopied = true
                        },
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = cc.textPrimary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            if (isCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            tint = if (isCopied) Color(0xFF4CAF50) else cc.textMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isCopied) "Copied" else "Copy Error Details", fontSize = 12.sp)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Close", color = cc.textMuted, fontSize = 12.sp)
                        }

                        GradientButton(
                            text = "Retry Debate",
                            icon = Icons.Outlined.PlayArrow,
                            onClick = {
                                onDismiss()
                                onResume()
                            },
                            height = 32.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueDebateDialog(
    cc: CcPalette,
    currentMaxRound: Int,
    configuredMaxRounds: Int,
    isUnlimited: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (additionalRounds: Int, unlimited: Boolean) -> Unit
) {
    var selectedPreset by remember { mutableStateOf<Int?>(3) }
    var isUnlimitedSelected by remember { mutableStateOf(false) }
    var customRoundsText by remember { mutableStateOf("3") }
    var isCustom by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cc.panel,
            border = BorderStroke(1.dp, cc.border.copy(alpha = 0.6f)),
            modifier = Modifier
                .widthIn(min = 420.dp, max = 520.dp)
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cc.accent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                "Continue Discussion",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                                color = cc.textPrimary
                            )
                            Text(
                                "Currently completed Round $currentMaxRound",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                                color = cc.textMuted
                            )
                        }
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close", tint = cc.textMuted, modifier = Modifier.size(16.dp))
                    }
                }

                Text(
                    "Choose how many additional rounds you would like the agents to deliberate:",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                    color = cc.textPrimary
                )

                // Quick preset pills: +1, +3, +5, +10
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 3, 5, 10).forEach { rounds ->
                        val isSelected = !isUnlimitedSelected && !isCustom && selectedPreset == rounds
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) cc.accent.copy(alpha = 0.15f) else cc.bg,
                            border = BorderStroke(1.dp, if (isSelected) cc.accent else cc.border.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    isUnlimitedSelected = false
                                    isCustom = false
                                    selectedPreset = rounds
                                    customRoundsText = rounds.toString()
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "+$rounds",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium),
                                    color = if (isSelected) cc.accent else cc.textPrimary
                                )
                            }
                        }
                    }
                }

                // Custom & Unlimited Options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isCustomSelected = isCustom && !isUnlimitedSelected
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCustomSelected) cc.accent.copy(alpha = 0.15f) else cc.bg,
                        border = BorderStroke(1.dp, if (isCustomSelected) cc.accent else cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                isUnlimitedSelected = false
                                isCustom = true
                                selectedPreset = null
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Custom",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isCustomSelected) FontWeight.Bold else FontWeight.Medium),
                                color = if (isCustomSelected) cc.accent else cc.textPrimary
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUnlimitedSelected) cc.accent.copy(alpha = 0.15f) else cc.bg,
                        border = BorderStroke(1.dp, if (isUnlimitedSelected) cc.accent else cc.border.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                isUnlimitedSelected = true
                                isCustom = false
                                selectedPreset = null
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Unlimited",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (isUnlimitedSelected) FontWeight.Bold else FontWeight.Medium),
                                color = if (isUnlimitedSelected) cc.accent else cc.textPrimary
                            )
                        }
                    }
                }

                // If Custom is selected, show input field
                if (isCustom && !isUnlimitedSelected) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Number of additional rounds:",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = cc.textMuted
                        )
                        OutlinedTextField(
                            value = customRoundsText,
                            onValueChange = { str ->
                                val filtered = str.filter { it.isDigit() }
                                customRoundsText = filtered
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = cc.textPrimary),
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("e.g. 7", color = cc.textMuted) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = cc.accent,
                                unfocusedBorderColor = cc.border.copy(alpha = 0.5f),
                                focusedTextColor = cc.textPrimary,
                                unfocusedTextColor = cc.textPrimary,
                                cursorColor = cc.accent
                            )
                        )
                    }
                }

                // Summary hint
                Text(
                    text = when {
                        isUnlimitedSelected -> "Agents will continue debating indefinitely until manually paused or concluded."
                        isCustom -> {
                            val add = customRoundsText.toIntOrNull() ?: 1
                            "Will run +$add more round(s), up to Round ${currentMaxRound + add}."
                        }
                        else -> {
                            val add = selectedPreset ?: 3
                            "Will run +$add more round(s), up to Round ${currentMaxRound + add}."
                        }
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = cc.textMuted
                )

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Cancel", color = cc.textMuted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (isUnlimitedSelected) {
                                onConfirm(0, true)
                            } else {
                                val rounds = if (isCustom) {
                                    (customRoundsText.toIntOrNull() ?: 1).coerceAtLeast(1)
                                } else {
                                    selectedPreset ?: 3
                                }
                                onConfirm(rounds, false)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cc.accent,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Continue Debate", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
