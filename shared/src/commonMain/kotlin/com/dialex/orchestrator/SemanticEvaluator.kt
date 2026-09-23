package com.dialex.orchestrator

import kotlin.math.max

object SemanticEvaluator {

    private val STOPWORDS = setOf(
        "a", "about", "above", "after", "again", "against", "all", "am", "an", "and",
        "any", "are", "aren't", "as", "at", "be", "because", "been", "before", "being",
        "below", "between", "both", "but", "by", "can", "cannot", "could", "couldn't",
        "did", "didn't", "do", "does", "doesn't", "doing", "don't", "down", "during",
        "each", "few", "for", "from", "further", "had", "hadn't", "has", "hasn't",
        "have", "haven't", "having", "he", "he'd", "he'll", "he's", "her", "here",
        "here's", "hers", "herself", "him", "himself", "his", "how", "how's", "i",
        "i'd", "i'll", "i'm", "i've", "if", "in", "into", "is", "isn't", "it", "it's",
        "its", "itself", "let's", "me", "more", "most", "mustn't", "my", "myself",
        "no", "nor", "not", "of", "off", "on", "once", "only", "or", "other", "ought",
        "our", "ours", "ourselves", "out", "over", "own", "same", "shan't", "she",
        "she'd", "she'll", "she's", "should", "shouldn't", "so", "some", "such",
        "than", "that", "that's", "the", "their", "theirs", "them", "themselves",
        "then", "there", "there's", "these", "they", "they'd", "they'll", "they're",
        "they've", "this", "those", "through", "to", "too", "under", "until", "up",
        "very", "was", "wasn't", "we", "we'd", "we'll", "we're", "we've", "were",
        "weren't", "what", "what's", "when", "when's", "where", "where's", "which",
        "while", "who", "who's", "whom", "why", "why's", "with", "won't", "would",
        "wouldn't", "you", "you'd", "you'll", "you're", "you've", "your", "yours",
        "yourself", "yourselves"
    )

    /**
     * Calculates semantic similarity and topical coverage between turn content and the central topic.
     * Evaluates unigrams, bigrams, and morphological stem alignment.
     * Returns a normalized score between 0.0 and 1.0.
     */
    fun similarity(text: String, topic: String): Double {
        if (text.isBlank() || topic.isBlank()) return 0.0

        val textTokens = extractTerms(text)
        val topicTokens = extractTerms(topic)

        if (textTokens.isEmpty() || topicTokens.isEmpty()) return 0.0

        val textFreq = countTermFrequencies(textTokens)
        val topicFreq = countTermFrequencies(topicTokens)

        var matchedTopicWeight = 0.0
        var totalTopicWeight = 0.0

        for ((term, count) in topicFreq) {
            totalTopicWeight += count
            if (textFreq.containsKey(term)) {
                matchedTopicWeight += count
            }
        }

        val topicCoverage = if (totalTopicWeight > 0.0) matchedTopicWeight / totalTopicWeight else 0.0

        // Word stem and substring matching across topic keywords
        val textWords = textTokens.filter { !it.contains("_") }.toSet()
        val topicWords = topicTokens.filter { !it.contains("_") }
        val wordMatches = topicWords.count { tw ->
            textWords.any { it == tw || (it.length >= 4 && tw.length >= 4 && (it.startsWith(tw) || tw.startsWith(it))) }
        }
        val wordCoverage = if (topicWords.isNotEmpty()) wordMatches.toDouble() / topicWords.size else 0.0

        return (topicCoverage * 0.5 + wordCoverage * 0.5).coerceIn(0.0, 1.0)
    }

