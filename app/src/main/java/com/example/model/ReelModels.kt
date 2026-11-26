package com.example.model

enum class PipelineStage(val arabicTitle: String, val index: Int) {
    SCRIPT("السكربت", 0),
    VOICE("الصوت", 1),
    TIMING("التوقيت", 2),
    STORYBOARD("الستوري بورد", 3),
    RENDER("الرندر", 4)
}

enum class StageStatus(val arabicLabel: String) {
    IDLE("في الانتظار"),
    RUNNING("قيد التنفيذ"),
    REVIEW("بانتظار الموافقة"),
    DONE("مكتمل"),
    ERROR("خطأ")
}

enum class ApprovalMode(val arabicLabel: String) {
    AUTO("متابعة تلقائية"),
    MANUAL("انتظار موافقتي")
}

enum class VoiceInputMode(val arabicLabel: String) {
    ELEVENLABS_TTS("توليد صوت ElevenLabs"),
    UPLOADED_AUDIO("رفع ملف صوتي + Deepgram")
}

enum class SceneType(val wireName: String, val arabicLabel: String) {
    INTRO("intro", "مقدمة"),
    QUESTION("question", "سؤال السلسلة"),
    CONCEPT("concept", "مفهوم"),
    PROCESS("process", "عملية"),
    COMPARE("compare", "مقارنة"),
    STAT("stat", "إحصائية"),
    RECAP("recap", "خلاصة");

    companion object {
        fun fromWire(value: String?): SceneType =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: CONCEPT
    }
}

enum class SceneTemplate(val wireName: String, val arabicLabel: String) {
    BODY("body", "جسم وعضو"),
    ZOOM("zoom", "تكبير مجهري"),
    FLOW("flow", "تدفق A←B←C"),
    CYCLE("cycle", "دورة مستمرة"),
    TIMELINE("timeline", "خط زمني"),
    CHART("chart", "رسم بياني"),
    LAYERS("layers", "طبقات متدرجة"),
    CARDS("cards", "بطاقات مقارنة"),
    METER("meter", "مقياس مؤشر");

    companion object {
        fun fromWire(value: String?): SceneTemplate =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: CARDS
    }
}

enum class AccentTone(val wireName: String) {
    CYAN("cyan"),
    INDIGO("indigo"),
    SUCCESS("success"),
    WARNING("warning");

    companion object {
        fun fromWire(value: String?): AccentTone =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) } ?: CYAN
    }
}

data class StageState(
    val stage: PipelineStage,
    val status: StageStatus = StageStatus.IDLE,
    val approvalMode: ApprovalMode = ApprovalMode.AUTO,
    val progress: Float = 0f,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null
)

data class ScriptData(
    val title: String = "",
    val script: String = "",
    val sources: List<String> = emptyList()
) {
    val words: List<String>
        get() = tokenizeWords(script)

    companion object {
        fun tokenizeWords(text: String): List<String> =
            text.trim()
                .split(Regex("\\s+"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
    }
}

data class TimedWord(
    val index: Int,
    val text: String,
    val startSec: Double,
    val endSec: Double,
    val confidence: Double = 1.0,
    val isWeak: Boolean = false
)

data class StoryboardScene(
    val type: SceneType,
    val template: SceneTemplate,
    val atWordIndex: Int,
    val title: String,
    val subtitle: String = "",
    val icons: List<String> = listOf("lightbulb"),
    val items: List<String> = emptyList(),
    val metricValue: String = "",
    val metricPercent: Float = 0.72f
)

data class WordAccent(
    val wordIndex: Int,
    val tone: AccentTone,
    val underline: Boolean = true
)

data class StoryboardData(
    val scenes: List<StoryboardScene> = emptyList(),
    val accents: List<WordAccent> = emptyList(),
    val reviewNotes: String = ""
)

data class IconDefinition(
    val name: String,
    val source: String,
    val license: String,
    val aliases: List<String>,
    val paths: List<String>
)

data class VoiceInfo(
    val voiceId: String,
    val name: String,
    val category: String = ""
)

data class AppSettings(
    val llmBaseUrl: String = "http://127.0.0.1:31415/v1",
    val llmApiKey: String = "",
    val llmModel: String = "auto",
    val elevenLabsBaseUrl: String = "https://api.elevenlabs.io",
    val elevenLabsApiKey: String = "",
    val selectedVoiceId: String = "arabic_science_narrator",
    val deepgramBaseUrl: String = "https://api.deepgram.com",
    val deepgramApiKey: String = "",
    val fontFolderUri: String = "",
    val fontDisplayName: String = "IBM Plex Sans Arabic (مدمج)",
    val useLocalMockApis: Boolean = true
) {
    fun effectiveLlmBaseUrl(): String =
        if (useLocalMockApis) "http://127.0.0.1:4399/v1" else llmBaseUrl.trimEnd('/')

    fun effectiveElevenLabsBaseUrl(): String =
        if (useLocalMockApis) "http://127.0.0.1:4399" else elevenLabsBaseUrl.trimEnd('/')

    fun effectiveDeepgramBaseUrl(): String =
        if (useLocalMockApis) "http://127.0.0.1:4399" else deepgramBaseUrl.trimEnd('/')

    companion object {
        fun maskKey(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "غير مضبوط"
            if (trimmed.length <= 4) return "••••"
            return "••••••••" + trimmed.takeLast(4)
        }
    }
}

data class ReelProject(
    val id: String,
    val topic: String = "",
    val durationSec: Int = 10,
    val voiceInputMode: VoiceInputMode = VoiceInputMode.ELEVENLABS_TTS,
    val uploadedAudioPath: String? = null,
    val scriptData: ScriptData = ScriptData(),
    val streamedLlmText: String = "",
    val voiceAudioPath: String? = null,
    val rawTimedWords: List<TimedWord> = emptyList(),
    val alignedWords: List<TimedWord> = emptyList(),
    val storyboard: StoryboardData = StoryboardData(),
    val rawStoryboardJson: String = "",
    val usedIcons: Map<String, IconDefinition> = emptyMap(),
    val exportedVideoPath: String? = null,
    val totalDurationSec: Double = 10.0
)
