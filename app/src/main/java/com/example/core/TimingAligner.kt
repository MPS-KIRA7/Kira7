package com.example.core

import com.example.model.ScriptData
import com.example.model.TimedWord
import kotlin.math.max
import kotlin.math.min

/**
 * Aligns timed words (from ElevenLabs char-timings or Deepgram word timings)
 * onto the script's exact tokens using character-level LCS similarity.
 * Preserves script spelling and flags weak/uncertain alignments.
 */
object TimingAligner {

    fun elevenLabsCharsToWords(
        characters: List<String>,
        starts: List<Double>,
        ends: List<Double>
    ): List<TimedWord> {
        if (characters.isEmpty() || starts.size != characters.size || ends.size != characters.size) {
            return emptyList()
        }
        val words = mutableListOf<TimedWord>()
        val currentToken = StringBuilder()
        var wordStart = -1.0
        var wordEnd = 0.0

        for (i in characters.indices) {
            val ch = characters[i]
            if (ch.isBlank()) {
                if (currentToken.isNotEmpty()) {
                    words.add(
                        TimedWord(
                            index = words.size,
                            text = currentToken.toString(),
                            startSec = wordStart.coerceAtLeast(0.0),
                            endSec = max(wordEnd, wordStart + 0.06),
                            confidence = 0.98
                        )
                    )
                    currentToken.clear()
                    wordStart = -1.0
                }
            } else {
                if (wordStart < 0.0) wordStart = starts[i]
                wordEnd = ends[i]
                currentToken.append(ch)
            }
        }
        if (currentToken.isNotEmpty()) {
            words.add(
                TimedWord(
                    index = words.size,
                    text = currentToken.toString(),
                    startSec = wordStart.coerceAtLeast(0.0),
                    endSec = max(wordEnd, wordStart + 0.06),
                    confidence = 0.98
                )
            )
        }
        return words
    }

    fun alignToScript(
        scriptText: String,
        timedWords: List<TimedWord>,
        fallbackDurationSec: Double
    ): List<TimedWord> {
        val scriptTokens = ScriptData.tokenizeWords(scriptText)
        if (scriptTokens.isEmpty()) return emptyList()

        if (timedWords.isEmpty()) {
            return synthesizeEvenTiming(scriptTokens, fallbackDurationSec)
        }

        val n = scriptTokens.size
        val m = timedWords.size

        val normScript = scriptTokens.map { normalizeArabicToken(it) }
        val normTimed = timedWords.map { normalizeArabicToken(it.text) }

        // Word-level DP backed by character-level LCS similarity
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        val simMatrix = Array(n) { i ->
            DoubleArray(m) { j ->
                charLcsSimilarity(normScript[i], normTimed[j])
            }
        }

        for (i in 1..n) {
            for (j in 1..m) {
                val match = dp[i - 1][j - 1] + simMatrix[i - 1][j - 1]
                val skipScript = dp[i - 1][j]
                val skipTimed = dp[i][j - 1]
                dp[i][j] = maxOf(match, skipScript, skipTimed)
            }
        }

        // Backtrack matched pairs (scriptIndex -> timedIndex)
        val matchedTimedIdx = IntArray(n) { -1 }
        val matchScore = DoubleArray(n) { 0.0 }
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val diag = dp[i - 1][j - 1] + simMatrix[i - 1][j - 1]
            if (dp[i][j] == diag && simMatrix[i - 1][j - 1] >= 0.42) {
                matchedTimedIdx[i - 1] = j - 1
                matchScore[i - 1] = simMatrix[i - 1][j - 1]
                i--
                j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }

        val totalAudioEnd = timedWords.maxOf { it.endSec }.coerceAtLeast(fallbackDurationSec)
        val starts = DoubleArray(n)
        val ends = DoubleArray(n)
        val confidences = DoubleArray(n)
        val weakFlags = BooleanArray(n)

        for (k in 0 until n) {
            val tidx = matchedTimedIdx[k]
            if (tidx >= 0) {
                val tw = timedWords[tidx]
                starts[k] = tw.startSec
                ends[k] = tw.endSec
                val combinedConf = min(tw.confidence, matchScore[k])
                confidences[k] = combinedConf
                weakFlags[k] = combinedConf < 0.72
            } else {
                confidences[k] = 0.35
                weakFlags[k] = true
            }
        }

        // Interpolate unmatched spans smoothly between nearest matched anchors
        var cursor = 0
        while (cursor < n) {
            if (matchedTimedIdx[cursor] >= 0) {
                cursor++
                continue
            }
            val spanStart = cursor
            while (cursor < n && matchedTimedIdx[cursor] < 0) {
                cursor++
            }
            val spanEnd = cursor - 1
            val count = spanEnd - spanStart + 1

            val anchorBefore = if (spanStart > 0) ends[spanStart - 1] else 0.0
            val anchorAfter = if (cursor < n) starts[cursor] else totalAudioEnd
            val spanDuration = max(0.18 * count, anchorAfter - anchorBefore)
            val step = spanDuration / count
            for (offset in 0 until count) {
                val idx = spanStart + offset
                starts[idx] = anchorBefore + offset * step
                ends[idx] = starts[idx] + max(0.08, step * 0.92)
            }
        }

        // Enforce strict monotonic progression
        for (k in 0 until n) {
            if (k > 0 && starts[k] < ends[k - 1]) {
                starts[k] = ends[k - 1]
            }
            if (ends[k] <= starts[k]) {
                ends[k] = starts[k] + 0.08
            }
        }

        return scriptTokens.mapIndexed { idx, originalWord ->
            TimedWord(
                index = idx,
                text = originalWord,
                startSec = starts[idx],
                endSec = ends[idx],
                confidence = confidences[idx],
                isWeak = weakFlags[idx]
            )
        }
    }

    fun synthesizeEvenTiming(tokens: List<String>, durationSec: Double): List<TimedWord> {
        if (tokens.isEmpty()) return emptyList()
        val usable = (durationSec - 0.3).coerceAtLeast(tokens.size * 0.15)
        val step = usable / tokens.size
        return tokens.mapIndexed { index, word ->
            val start = 0.15 + index * step
            TimedWord(
                index = index,
                text = word,
                startSec = start,
                endSec = start + step * 0.9,
                confidence = 0.6,
                isWeak = true
            )
        }
    }

    fun normalizeArabicToken(token: String): String {
        val sb = StringBuilder()
        for (ch in token.lowercase()) {
            when (ch) {
                // Strip Arabic diacritics & tatweel
                in '\u064B'..'\u065F', '\u0670', '\u0640' -> continue
                'أ', 'إ', 'آ' -> sb.append('ا')
                'ة' -> sb.append('ه')
                'ى' -> sb.append('ي')
                else -> if (ch.isLetterOrDigit()) sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun charLcsSimilarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val lcs = charLcsLength(a, b)
        return (2.0 * lcs) / (a.length + b.length).toDouble()
    }

    fun charLcsLength(a: String, b: String): Int {
        val n = a.length
        val m = b.length
        val dp = IntArray(m + 1)
        for (i in 1..n) {
            var prev = 0
            for (j in 1..m) {
                val temp = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev + 1 else max(dp[j], dp[j - 1])
                prev = temp
            }
        }
        return dp[m]
    }
}
