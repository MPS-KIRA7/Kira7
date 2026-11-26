package com.example.core

import com.example.model.AccentTone
import com.example.model.SceneTemplate
import com.example.model.SceneType
import com.example.model.StoryboardData
import com.example.model.StoryboardScene
import com.example.model.TimedWord
import com.example.model.WordAccent
import org.json.JSONArray
import org.json.JSONObject

object StoryboardEngine {

    fun buildStoryboardPrompt(
        title: String,
        words: List<TimedWord>,
        availableIcons: Collection<String>
    ): Pair<String, String> {
        val indexedWords = words.joinToString(" ") { "[${it.index}:${it.text}]" }
        val systemPrompt = """
            أنت مخرج فني تقني (Motion & Science Explainer Director).
            أعد JSON فقط بصيغة:
            {
              "scenes": [
                {
                  "type": "intro|question|concept|process|compare|stat|recap",
                  "template": "body|zoom|flow|cycle|timeline|chart|layers|cards|meter",
                  "at": 0,
                  "title": "عنوان بصري قصير",
                  "subtitle": "وصف مختصر",
                  "icons": ["english_noun"],
                  "items": ["عنصر 1", "عنصر 2", "عنصر 3"],
                  "metricValue": "85%",
                  "metricPercent": 0.85
                }
              ],
              "accents": [[3, "cyan"], [8, "warning"]]
            }
            استخدم فقط أسماء الأيقونات البسيطة من هذه القائمة: ${availableIcons.joinToString(", ")}.
        """.trimIndent()

        val userPrompt = """
            العنوان: $title
            كلمات السكربت المفهرسة:
            $indexedWords
            وزّع المشاهد بذكاء على الكلمات بحيث يبدأ أول مشهد intro عند 0، ولا يتكرر نفس القالب 3 مرات متتالية.
        """.trimIndent()

        return systemPrompt to userPrompt
    }

    fun buildSelfReviewPrompt(rawStoryboardJson: String, words: List<TimedWord>): Pair<String, String> {
        val indexedWords = words.joinToString(" ") { "[${it.index}:${it.text}]" }
        val systemPrompt = """
            أنت مراجع جودة للستوري بورد العلمي. راجع JSON الستوري بورد التالي وحسّن اختيارات القوالب الضعيفة أو الأيقونات غير الدقيقة أو نقاط الـ accents، ثم أعد JSON المحسّن فقط بدون أي شرح إضافي.
        """.trimIndent()

        val userPrompt = """
            الكلمات: $indexedWords
            الستوري بورد الحالي:
            $rawStoryboardJson
        """.trimIndent()

        return systemPrompt to userPrompt
    }

    fun parseAndRepair(
        rawResponse: String,
        wordCount: Int,
        iconLibrary: IconLibrary? = null
    ): StoryboardData {
        val jsonStr = extractJsonObject(ThinkBlockStripper.stripAll(rawResponse))
        val parsed = try {
            parseRawJson(jsonStr, iconLibrary)
        } catch (_: Exception) {
            StoryboardData()
        }
        return validateAndRepair(parsed, wordCount, iconLibrary)
    }

    fun validateAndRepair(
        data: StoryboardData,
        wordCount: Int,
        iconLibrary: IconLibrary? = null
    ): StoryboardData {
        val maxWordIdx = (wordCount - 1).coerceAtLeast(0)
        val rawScenes = data.scenes.ifEmpty {
            listOf(
                StoryboardScene(
                    type = SceneType.INTRO,
                    template = SceneTemplate.ZOOM,
                    atWordIndex = 0,
                    title = "عارف ليه؟",
                    icons = listOf("lightbulb"),
                    items = listOf("الفكرة", "الآلية", "النتيجة")
                )
            )
        }

        // 1. Clamp word indices & resolve icon nouns
        val clamped = rawScenes.map { scene ->
            val resolvedIcons = scene.icons
                .ifEmpty { listOf("lightbulb") }
                .map { noun -> iconLibrary?.resolve(noun)?.name ?: noun.lowercase() }
            scene.copy(
                atWordIndex = scene.atWordIndex.coerceIn(0, maxWordIdx),
                icons = resolvedIcons,
                metricPercent = scene.metricPercent.coerceIn(0.05f, 1.0f)
            )
        }.sortedBy { it.atWordIndex }

        // 2. Deduplicate identical word indices while preserving progression
        val deduped = mutableListOf<StoryboardScene>()
        for (scene in clamped) {
            if (deduped.isEmpty()) {
                deduped.add(scene)
            } else {
                val prev = deduped.last()
                if (scene.atWordIndex <= prev.atWordIndex) {
                    val shifted = (prev.atWordIndex + 1).coerceAtMost(maxWordIdx)
                    if (shifted > prev.atWordIndex) {
                        deduped.add(scene.copy(atWordIndex = shifted))
                    }
                } else {
                    deduped.add(scene)
                }
            }
        }

        // 3. Enforce first scene = INTRO at wordIndex 0
        if (deduped.isNotEmpty()) {
            deduped[0] = deduped[0].copy(
                type = SceneType.INTRO,
                atWordIndex = 0
            )
        }

        // 4. Prevent the same template appearing 3 times in a row
        val allTemplates = SceneTemplate.entries
        for (i in 2 until deduped.size) {
            val t0 = deduped[i - 2].template
            val t1 = deduped[i - 1].template
            val t2 = deduped[i].template
            if (t0 == t1 && t1 == t2) {
                val replacement = allTemplates.firstOrNull { it != t1 } ?: SceneTemplate.FLOW
                deduped[i] = deduped[i].copy(template = replacement)
            }
        }

        // 5. Clamp and sort word accents
        val validAccents = data.accents
            .map { it.copy(wordIndex = it.wordIndex.coerceIn(0, maxWordIdx)) }
            .distinctBy { it.wordIndex }
            .sortedBy { it.wordIndex }

        return StoryboardData(
            scenes = deduped,
            accents = validAccents,
            reviewNotes = data.reviewNotes
        )
    }

