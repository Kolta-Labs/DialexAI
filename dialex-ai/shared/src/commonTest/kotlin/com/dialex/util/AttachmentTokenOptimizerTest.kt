package com.dialex.util

import com.dialex.model.AttachedFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentTokenOptimizerTest {

    @Test
    fun testTokenEstimation() {
        assertEquals(0, AttachmentTokenOptimizer.estimateTokens(""))
        assertEquals(1, AttachmentTokenOptimizer.estimateTokens("hi"))
        assertEquals(25, AttachmentTokenOptimizer.estimateTokens("a".repeat(100)))
        assertEquals("450 tokens", AttachmentTokenOptimizer.formatTokenEstimate(450))
        assertEquals("1.5K tokens", AttachmentTokenOptimizer.formatTokenEstimate(1500))
    }

    @Test
    fun testNormalizationCollapsesNewlinesAndWhitespace() {
        val messy = "Line 1   \r\n\r\n\r\n\r\nLine 2  \t  \n\n\nLine 3   "
        val clean = AttachmentTokenOptimizer.optimizeContent(messy)
        assertEquals("Line 1\n\nLine 2\n\nLine 3", clean)
    }

    @Test
    fun testHeadAndTailTruncationOnLargeFiles() {
        val largeContent = (1..1000).joinToString("\n") { "Row $it: some diagnostic payload" }
        val maxChars = 500
        val optimized = AttachmentTokenOptimizer.optimizeContent(largeContent, maxChars = maxChars)

        assertTrue(optimized.contains("Row 1:"))
        assertTrue(optimized.contains("Truncated"))
        assertTrue(optimized.contains("to conserve token budget"))
        assertTrue(optimized.contains("Row 1000:"))
    }

    @Test
    fun testFormatWithAttachmentsEnclosure() {
        val basePrompt = "Analyze the attached spec for bottlenecks."
        val file = AttachedFile(
            id = "f1",
            name = "spec.md",
            sizeLabel = "1.2 KB",
            content = "## Architecture\nMicroservices with event sourcing.",
            scope = "topic"
        )
        val formatted = AttachmentTokenOptimizer.formatWithAttachments(basePrompt, listOf(file))

        assertTrue(formatted.startsWith("Analyze the attached spec for bottlenecks."))
        assertTrue(formatted.contains("<attached_file name=\"spec.md\">"))
        assertTrue(formatted.contains("Microservices with event sourcing."))
        assertTrue(formatted.contains("</attached_file>"))
    }
}
