@file:Suppress("DEPRECATION")

package com.dialex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.dialex.util.formatTokenCount
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dialex.export.exportFileName
import com.dialex.export.toMarkdown
import com.dialex.model.Agent
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import com.dialex.model.DiscussionStatus
import com.dialex.model.Provider
import com.dialex.model.RoundMode
import com.dialex.model.brandName
import com.dialex.model.label
import com.dialex.theme.CcPalette
import com.dialex.theme.LocalCcColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Resolves a transcript message's display name using seat ID, author snapshot, or provider fallback. */
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

/** Lets any composable under [ChatView] pop a brief toast without threading a callback
 * through every layer — set once here, read by [BubbleActions]. */
private val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Transient states of a "Generate AI handoff prompt" request — the successful result isn't
 * here, it's [Discussion.handoffPrompt] (persisted, so it stays put); this only tracks the
 * in-flight/failed states while a request is actually running. */
private sealed interface HandoffState {
    data object Idle : HandoffState
    data object Loading : HandoffState
    data class Failed(val message: String) : HandoffState
}

/** Whoever should speak next while RUNNING, by the same round-robin math the orchestrator
 * uses — null once nothing is left to wait on. Drives the typing-indicator bubble. */
private fun nextSpeaker(discussion: Discussion): Agent? {
    if (discussion.status != DiscussionStatus.RUNNING) return null
    val agents = discussion.config.agents
    if (agents.isEmpty()) return null
    val config = discussion.config
    if (config.roundMode == RoundMode.FIXED && discussion.transcript.size >= config.maxRounds * agents.size) {
        return config.primary // final decision turn
    }
    return agents[discussion.transcript.size % agents.size]
}

/** Bubbles take more width in a narrow pane (nothing to gain from short-lining them) and
 * less in a wide one (a full-width line of text is hard to read), scaling smoothly
 * between 95% and 70% of the available pane width. */
private fun bubbleWidthFraction(paneWidth: Dp): Float {
    val narrow = 420f
    val wide = 1100f
    val t = ((paneWidth.value - narrow) / (wide - narrow)).coerceIn(0f, 1f)
    return 0.95f - t * 0.25f
}

/**
 * Renders the debate transcript like a chat: the primary agent (defaults to Claude,
 * configurable) always on the left — it hosts every debate — the other seat(s) on the
 * right in their own colors. Scrollable, auto-follows, and shows a typing-indicator bubble
 * for whoever's turn is next while running.
 *
 * Edit/restart isn't offered directly here — it's one tap behind the "⋮" menu, and always
 * goes through the config screen ([onEditConfig]), so there's exactly one place that does
 * it. [onExport] hands the platform a ready-to-save Markdown string + suggested file name
 * (desktop/mobile each provide their own "save as" flow) — null hides the export button.
 */