    fun toJsonString(data: StoryboardData): String {
        val root = JSONObject()
        val scenesArr = JSONArray()
        for (scene in data.scenes) {
            scenesArr.put(
                JSONObject().apply {
                    put("type", scene.type.wireName)
                    put("template", scene.template.wireName)
                    put("at", scene.atWordIndex)
                    put("title", scene.title)
                    put("subtitle", scene.subtitle)
                    put("icons", JSONArray(scene.icons))
                    put("items", JSONArray(scene.items))
                    put("metricValue", scene.metricValue)
                    put("metricPercent", scene.metricPercent.toDouble())
                }
            )
        }
        val accentsArr = JSONArray()
        for (accent in data.accents) {
            accentsArr.put(
                JSONArray().apply {
                    put(accent.wordIndex)
                    put(accent.tone.wireName)
                }
            )
        }
        root.put("scenes", scenesArr)
        root.put("accents", accentsArr)
        if (data.reviewNotes.isNotBlank()) {
            root.put("reviewNotes", data.reviewNotes)
        }
        return root.toString(2)
    }

    private fun parseRawJson(jsonStr: String, iconLibrary: IconLibrary?): StoryboardData {
        val obj = JSONObject(jsonStr)
        val scenesArr = obj.optJSONArray("scenes") ?: JSONArray()
        val scenes = buildList {
            for (i in 0 until scenesArr.length()) {
                val item = scenesArr.getJSONObject(i)
                val iconsJson = item.optJSONArray("icons")
                val icons = buildList {
                    if (iconsJson != null) {
                        for (j in 0 until iconsJson.length()) {
                            val noun = iconsJson.optString(j)
                            if (noun.isNotBlank()) {
                                add(iconLibrary?.resolve(noun)?.name ?: noun)
                            }
                        }
                    }
                }
                val itemsJson = item.optJSONArray("items")
                val items = buildList {
                    if (itemsJson != null) {
                        for (j in 0 until itemsJson.length()) {
                            val label = itemsJson.optString(j)
                            if (label.isNotBlank()) add(label)
                        }
                    }
                }
                add(
                    StoryboardScene(
                        type = SceneType.fromWire(item.optString("type")),
                        template = SceneTemplate.fromWire(item.optString("template")),
                        atWordIndex = item.optInt("at", 0),
                        title = item.optString("title", "مشهد علمي"),
                        subtitle = item.optString("subtitle", ""),
                        icons = icons.ifEmpty { listOf("lightbulb") },
                        items = items,
                        metricValue = item.optString("metricValue", ""),
                        metricPercent = item.optDouble("metricPercent", 0.75).toFloat()
                    )
                )
            }
        }

        val accentsArr = obj.optJSONArray("accents") ?: JSONArray()
        val accents = buildList {
            for (i in 0 until accentsArr.length()) {
                val pair = accentsArr.optJSONArray(i)
                if (pair != null && pair.length() >= 2) {
                    add(
                        WordAccent(
                            wordIndex = pair.optInt(0, 0),
                            tone = AccentTone.fromWire(pair.optString(1))
                        )
                    )
                }
            }
        }
        return StoryboardData(
            scenes = scenes,
            accents = accents,
            reviewNotes = obj.optString("reviewNotes", "")
        )
    }

    private fun extractJsonObject(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else "{}"
    }
}
