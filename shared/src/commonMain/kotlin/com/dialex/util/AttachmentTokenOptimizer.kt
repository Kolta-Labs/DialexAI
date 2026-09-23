package com.dialex.util

import com.dialex.model.AttachedFile
import kotlin.math.roundToInt

/**
 * Token-optimization engine for file attachments in Dialex.
 *
 * Provides:
 * 1. Fast, provider-neutral token estimation (~4 chars/token).
 * 2. Normalization: collapses superfluous newlines, trims trailing whitespace, strips control noise.
 * 3. Budget enforcement: head-and-tail preservation when large files exceed token limits.
 * 4. Structural XML enclosure (`<attached_file name="...">`) for unambiguous context injection.
 */
object AttachmentTokenOptimizer {

    /** Default per-file character ceiling (~5,000 tokens) to protect against runaway turns. */
    const val DEFAULT_MAX_CHARS_PER_FILE = 20_000

    /** Default total budget for all attachments in a single field (~7,500 tokens). */
    const val DEFAULT_MAX_TOTAL_CHARS = 30_000

    /**
     * Estimates token count based on the standard ~4 characters per token heuristic.
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length + 3) / 4
    }

    /**
     * Formats token estimate into a human-readable badge (e.g., "450 tokens", "~2.3k tokens").
     */
    fun formatTokenEstimate(tokens: Int): String {
        return "${formatTokenCount(tokens)} tokens"
    }

    /**
     * Formats raw byte size into a compact label (e.g., "820 B", "14 KB", "2.1 MB").
     */
    fun formatFileSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> {
                val mb = (bytes / (1024.0 * 1024.0) * 10.0).roundToInt() / 10.0
                "$mb MB"
            }
        }
    }

    /**
     * Normalizes text by removing trailing whitespace, collapsing excess newlines,
     * stripping non-printable characters, and enforcing head-and-tail budget truncation.
     */
    fun optimizeContent(
        rawContent: String,
        maxChars: Int = DEFAULT_MAX_CHARS_PER_FILE
    ): String {
        if (rawContent.isBlank()) return ""

        // 1. Normalize line endings
        var text = rawContent.replace("\r\n", "\n").replace("\r", "\n")

        // 2. Collapse 3 or more consecutive newlines into at most 2
        text = Regex("\n{3,}").replace(text, "\n\n")

        // 3. Trim trailing whitespace per line & filter unprintable control chars
        val cleanLines = text.lines().map { line ->
            line.trimEnd().filter { it == '\n' || it == '\t' || it >= ' ' }
        }
        text = cleanLines.joinToString("\n").trim()

        // 4. Smart Head-and-Tail Budget Truncation if content exceeds maxChars
        if (text.length > maxChars) {
            val headLength = (maxChars * 0.70).toInt()
            val tailLength = (maxChars * 0.30).toInt()
            val truncatedChars = text.length - (headLength + tailLength)
            val head = text.take(headLength).trimEnd()
            val tail = text.takeLast(tailLength).trimStart()
            return "$head\n\n[... Truncated $truncatedChars characters to conserve token budget ...]\n\n$tail"
        }

        return text
    }

    /**
     * Formats a base user prompt in conjunction with its attached files.
     *
     * Result structure:
     * ```
     * {userText}
     *
     * <attached_file name="filename.ext">
     * {optimizedContent}
     * </attached_file>
     * ```
     */
    fun formatWithAttachments(
        baseText: String,
        files: List<AttachedFile>,
        maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS
    ): String {
        if (files.isEmpty()) return baseText.trim()

        val cleanBase = baseText.trim()
        val budgetPerFile = (maxTotalChars / files.size.coerceAtLeast(1)).coerceAtLeast(3000)

        val sb = StringBuilder()
        if (cleanBase.isNotEmpty()) {
            sb.append(cleanBase)
            sb.append("\n\n")
        }

        files.forEachIndexed { index, file ->
            val optimized = optimizeContent(file.content, maxChars = budgetPerFile)
            sb.append("<attached_file name=\"").append(file.name).append("\">\n")
            sb.append(optimized)
            sb.append("\n</attached_file>")
            if (index < files.size - 1) {
                sb.append("\n\n")
            }
        }

        return sb.toString().trim()
    }
}