@Composable
fun ChatView(
    discussion: Discussion,
    onPause: () -> Unit = {},
    onHardStop: () -> Unit = {},
    onResume: () -> Unit = {},
    onEditConfig: () -> Unit = {},
    onExport: ((markdown: String, suggestedFileName: String) -> Unit)? = null,
    /** Asks the primary agent to summarize the discussion into a prompt another AI could
     * pick up cold; the result gets copied to the clipboard. Null hides the menu item. */
    onGenerateHandoff: (suspend () -> String)? = null,
    /** Persists the generated prompt onto the discussion so it survives navigating away and
     * back (or an app restart) instead of vanishing like transient UI state would. */
    onHandoffGenerated: (String) -> Unit = {},
) {
    val cc = LocalCcColors.current
    val listState = rememberLazyListState()
    val nextSpeaker = nextSpeaker(discussion)
    val snackbarHostState = remember { SnackbarHostState() }
    val toastScope = rememberCoroutineScope()

    // Pause only takes effect once the in-flight turn finishes — there's no way to abort a
    // network call or subprocess mid-flight without risking corrupt state. That delay used
    // to look like "nothing happened"; this tracks the request locally so the chat can show
    // it's queued, and clears itself once the discussion actually leaves RUNNING. Hard stop
    // doesn't need this — it kills the turn immediately instead of waiting.
    var pauseRequested by remember(discussion.id) { mutableStateOf(false) }
    LaunchedEffect(discussion.status) {
        if (discussion.status != DiscussionStatus.RUNNING) pauseRequested = false
    }

    // Loading/Failed are transient (a re-attempt just fires another request) — the Ready
    // result itself lives on discussion.handoffPrompt so it's stable across recompositions.
    var handoff by remember(discussion.id) { mutableStateOf<HandoffState>(HandoffState.Idle) }

    LaunchedEffect(discussion.transcript.size, nextSpeaker, handoff, discussion.handoffPrompt, discussion.conclusion) {
        // Item 0 is the context header, so transcript item N sits at list index N+1 — this
        // accounts for that offset, plus one item each for a typing/status bubble, the
        // conclusion, and the handoff bubble (always last) when present.
        var lastIndex = discussion.transcript.size
        if (nextSpeaker != null) lastIndex++
        if (discussion.conclusion != null) lastIndex++
        if (handoff !is HandoffState.Idle || discussion.handoffPrompt != null) lastIndex++
        listState.animateScrollToItem(lastIndex)
    }

    CompositionLocalProvider(
        LocalToast provides { message ->
            toastScope.launch { snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short) }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(cc.bg)) {
                Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(discussion.name, style = MaterialTheme.typography.titleMedium)
                        val tokenTotal = discussion.transcript.sumOf { (it.tokensIn ?: 0) + (it.tokensOut ?: 0) }
                        if (tokenTotal > 0) {
                            val estimate = estimatedCostUsd(discussion.transcript)
                            val costText = if (estimate > 0) " · ~$%.2f".format(estimate) else ""
                            Text("${formatTokenCount(tokenTotal)} tokens$costText", style = MaterialTheme.typography.labelSmall, color = cc.textMuted)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (discussion.status) {
                                DiscussionStatus.RUNNING -> "running…"
                                DiscussionStatus.PAUSED -> "paused"
                                DiscussionStatus.COMPLETED, DiscussionStatus.DONE -> "🟢 Completed"
                                DiscussionStatus.COMPLETED_WITH_WARNING -> "🟡 Concluded (Turn timeout, outcome salvaged)"
                                DiscussionStatus.FAILED, DiscussionStatus.ERROR -> "🔴 Failed"
                                DiscussionStatus.DRAFT -> ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (discussion.status) {
                                DiscussionStatus.COMPLETED_WITH_WARNING -> cc.accent
                                DiscussionStatus.FAILED, DiscussionStatus.ERROR -> MaterialTheme.colorScheme.error
                                DiscussionStatus.COMPLETED, DiscussionStatus.DONE -> Color(0xFF4CAF50)
                                else -> cc.textMuted
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        when (discussion.status) {
                            DiscussionStatus.RUNNING -> {
                                HeaderIconButton(Icons.Filled.Pause, "Pause — finishes the current turn first", cc, enabled = !pauseRequested) {
                                    pauseRequested = true; onPause()
                                }
                                HeaderIconButton(Icons.Filled.Stop, "Hard stop — kills the current turn now, resumable from here", cc, tint = MaterialTheme.colorScheme.error) {
                                    onHardStop()
                                }
                            }
                            DiscussionStatus.PAUSED, DiscussionStatus.ERROR, DiscussionStatus.FAILED -> {
                                HeaderIconButton(Icons.Filled.PlayArrow, "Resume", cc, onClick = onResume)
                            }
                            else -> {}
                        }
                        if (onExport != null) {
                            HeaderIconButton(Icons.Outlined.FileDownload, "Export as Markdown", cc) {
                                onExport(discussion.toMarkdown(), discussion.exportFileName())
                            }
                        }
                        if (discussion.status != DiscussionStatus.RUNNING && discussion.status != DiscussionStatus.DRAFT) {
                            var moreOpen by remember { mutableStateOf(false) }
                            val clipboard = LocalClipboardManager.current
                            val toast = LocalToast.current
                            Box {
                                HeaderIconButton(Icons.Filled.MoreVert, "More", cc) { moreOpen = true }
                                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Copy summary") },
                                        onClick = {
                                            moreOpen = false
                                            clipboard.setText(AnnotatedString(discussion.toMarkdown()))
                                            toast("Discussion summary copied — paste it anywhere to pick up where it left off")
                                        },
                                    )
                                    if (onGenerateHandoff != null) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    when {
                                                        handoff is HandoffState.Loading -> "Asking ${discussion.config.primary.label()}…"
                                                        discussion.handoffPrompt != null -> "Regenerate AI handoff prompt"
                                                        else -> "Generate AI handoff prompt"
                                                    },
                                                )
                                            },
                                            enabled = handoff !is HandoffState.Loading && discussion.transcript.isNotEmpty(),
                                            onClick = {
                                                moreOpen = false
                                                handoff = HandoffState.Loading
                                                toastScope.launch {
                                                    handoff = try {
                                                        val prompt = onGenerateHandoff()
                                                        clipboard.setText(AnnotatedString(prompt))
                                                        toast("Handoff prompt copied — also shown below")
                                                        onHandoffGenerated(prompt)
                                                        HandoffState.Idle
                                                    } catch (t: Throwable) {
                                                        val msg = t.message ?: "Failed to generate handoff prompt"
                                                        toast(msg)
                                                        HandoffState.Failed(msg)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    DropdownMenuItem(text = { Text("Edit setup") }, onClick = { moreOpen = false; onEditConfig() })
                                }
                            }
                        }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val bubbleMaxWidth = maxWidth * bubbleWidthFraction(maxWidth)
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item { ContextHeader(cc, discussion) }
                        val primaryProvider = discussion.config.primary.provider
                        items(discussion.transcript) { msg ->
                            MessageBubble(
                                cc = cc,
                                agentName = discussion.labelFor(msg),
                                meta = if (msg.isError) "error" else "round ${msg.round}",
                                content = msg.content,
                                provider = msg.agentId,
                                isPrimary = msg.agentId == primaryProvider,
                                maxBubbleWidth = bubbleMaxWidth,
                                isError = msg.isError,
                                tokensIn = msg.tokensIn,
                                tokensOut = msg.tokensOut,
                            )
                        }
                        if (nextSpeaker != null) {
                            item { TypingBubble(cc, nextSpeaker.label(), nextSpeaker.provider, nextSpeaker.provider == primaryProvider, bubbleMaxWidth) }
                        }
                        if (pauseRequested && discussion.status == DiscussionStatus.RUNNING) {
                            item { StatusLine(cc, "Pausing — finishing ${nextSpeaker?.label() ?: discussion.config.primary.label()}'s current turn…") }
                        } else if (discussion.status == DiscussionStatus.PAUSED) {
                            item { StatusLine(cc, "Paused — press Resume to continue.") }
                        }
                        if (discussion.conclusion != null) {
                            if (discussion.status == DiscussionStatus.COMPLETED_WITH_WARNING || discussion.warning != null) {
                                item {
                                    Spacer(Modifier.height(4.dp))
                                    EmergencyConclusionBanner(cc, discussion)
                                }
                            }
                            item {
                                Spacer(Modifier.height(4.dp))
                                ConclusionBlock(cc, discussion.config.primary.label(), discussion.conclusion)
                            }
                        }
                        // Always the last bubble: persisted on the discussion (not transient
                        // UI state), so it stays put across recomposition, navigation, and
                        // app restarts instead of disappearing like a one-off toast would.
                        val primaryName = discussion.config.primary.label()
                        when (val h = handoff) {
                            is HandoffState.Loading -> item {
                                Spacer(Modifier.height(4.dp))
                                TypingBubble(cc, primaryName, primaryProvider, true, bubbleMaxWidth, label = "writing handoff prompt…")
                            }
                            is HandoffState.Failed -> item {
                                Spacer(Modifier.height(4.dp))
                                MessageBubble(cc, primaryName, "handoff prompt failed", h.message, primaryProvider, true, bubbleMaxWidth, isError = true)
                            }
                            else -> {
                                if (discussion.handoffPrompt != null) {
                                    item {
                                        Spacer(Modifier.height(4.dp))
                                        MessageBubble(cc, primaryName, "handoff prompt", discussion.handoffPrompt, primaryProvider, true, bubbleMaxWidth)
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(36.dp)) }
                    }
                }
            }
            AnimatedVisibility(
                visible = listState.canScrollForward,
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val scope = rememberCoroutineScope()
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(listState.layoutInfo.totalItemsCount) } },
                    containerColor = cc.panelAlt,
                    contentColor = cc.textPrimary,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                }
            }
            ThemedSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

