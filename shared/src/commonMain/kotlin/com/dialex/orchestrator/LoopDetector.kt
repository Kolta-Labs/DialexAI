package com.dialex.orchestrator

import com.dialex.model.LoopInspectionResult

object LoopDetector {

    /**
     * Inspects a newly generated turn against prior turns.
     * Evaluates:
     * 1. Exact contiguous substring length (Tier 1)
     * 2. Token n-gram Jaccard similarity (Tier 2)
     * 3. Stagnant agreement echo (Tier 3)
     */
    fun inspect(
        newText: String,
        priorTurns: List<String>,
        threshold: Double = 0.65,
        maxContiguous: Int = 180
    ): LoopInspectionResult {
        if (newText.isBlank() || priorTurns.isEmpty()) {
            return LoopInspectionResult.Clean
        }

        val normalizedNew = normalizeText(newText)
        if (normalizedNew.length < 15) {
            return LoopInspectionResult.Clean
        }

        val isAgreement = normalizedNew.startsWith("agreed") || normalizedNew.startsWith("concur")
        val newNGrams = extractNGrams(normalizedNew)

        for ((index, prior) in priorTurns.withIndex()) {
            val normalizedPrior = normalizeText(prior)
            if (normalizedPrior.isBlank()) continue

            val isPriorAgreement = normalizedPrior.startsWith("agreed") || normalizedPrior.startsWith("concur")

            // If this is legitimate cross-participant agreement on consensus, don't flag as loop
            if (isAgreement && isPriorAgreement) {
                val newWords = extractWords(normalizedNew)
                val priorWords = extractWords(normalizedPrior)
                if (newWords.isNotEmpty() && priorWords.isNotEmpty()) {
                    val wordIntersection = newWords.count { it in priorWords }
                    val wordOverlap = wordIntersection.toDouble() / newWords.size
                    if (wordOverlap > 0.85 && priorTurns.size >= 4) {
                        return LoopInspectionResult.LoopDetected(
                            similarityScore = wordOverlap,
                            longestContiguousMatch = "Repeated agreement formula",
                            duplicatedTurnRound = index + 1,
                            reason = "Circular agreement echo detected across multiple turns"
                        )
                    }
                }
                continue
            }

            // Tier 1: Exact Contiguous Substring Check
            val lcs = findLongestCommonSubstring(normalizedNew, normalizedPrior)
            if (lcs.length >= maxContiguous) {
                return LoopInspectionResult.LoopDetected(
                    similarityScore = 1.0,
                    longestContiguousMatch = lcs.take(100),
                    duplicatedTurnRound = index + 1,
                    reason = "Verbatim cloned block detected (${lcs.length} chars >= $maxContiguous chars)"
                )
            }

            // Tier 2: Token N-Gram Jaccard Overlap (unigrams, bigrams, trigrams)
            val priorNGrams = extractNGrams(normalizedPrior)
            if (newNGrams.isNotEmpty() && priorNGrams.isNotEmpty()) {
                val intersectionSize = newNGrams.count { it in priorNGrams }
                val unionSize = (newNGrams + priorNGrams).size
                val jaccard = if (unionSize > 0) intersectionSize.toDouble() / unionSize else 0.0

                if (jaccard >= threshold) {
                    return LoopInspectionResult.LoopDetected(
                        similarityScore = jaccard,
                        longestContiguousMatch = lcs.take(100),
                        duplicatedTurnRound = index + 1,
                        reason = "Structural loop detected: Jaccard similarity ${(jaccard * 100).toInt()}% >= ${(threshold * 100).toInt()}%"
                    )
                }
            }
        }

        return LoopInspectionResult.Clean
    }

    private fun normalizeText(text: String): String {
        return text
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""[#*_`~>\[\]()]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()
    }

    private fun extractWords(normalized: String): Set<String> {
        return normalized.split(" ")
            .map { it.trim().filter { c -> c.isLetterOrDigit() } }
            .filter { it.length > 2 }
            .toSet()
    }

    private fun extractNGrams(normalized: String): Set<String> {
        val words = normalized.split(" ")
            .map { it.trim().filter { c -> c.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptySet()

        val ngrams = mutableSetOf<String>()
        // unigrams
        for (w in words) {
            ngrams.add(w)
        }
        // bigrams
        for (i in 0..words.size - 2) {
            ngrams.add("${words[i]} ${words[i + 1]}")
        }
        // trigrams
        for (i in 0..words.size - 3) {
            ngrams.add("${words[i]} ${words[i + 1]} ${words[i + 2]}")
        }
        return ngrams
    }

    /**
     * Efficient Longest Common Substring algorithm using rolling row DP.
     */
    fun findLongestCommonSubstring(str1: String, str2: String): String {
        if (str1.isEmpty() || str2.isEmpty()) return ""

        val len1 = str1.length
        val len2 = str2.length
        var maxLen = 0
        var endIndex1 = 0

        var prev = IntArray(len2 + 1)
        var curr = IntArray(len2 + 1)

        for (i in 1..len1) {
            for (j in 1..len2) {
                if (str1[i - 1] == str2[j - 1]) {
                    curr[j] = prev[j - 1] + 1
                    if (curr[j] > maxLen) {
                        maxLen = curr[j]
                        endIndex1 = i
                    }
                } else {
                    curr[j] = 0
                }
            }
            val temp = prev
            prev = curr
            curr = temp
        }

        return if (maxLen > 0) str1.substring(endIndex1 - maxLen, endIndex1) else ""
    }
}