    private fun extractTerms(raw: String): List<String> {
        val clean = raw
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""[#*_`~>\[\](){}|\\.,!?:;"'/\-]"""), " ")
            .lowercase()

        val words = clean.split(Regex("""\s+"""))
            .map { it.trim().filter { c -> c.isLetterOrDigit() } }
            .filter { it.length > 2 && it !in STOPWORDS }

        if (words.isEmpty()) return emptyList()

        val terms = mutableListOf<String>()
        terms.addAll(words)

        // Add adjacent word bigrams for semantic cohesion
        for (i in 0 until words.size - 1) {
            terms.add("${words[i]}_${words[i + 1]}")
        }
        return terms
    }

    private fun countTermFrequencies(terms: List<String>): Map<String, Int> {
        val freq = mutableMapOf<String, Int>()
        for (term in terms) {
            freq[term] = (freq[term] ?: 0) + 1
        }
        return freq
    }

    /**
     * Checks if content contains mathematical formulas, LaTeX notation, or equation blocks.
     */
    fun hasMathOrLatex(text: String): Boolean {
        if (text.contains("$")) return true
        if (text.contains(Regex("""\\(frac|int|sum|partial|sigma|theta|alpha|beta|gamma|lambda|mu|infty|equiv|approx|sqrt)"""))) return true
        if (text.contains(Regex("""\\\[[\s\S]*?\\\]"""))) return true
        return false
    }

    /**
     * Counts approximate words in a body of text.
     */
    fun countWords(text: String): Int {
        val clean = text.replace(Regex("""```[\s\S]*?```"""), "").trim()
        if (clean.isBlank()) return 0
        return clean.split(Regex("""\s+""")).count { it.isNotBlank() }
    }

    /**
     * Counts sentences in a body of text.
     */
    fun countSentences(text: String): Int {
        val clean = text.replace(Regex("""```[\s\S]*?```"""), "").trim()
        if (clean.isBlank()) return 1
        val sentences = clean.split(Regex("""[.!?]+(\s+|$)""")).filter { it.isNotBlank() }
        return max(1, sentences.size)
    }

    /**
     * Approximates syllable count for an English word.
     */
    fun countSyllables(rawWord: String): Int {
        val word = rawWord.lowercase().filter { it.isLetter() }
        if (word.length <= 3) return 1

        var count = 0
        var prevIsVowel = false
        val vowels = "aeiouy"

        for (c in word) {
            val isVowel = c in vowels
            if (isVowel && !prevIsVowel) {
                count++
            }
            prevIsVowel = isVowel
        }

        // Adjust for silent 'e' at the end
        if (word.endsWith("e") && !word.endsWith("le") && count > 1) {
            count--
        }

        return max(1, count)
    }

    /**
     * Calculates the Flesch-Kincaid Reading Ease score:
     * 206.835 - 1.015 * (total words / total sentences) - 84.6 * (total syllables / total words)
     * CASUAL: 70-80 (Grade 7-8)
     * EXECUTIVE: 50-60 (Grade 10-12)
     * ACADEMIC: 20-30 (Post-grad)
     */
    fun fleschKincaidReadingEase(text: String): Double {
        val words = text.replace(Regex("""```[\s\S]*?```"""), "")
            .split(Regex("""\s+"""))
            .map { it.filter { c -> c.isLetter() } }
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return 100.0

        val totalWords = words.size.toDouble()
        val totalSentences = countSentences(text).toDouble()
        val totalSyllables = words.sumOf { countSyllables(it) }.toDouble()

        val ease = 206.835 - 1.015 * (totalWords / totalSentences) - 84.6 * (totalSyllables / totalWords)
        return ease.coerceIn(0.0, 100.0)
    }

    /**
     * Calculates the Flesch-Kincaid Grade Level:
     * 0.39 * (total words / total sentences) + 11.8 * (total syllables / total words) - 15.59
     */
    fun fleschKincaidGradeLevel(text: String): Double {
        val words = text.replace(Regex("""```[\s\S]*?```"""), "")
            .split(Regex("""\s+"""))
            .map { it.filter { c -> c.isLetter() } }
            .filter { it.isNotBlank() }

        if (words.isEmpty()) return 1.0

        val totalWords = words.size.toDouble()
        val totalSentences = countSentences(text).toDouble()
        val totalSyllables = words.sumOf { countSyllables(it) }.toDouble()

        val grade = 0.39 * (totalWords / totalSentences) + 11.8 * (totalSyllables / totalWords) - 15.59
        return max(0.0, grade)
    }
}