/** Subtle, light-grey Material icon button for the header row — same visual language as
 * Claude Code's toolbar icons. Shows [contentDescription] as a hover tooltip. */
@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    cc: CcPalette,
    enabled: Boolean = true,
    tint: Color = cc.textMuted.copy(alpha = 0.8f),
    onClick: () -> Unit,
) {
    Tooltipped(contentDescription, cc) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(31.dp)) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(15.dp))
        }
    }
}

/** Standard Material3 hover/long-press tooltip wrapper, styled with the app's own palette
 * instead of Material3's default (which otherwise clashes with the custom theme). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tooltipped(text: String, cc: CcPalette, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(containerColor = cc.panelAlt, contentColor = cc.textPrimary) { Text(text) }
        },
        state = rememberTooltipState(),
        content = content,
    )
}

/** Light-grey italic recap of the setup — topic, context, info, round mode — scrolls
 * away with the rest of the transcript instead of pinning space at the top. */
@Composable
private fun ContextHeader(cc: CcPalette, discussion: Discussion) {
    val config = discussion.config
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cc.panelAlt.copy(alpha = 0.5f),
        border = BorderStroke(0.5.dp, cc.border.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { isExpanded = !isExpanded }
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = discussion.name.ifBlank { "Discussion" },
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = cc.textPrimary
                )
                Text(
                    text = if (isExpanded) "Hide details ▲" else "View full topic & context ▼",
                    style = MaterialTheme.typography.labelSmall,
                    color = cc.accent
                )
            }
            if (isExpanded) {
                Spacer(Modifier.height(8.dp))
                Text("Topic: ${config.topic}", style = MaterialTheme.typography.bodySmall, color = cc.textPrimary)
                if (config.commonContext.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Context: ${config.commonContext}", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                }
                if (config.commonInfo.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Directives: ${config.commonInfo}", style = MaterialTheme.typography.bodySmall, color = cc.textMuted)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Participants: ${config.agents.joinToString(", ") { it.label() }} · Mode: ${if (config.roundMode == RoundMode.FIXED) "${config.maxRounds} round(s)" else "Unlimited"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cc.textMuted
                )
            }
        }
    }
}

