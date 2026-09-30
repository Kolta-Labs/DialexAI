package com.dialex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialex.theme.CcPalette

/**
 * Enhanced Markdown renderer supporting dynamic base font sizing, configurable line heights,
 * horizontal rules, headers, code blocks, lists, and inline formatting.
 */
class SearchOccurrenceCounter(var count: Int = 0)

@Composable
fun MarkdownText(
    text: String,
    cc: CcPalette,
    textColor: Color,
    baseFontSize: TextUnit = 14.5.sp,
    lineHeightMultiplier: Float = 1.55f,
    searchQuery: String? = null,
    activeOccurrenceIndex: Int = -1,
    modifier: Modifier = Modifier
) {
    val bodyFontSize = baseFontSize
    val bodyLineHeight = (bodyFontSize.value * lineHeightMultiplier).sp
    val paragraphSpacing = (bodyFontSize.value * 0.45f * lineHeightMultiplier).dp

    val h1Size = (bodyFontSize.value * 1.35f).sp
    val h1LineHeight = (h1Size.value * 1.3f).sp
    val h2Size = (bodyFontSize.value * 1.2f).sp
    val h2LineHeight = (h2Size.value * 1.3f).sp
    val h3Size = (bodyFontSize.value * 1.1f).sp
    val h3LineHeight = (h3Size.value * 1.3f).sp
    val codeFontSize = (bodyFontSize.value * 0.88f).sp

    val occurrenceCounter = remember(text, searchQuery, activeOccurrenceIndex) { SearchOccurrenceCounter() }
    occurrenceCounter.count = 0

    val lines = text.split("\n")
    Column(modifier = modifier) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.trim().startsWith("```")) {
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (body.isNotEmpty()) body.append("\n")
                    body.append(lines[i])
                    i++
                }
                i++ // skip closing fence
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = (paragraphSpacing / 2).coerceAtLeast(4.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(cc.panelAlt)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    val codeAnnotated = buildAnnotatedString {
                        append(body.toString())
                        if (!searchQuery.isNullOrBlank()) {
                            val q = searchQuery.trim()
                            if (q.isNotEmpty()) {
                                val codeText = body.toString()
                                var sIdx = codeText.indexOf(q, 0, ignoreCase = true)
                                while (sIdx != -1) {
                                    val currentOcc = occurrenceCounter.count
                                    occurrenceCounter.count++
                                    val isActive = currentOcc == activeOccurrenceIndex

                                    addStyle(
                                        SpanStyle(
                                            background = if (isActive) Color(0xFF0288D1) else Color(0xFF64B5F6).copy(alpha = 0.38f),
                                            color = if (isActive) Color(0xFFFFFFFF) else (if (cc.isDark) Color(0xFFE1F5FE) else Color(0xFF01579B)),
                                            fontWeight = if (isActive) FontWeight.Black else FontWeight.Bold
                                        ),
                                        start = sIdx,
                                        end = sIdx + q.length
                                    )
                                    sIdx = codeText.indexOf(q, sIdx + q.length, ignoreCase = true)
                                }
                            }
                        }
                    }
                    Text(
                        text = codeAnnotated,
                        fontFamily = FontFamily.Monospace,
                        fontSize = codeFontSize,
                        lineHeight = (codeFontSize.value * 1.4f).sp,
                        color = textColor
                    )
                }
                continue
            }

            val trimmed = line.trim()
            when {
                trimmed.isBlank() -> {
                    Spacer(Modifier.height(paragraphSpacing))
                }
                trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = paragraphSpacing),
                        color = cc.border.copy(alpha = 0.35f),
                        thickness = 0.75.dp
                    )
                }
                trimmed.startsWith("### ") -> {
                    Spacer(Modifier.height((paragraphSpacing * 0.5f).coerceAtLeast(4.dp)))
                    Text(
                        text = inline(trimmed.removePrefix("### "), textColor, searchQuery, activeOccurrenceIndex, occurrenceCounter, cc),
                        fontWeight = FontWeight.Bold,
                        fontSize = h3Size,
                        lineHeight = h3LineHeight,
                        color = textColor
                    )
                    Spacer(Modifier.height((paragraphSpacing * 0.25f).coerceAtLeast(2.dp)))
                }
                trimmed.startsWith("## ") -> {
                    Spacer(Modifier.height((paragraphSpacing * 0.75f).coerceAtLeast(6.dp)))
                    Text(
                        text = inline(trimmed.removePrefix("## "), textColor, searchQuery, activeOccurrenceIndex, occurrenceCounter, cc),
                        fontWeight = FontWeight.Bold,
                        fontSize = h2Size,
                        lineHeight = h2LineHeight,
                        color = textColor
                    )
                    Spacer(Modifier.height((paragraphSpacing * 0.35f).coerceAtLeast(3.dp)))
                }
                trimmed.startsWith("# ") -> {
                    Spacer(Modifier.height(paragraphSpacing.coerceAtLeast(8.dp)))
                    Text(
                        text = inline(trimmed.removePrefix("# "), textColor, searchQuery, activeOccurrenceIndex, occurrenceCounter, cc),
                        fontWeight = FontWeight.Bold,
                        fontSize = h1Size,
                        lineHeight = h1LineHeight,
                        color = textColor
                    )
                    Spacer(Modifier.height((paragraphSpacing * 0.5f).coerceAtLeast(4.dp)))
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(
                        modifier = Modifier.padding(vertical = (paragraphSpacing * 0.2f).coerceAtLeast(2.dp)),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            fontSize = bodyFontSize,
                            lineHeight = bodyLineHeight,
                            color = textColor.copy(alpha = 0.8f),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = inline(trimmed.drop(2), textColor, searchQuery, activeOccurrenceIndex, occurrenceCounter, cc),
                            fontSize = bodyFontSize,
                            lineHeight = bodyLineHeight,
                            color = textColor
                        )
                    }
                }
                trimmed.matches(Regex("""^\d+\.\s.*""")) -> {
                    val dotIdx = trimmed.indexOf('.')
                    val prefix = trimmed.substring(0, dotIdx + 1)
                    val content = trimmed.substring(dotIdx + 1).trimStart()
                    Row(
                        modifier = Modifier.padding(vertical = (paragraphSpacing * 0.2f).coerceAtLeast(2.dp)),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = prefix,
                            fontSize = bodyFontSize,
                            lineHeight = bodyLineHeight,
                            fontWeight = FontWeight.Medium,
                            color = textColor.copy(alpha = 0.75f),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = inline(content, textColor, searchQuery, activeOccurrenceIndex, occurrenceCounter, cc),
                            fontSize = bodyFontSize,
                            lineHeight = bodyLineHeight,
                            color = textColor
                        )
                    }
                }
                else -> {
                    Text(
                        text = inline(line, textColor, searchQuery, activeOccurrenceIndex, occurrenceCounter, cc),
                        fontSize = bodyFontSize,
                        lineHeight = bodyLineHeight,
                        color = textColor
                    )
                }
            }
            i++
        }
    }
}

