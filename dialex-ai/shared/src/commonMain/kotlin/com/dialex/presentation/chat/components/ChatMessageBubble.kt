package com.dialex.presentation.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.model.Agent
import com.dialex.model.Provider
import com.dialex.model.brandName
import com.dialex.model.label
import com.dialex.presentation.chat.ChatTypographySettings
import com.dialex.presentation.chat.LocalChatDisplaySettings
import com.dialex.presentation.chat.LocalToast
import com.dialex.theme.CcPalette
import com.dialex.theme.memberColorFor
import com.dialex.ui.MarkdownText
import com.dialex.util.formatMessageTimestamp
import com.dialex.util.formatTokenCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AeratedMessageItem(
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
    agreed: Boolean = false,
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
        else -> memberColorFor(memberIndex, cc)
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

    val isConcurred = agreed && !isUserComment && !isError
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
                        // Left metadata row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
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

                            val hasEvidenceCitation = remember(content) { content.contains("[Evidence:", ignoreCase = true) }
                            if (hasEvidenceCitation) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF673AB7).copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, Color(0xFF673AB7).copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = "🔍 Grounded Citation",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.5.sp
                                        ),
                                        color = if (cc.isDark) Color(0xFFD1C4E9) else Color(0xFF673AB7),
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

                        // Right metadata
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

                    // Sub-header row
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

        // Below the bubble: Animated hover row
        AnimatedVisibility(
            visible = isHovered,
            enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150))
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
                            clipboard.setText(AnnotatedString(content))
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
fun AgentThinkingTicker(
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
fun StatusPill(cc: CcPalette, text: String) {
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
fun CompactionInfoBar(
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
                        modifier = Modifier.size(48.dp)
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
fun BubbleActions(cc: CcPalette, agentName: String, meta: String, content: String) {
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
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", tint = cc.textMuted.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
        }
        IconButton(
            onClick = {
                scope.launch {
                    toast("Copied for sharing")
                }
            },
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Outlined.IosShare, contentDescription = "Share", tint = cc.textMuted.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
        }
    }
}