// Rough blended $/1K-token rate per provider — a ballpark for "is this getting expensive",
// not a billing reconciliation. CUSTOM is usually subscription-metered by whatever tool it
// wraps, not per-token, and reports no usage anyway, so it's excluded (0).
private fun blendedRatePer1kTokens(p: Provider): Double = when (p) {
    Provider.ANTHROPIC -> 6.0
    Provider.OPENAI -> 5.0
    Provider.GEMINI -> 2.0
    Provider.GROK -> 4.0
    Provider.DEEPSEEK -> 0.5
    Provider.MISTRAL -> 1.5
    Provider.OLLAMA -> 0.0
    Provider.CUSTOM -> 0.0
}

private fun estimatedCostUsd(transcript: List<DebateMessage>): Double =
    transcript.sumOf { ((it.tokensIn ?: 0) + (it.tokensOut ?: 0)) * blendedRatePer1kTokens(it.agentId) / 1000.0 }

private fun bubbleColors(cc: CcPalette, provider: Provider): Pair<Color, Color> =
    when (provider) {
        Provider.ANTHROPIC -> cc.bubbleLeft to cc.agentLeft
        Provider.GEMINI -> cc.bubbleRight to cc.agentRight
        Provider.OPENAI -> cc.bubbleThird to cc.agentThird
        Provider.CUSTOM -> cc.bubbleFourth to cc.agentFourth
        Provider.GROK -> cc.bubbleFifth to cc.agentFifth
        Provider.DEEPSEEK -> cc.bubbleSixth to cc.agentSixth
        Provider.MISTRAL -> cc.bubbleSeventh to cc.agentSeventh
        Provider.OLLAMA -> (if (cc.isDark) Color(0xFF0C2438) else Color(0xFFE0F2FE)) to Color(0xFF0EA5E9)
    }

/** Copy puts the raw reply on the clipboard; Share puts a quoted, attributed version
 * (handy for pasting into another chat/doc) — no OS share sheet in commonMain, so this is
 * the useful thing both buttons can actually do everywhere. Both confirm with a toast. */
@Composable
private fun BubbleActions(cc: CcPalette, agentName: String, meta: String, content: String) {
    val clipboard = LocalClipboardManager.current
    val toast = LocalToast.current
    Row(modifier = Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        TinyIconButton(Icons.Outlined.ContentCopy, "Copy", cc) {
            clipboard.setText(AnnotatedString(content))
            toast("Copied to clipboard")
        }
        TinyIconButton(Icons.Outlined.IosShare, "Share", cc) {
            clipboard.setText(AnnotatedString("> **$agentName** ($meta)\n>\n> ${content.replace("\n", "\n> ")}"))
            toast("Copied for sharing")
        }
    }
}

@Composable
private fun TinyIconButton(icon: ImageVector, contentDescription: String, cc: CcPalette, onClick: () -> Unit) {
    Tooltipped(contentDescription, cc) {
        IconButton(onClick = onClick, modifier = Modifier.size(21.dp)) {
            Icon(icon, contentDescription = contentDescription, tint = cc.textMuted.copy(alpha = 0.56f), modifier = Modifier.size(10.dp))
        }
    }
}

