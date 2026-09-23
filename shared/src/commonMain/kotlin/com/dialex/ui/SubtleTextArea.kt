package com.dialex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.theme.CcPalette
import com.dialex.theme.LocalCcColors

/**
 * Metadata representation of a file chip rendered inside [SubtleTextArea].
 */
data class FileChipItem(
    val id: String,
    val name: String,
    val meta: String = "",
)

/**
 * Antigravity / Claude Code style subtle text area:
 * - Clean white / card container with soft rounded corners (14.dp)
 * - Decent height with scrollable multi-line text
 * - Subtle '+' / '📎' attach icon button inside the text field at the bottom
 * - Removable attached file chips inside the container (with size and token estimates)
 * - Optional trailing bottom content (e.g., model pill)
 */
@Composable
fun SubtleTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minHeight: Dp = 110.dp,
    minLines: Int = 3,
    maxLines: Int = Int.MAX_VALUE,
    onAttachFile: (() -> Unit)? = null,
    attachedFiles: List<String> = emptyList(),
    fileChips: List<FileChipItem> = emptyList(),
    onRemoveFile: ((String) -> Unit)? = null,
    onRemoveChip: ((String) -> Unit)? = null,
    enableVoiceInput: Boolean = true,
    trailingBottomContent: (@Composable () -> Unit)? = null,
    cc: CcPalette = LocalCcColors.current
) {
    val recognizer = com.dialex.audio.rememberPlatformSpeechRecognizer()
    val speechState by recognizer.state.collectAsState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(cc.panel)
            .border(BorderStroke(0.85.dp, cc.border.copy(alpha = 0.6f)), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        // Multi-line Text Area
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = cc.textPrimary,
                lineHeight = 20.sp,
                fontSize = 13.5.sp
            ),
            cursorBrush = SolidColor(cc.accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            lineHeight = 20.sp,
                            fontSize = 13.5.sp
                        ),
                        color = cc.textMuted.copy(alpha = 0.8f)
                    )
                }
                innerTextField()
            }
        )

        // Live partial transcript indicator
        LiveSpeechTranscriptBadge(
            speechState = speechState,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )

        Spacer(Modifier.height(8.dp))

        // Bottom Row: Attached files, '+' Attach button, Voice input & Trailing Content
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f, fill = false),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Subtle paperclip attach button inside the text field
                if (onAttachFile != null) {
                    com.dialex.ui.ThemedTooltipBox("Attach file") {
                        IconButton(
                            onClick = onAttachFile,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = "Attach file",
                                tint = cc.textMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }

                // Voice Dictation Button
                if (enableVoiceInput) {
                    VoiceInputButton(
                        currentText = value,
                        onTextChange = onValueChange,
                        recognizer = recognizer,
                        size = 24.dp,
                        iconSize = 15.dp
                    )
                }

                // File chips with rich metadata (token count, size)
                if (fileChips.isNotEmpty()) {
                    fileChips.forEach { chip ->
                        Surface(
                            color = cc.panelAlt.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(0.75.dp, cc.border.copy(alpha = 0.45f)),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    chip.name,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                                    color = cc.textPrimary,
                                    maxLines = 1
                                )
                                if (chip.meta.isNotBlank()) {
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        chip.meta,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                                        color = cc.textMuted
                                    )
                                }
                                if (onRemoveChip != null) {
                                    Spacer(Modifier.width(5.dp))
                                    com.dialex.ui.ThemedTooltipBox("Remove file") {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove file",
                                            tint = cc.textMuted,
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clickable { onRemoveChip(chip.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Fallback to legacy string list if fileChips wasn't provided
                    attachedFiles.forEach { fileName ->
                        Surface(
                            color = cc.panelAlt.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                    tint = cc.textMuted,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    fileName,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                    color = cc.textPrimary,
                                    maxLines = 1
                                )
                                if (onRemoveFile != null) {
                                    Spacer(Modifier.width(4.dp))
                                    com.dialex.ui.ThemedTooltipBox("Remove file") {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove file",
                                            tint = cc.textMuted,
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clickable { onRemoveFile(fileName) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Optional Trailing Bottom Content (e.g. Model indicator)
            if (trailingBottomContent != null) {
                trailingBottomContent()
            }
        }
    }
}