/** Inline `**bold**`, `*italic*`/`_italic_`, and `` `code` `` within one line, with search highlighting. */
private fun inline(
    line: String,
    textColor: Color,
    searchQuery: String? = null,
    activeOccurrenceIndex: Int = -1,
    counter: SearchOccurrenceCounter? = null,
    cc: CcPalette? = null
): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < line.length) {
        when {
            line.startsWith("**", i) -> {
                val end = line.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(line[i]); i++
                }
            }
            line.startsWith("`", i) -> {
                val end = line.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = textColor.copy(alpha = 0.12f))) {
                        append(line.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(line[i]); i++
                }
            }
            (line[i] == '*' || line[i] == '_') && i + 1 < line.length && line[i + 1] != ' ' -> {
                val marker = line[i]
                val end = line.indexOf(marker, i + 1)
                if (end != -1 && end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(line.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(line[i]); i++
                }
            }
            else -> {
                append(line[i]); i++
            }
        }
    }

    if (!searchQuery.isNullOrBlank()) {
        val q = searchQuery.trim()
        if (q.isNotEmpty()) {
            val fullText = toAnnotatedString().text
            var searchIdx = fullText.indexOf(q, 0, ignoreCase = true)
            while (searchIdx != -1) {
                val currentOcc = counter?.count ?: 0
                counter?.let { it.count++ }
                val isActive = currentOcc == activeOccurrenceIndex

                addStyle(
                    style = SpanStyle(
                        background = if (isActive) Color(0xFF0288D1) else Color(0xFF64B5F6).copy(alpha = 0.38f),
                        color = if (isActive) Color(0xFFFFFFFF) else (if (cc?.isDark == true) Color(0xFFE1F5FE) else Color(0xFF01579B)),
                        fontWeight = if (isActive) FontWeight.Black else FontWeight.Bold
                    ),
                    start = searchIdx,
                    end = searchIdx + q.length
                )
                searchIdx = fullText.indexOf(q, searchIdx + q.length, ignoreCase = true)
            }
        }
    }
}