/** A failed turn renders in the same spot its agent's reply would have — same side, same
 * name — just tinted with the error color instead of getting pulled out into a separate
 * generic block, so it's obvious *whose* turn broke. */
@Composable
private fun MessageBubble(
    cc: CcPalette,
    agentName: String,
    meta: String,
    content: String,
    provider: Provider,
    isPrimary: Boolean,
    maxBubbleWidth: Dp,
    isError: Boolean = false,
    tokensIn: Int? = null,
    tokensOut: Int? = null,
) {
    val errorColor = MaterialTheme.colorScheme.error
    val (baseBubbleColor, baseNameColor) = bubbleColors(cc, provider)
    val bubbleColor = if (isError) errorColor.copy(alpha = 0.14f) else baseBubbleColor
    val nameColor = if (isError) errorColor else baseNameColor
    Column(horizontalAlignment = if (isPrimary) Alignment.Start else Alignment.End, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isPrimary) Arrangement.Start else Arrangement.End) {
            Column(
                Modifier.widthIn(max = maxBubbleWidth)
                    .clip(RoundedCornerShape(6.dp))
                    .background(bubbleColor)
                    .padding(12.dp),
            ) {
                Text("$agentName · $meta", style = MaterialTheme.typography.labelMedium, color = nameColor)
                Spacer(Modifier.height(4.dp))
                val displayContent = remember(content, agentName, provider) {
                    var cleaned = content.trim()
                    if (!isError) {
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
                SelectionContainer { MarkdownText(displayContent, cc, if (isError) errorColor else cc.textPrimary) }
                if (tokensIn != null || tokensOut != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatTokenCount(tokensIn ?: 0)} in · ${formatTokenCount(tokensOut ?: 0)} out",
                        style = MaterialTheme.typography.labelSmall,
                        color = cc.textMuted,
                    )
                }
            }
        }
        if (!isError) BubbleActions(cc, agentName, meta, content)
    }
}

/** Centered system note — pause state, etc — distinct from any agent's own bubble. */
@Composable
private fun StatusLine(cc: CcPalette, text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic), color = cc.textMuted)
    }
}

/** Pulsing "···" placeholder shown in place of the reply that hasn't arrived yet. */
@Composable
private fun TypingBubble(cc: CcPalette, agentName: String, provider: Provider, isPrimary: Boolean, maxBubbleWidth: Dp, label: String = "thinking…") {
    val (bubbleColor, nameColor) = bubbleColors(cc, provider)
    var dots by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(450)
            dots = dots % 3 + 1
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isPrimary) Arrangement.Start else Arrangement.End) {
        Column(
            Modifier.widthIn(max = maxBubbleWidth)
                .clip(RoundedCornerShape(6.dp))
                .background(bubbleColor)
                .padding(12.dp),
        ) {
            Text("$agentName · $label", style = MaterialTheme.typography.labelMedium, color = nameColor)
            Spacer(Modifier.height(4.dp))
            Text(".".repeat(dots), style = MaterialTheme.typography.bodyMedium, color = cc.textMuted)
        }
    }
}

@Composable
private fun ConclusionBlock(cc: CcPalette, agentName: String, conclusion: String) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(cc.panelAlt)
            .padding(14.dp),
    ) {
        Text("${agentName.uppercase()}'S CONCLUSION", style = MaterialTheme.typography.labelMedium, color = cc.textPrimary)
        Spacer(Modifier.height(6.dp))
        SelectionContainer { MarkdownText(conclusion, cc, cc.textPrimary) }
        BubbleActions(cc, agentName, "conclusion", conclusion)
    }
}

@Composable
private fun EmergencyConclusionBanner(cc: CcPalette, discussion: Discussion) {
    val completedTurns = discussion.transcript.count { !it.isError && !it.isSystem && !it.isUserComment }
    val maxRound = discussion.transcript.filter { !it.isError }.maxOfOrNull { it.round } ?: 1
    val interruptedRound = discussion.transcript.findLast { it.isError }?.round ?: (maxRound + 1)
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(cc.accent.copy(alpha = 0.12f))
            .padding(14.dp),
    ) {
        Text("ℹ️ Deliberation Concluded at Round $maxRound", style = MaterialTheme.typography.labelMedium, color = cc.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            discussion.warning ?: "A network interruption occurred in Round $interruptedRound. The Deliberation Moderator successfully synthesized the final outcome from the $completedTurns completed turns.",
            style = MaterialTheme.typography.bodySmall,
            color = cc.textMuted,
        )
    }
}

