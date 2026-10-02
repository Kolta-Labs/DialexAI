package com.dialex.presentation.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.domain.model.ConsensusResult
import com.dialex.model.DebateConfig
import com.dialex.model.DebateMessage
import com.dialex.model.Discussion
import com.dialex.model.RoundMode
import com.dialex.model.brandName
import com.dialex.model.label
import com.dialex.presentation.chat.ChatTypographySettings
import com.dialex.theme.CcPalette
import com.dialex.ui.ThemedTooltipBox
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextHeader(
    cc: CcPalette,
    discussion: Discussion,
    onRenameDiscussion: ((String) -> Unit)? = null,
    onRegenerateTitle: (() -> Unit)? = null,
    typographySettings: ChatTypographySettings? = null,
    evaluateConsensus: (suspend (DebateConfig, List<DebateMessage>) -> ConsensusResult?)? = null
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
            delay(1800)
            topicCopied = false
        }
    }
    LaunchedEffect(contextCopied) {
        if (contextCopied) {
            delay(1800)
            contextCopied = false
        }
    }
    LaunchedEffect(directivesCopied) {
        if (directivesCopied) {
            delay(1800)
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
                                .size(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(cc.accent.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Psychology,
                                contentDescription = null,
                                tint = cc.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        if (isEditingTitle) {
                            OutlinedTextField(
                                value = titleEditText,
                                onValueChange = { titleEditText = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = cc.textPrimary
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = cc.accent,
                                    unfocusedBorderColor = cc.border.copy(alpha = 0.6f),
                                    focusedTextColor = cc.textPrimary,
                                    unfocusedTextColor = cc.textPrimary,
                                    cursorColor = cc.accent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(titleFocusRequester)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            hasBeenFocused = true
                                        } else if (hasBeenFocused && !focusState.isFocused) {
                                            commitTitleRename()
                                        }
                                    }
                                    .onKeyEvent { keyEvent ->
                                        if (keyEvent.key == Key.Enter) {
                                            commitTitleRename()
                                            true
                                        } else if (keyEvent.key == Key.Escape) {
                                            titleEditText = title
                                            isEditingTitle = false
                                            hasBeenFocused = false
                                            true
                                        } else false
                                    }
                            )
                            IconButton(
                                onClick = { commitTitleRename() },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Save Title", tint = cc.accent, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = {
                                    titleEditText = title
                                    isEditingTitle = false
                                    hasBeenFocused = false
                                },
                                modifier = Modifier.size(48.dp)
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
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Regenerate Summary Title",
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            if (onRenameDiscussion != null) {
                                ThemedTooltipBox("Rename Discussion") {
                                    IconButton(
                                        onClick = { isEditingTitle = true },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "Edit Title",
                                            tint = cc.textMuted,
                                            modifier = Modifier.size(16.dp)
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
                                                clipboard.setText(AnnotatedString(config.topic))
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
                                                clipboard.setText(AnnotatedString(config.commonContext))
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
                                                clipboard.setText(AnnotatedString(config.commonInfo))
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
                                    // Engine-evaluated; null (not loaded / failed) simply shows "Debating".
                                    var consensusResult by remember { mutableStateOf<ConsensusResult?>(null) }
                                    LaunchedEffect(discussion.transcript.size, discussion.isConsensusReached) {
                                        consensusResult = try {
                                            evaluateConsensus?.invoke(discussion.config, discussion.transcript)
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            null
                                        }
                                    }
                                    val isConsensusAchieved = discussion.isConsensusReached || discussion.earlyExitReason == "CONSENSUS" || consensusResult?.achieved == true
                                    val isConverging = consensusResult?.let { it.ongoing && it.agreedCount > 0 } == true

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
                                                    isConverging -> "🟠 Converging (${consensusResult?.agreedCount}/${consensusResult?.totalCount} Agreed)"
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
