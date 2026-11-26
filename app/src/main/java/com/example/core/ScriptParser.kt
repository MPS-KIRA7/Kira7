package com.example.core

import android.content.Context
import com.example.model.ScriptData
import kotlin.math.roundToInt

/**
 * Stateful streaming filter that removes <think>...</think> blocks
 * even when opening or closing tags span multiple SSE chunks.
 */
class ThinkBlockStripper {
    private val buffer = StringBuilder()
    private var insideThink = false

    fun feed(chunk: String): String {
        if (chunk.isEmpty()) return ""
        buffer.append(chunk)
        val output = StringBuilder()
        while (buffer.isNotEmpty()) {
            if (!insideThink) {
                val startIdx = buffer.indexOf("<think>")
                if (startIdx >= 0) {
                    output.append(buffer.substring(0, startIdx))
                    buffer.delete(0, startIdx + "<think>".length)
                    insideThink = true
                } else {
                    val keepTail = partialTagTailLength(buffer, "<think>")
                    val emitLen = buffer.length - keepTail
                    if (emitLen > 0) {
                        output.append(buffer.substring(0, emitLen))
                        buffer.delete(0, emitLen)
                    }
                    break
                }
            } else {
                val endIdx = buffer.indexOf("</think>")
                if (endIdx >= 0) {
                    buffer.delete(0, endIdx + "</think>".length)
                    insideThink = false
                } else {
                    val keepTail = partialTagTailLength(buffer, "</think>")
                    val dropLen = buffer.length - keepTail
                    if (dropLen > 0) {
                        buffer.delete(0, dropLen)
                    }
                    break
                }
            }
        }
        return output.toString()
    }

    fun flush(): String {
        val remaining = if (insideThink) "" else buffer.toString()
        buffer.clear()
        insideThink = false
        return remaining
    }

    private fun partialTagTailLength(sb: StringBuilder, tag: String): Int {
        val maxCheck = minOf(sb.length, tag.length - 1)
        for (len in maxCheck downTo 1) {
            if (sb.endsWith(tag.substring(0, len))) return len
        }
        return 0
    }

    companion object {
        fun stripAll(text: String): String =
            text.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim()
    }
}

object ScriptParser {

    fun loadStyleGuide(context: Context): String = try {
        context.assets.open("references/script-style.md")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    } catch (_: Exception) {
        DEFAULT_STYLE_GUIDE
    }

    fun buildPromptMessages(topic: String, durationSec: Int, styleGuide: String): Pair<String, String> {
        val targetWords = (durationSec * 2.6).roundToInt().coerceAtLeast(18)
        val systemPrompt = """
            أنت كاتب محتوى علمي متخصص في ريلز قصيرة باللهجة المصرية لسلسلة «عارف ليه؟».
            اتبع دليل الأسلوب التالي بدقة:
            $styleGuide
        """.trimIndent()

        val userPrompt = """
            اكتب سكربت ريل علمي عن الموضوع: "$topic"
            المدة المطلوبة: $durationSec ثانية (حوالي $targetWords كلمة بمعدل 2.6 كلمة/ثانية).
            تأكد من وجود:
            1) جملة خطافة في البداية
            2) سؤال السلسلة «عارف ليه؟»
            3) تشبيه واحد بسيط وجمل قصيرة
            4) إبقاء المصطلحات العلمية الإنجليزية بالإنجليزية
            5) جملة خلاصة في النهاية
            أخرج النتيجة بهذه الصيغة فقط:
            TITLE: ...
            SCRIPT:
            ...
            SOURCES:
            - ...
        """.trimIndent()

        return systemPrompt to userPrompt
    }

    fun parse(rawText: String, fallbackTopic: String = ""): ScriptData {
        val cleaned = ThinkBlockStripper.stripAll(rawText)
        val lines = cleaned.lines()

        var title = ""
        val scriptLines = mutableListOf<String>()
        val sourceLines = mutableListOf<String>()

        var section = ""
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("TITLE:", ignoreCase = true) -> {
                    title = trimmed.substringAfter(":").trim()
                    section = "TITLE"
                }
                trimmed.startsWith("SCRIPT:", ignoreCase = true) -> {
                    val inline = trimmed.substringAfter(":").trim()
                    if (inline.isNotEmpty()) scriptLines.add(inline)
                    section = "SCRIPT"
                }
                trimmed.startsWith("SOURCES:", ignoreCase = true) -> {
                    val inline = trimmed.substringAfter(":").trim()
                    if (inline.isNotEmpty()) sourceLines.add(inline.removePrefix("-").removePrefix("•").trim())
                    section = "SOURCES"
                }
                section == "SCRIPT" -> {
                    if (trimmed.isNotEmpty()) scriptLines.add(trimmed)
                }
                section == "SOURCES" -> {
                    if (trimmed.isNotEmpty()) {
                        sourceLines.add(
                            trimmed.replace(Regex("^[-•*\\d.)]+\\s*"), "").trim()
                        )
                    }
                }
                else -> {
                    if (trimmed.isNotEmpty() && title.isNotEmpty()) {
                        scriptLines.add(trimmed)
                    }
                }
            }
        }

        val finalScript = if (scriptLines.isNotEmpty()) {
            scriptLines.joinToString("\n")
        } else {
            cleaned
        }
        val finalTitle = title.ifBlank { fallbackTopic.ifBlank { "عارف ليه؟" } }
        return ScriptData(
            title = finalTitle,
            script = finalScript.trim(),
            sources = sourceLines.filter { it.isNotBlank() }
        )
    }

    private const val DEFAULT_STYLE_GUIDE = """
        - اكتب باللهجة المصرية السلسة.
        - ابدأ بجملة خطافة ثم «عارف ليه؟» ثم تشبيه بسيط وجمل قصيرة ثم خلاصة.
        - احتفظ بالمصطلحات العلمية الإنجليزية بالإنجليزية.
        - الهدف: 2.6 كلمة في الثانية.
        - الصيغة: TITLE / SCRIPT / SOURCES.
    """
}
