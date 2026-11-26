package com.example.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import com.example.core.IconLibrary
import com.example.core.ScriptParser
import com.example.core.StoryboardEngine
import com.example.core.TimingAligner
import com.example.model.AppSettings
import com.example.model.ApprovalMode
import com.example.model.PipelineStage
import com.example.model.ReelProject
import com.example.model.StageState
import com.example.model.StageStatus
import com.example.model.VoiceInputMode
import com.example.network.ApiClients
import com.example.render.Mp4VideoExporter
import com.example.render.ReelRenderer
import kotlinx.coroutines.CancellationException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PipelineEngine(
    private val context: Context,
    private val apiClients: ApiClients,
    private val iconLibrary: IconLibrary
) {

    interface Callbacks {
        fun onStageUpdate(stage: PipelineStage, transform: (StageState) -> StageState)
        fun onLog(stage: PipelineStage, message: String)
        fun onProjectUpdate(transform: (ReelProject) -> ReelProject)
        fun onPreviewFrame(bitmap: Bitmap?)
    }

    suspend fun runFromStage(
        startStage: PipelineStage,
        initialProject: ReelProject,
        settings: AppSettings,
        stageStates: Map<PipelineStage, StageState>,
        customTypeface: Typeface?,
        callbacks: Callbacks
    ): ReelProject {
        var project = initialProject
        val stagesToRun = PipelineStage.entries.filter { it.index >= startStage.index }

        for (stage in stagesToRun) {
            callbacks.onStageUpdate(stage) {
                it.copy(status = StageStatus.RUNNING, progress = 0.05f, errorMessage = null)
            }
            try {
                project = when (stage) {
                    PipelineStage.SCRIPT -> executeScriptStage(project, settings, callbacks)
                    PipelineStage.VOICE -> executeVoiceStage(project, settings, callbacks)
                    PipelineStage.TIMING -> executeTimingStage(project, settings, callbacks)
                    PipelineStage.STORYBOARD -> executeStoryboardStage(project, settings, customTypeface, callbacks)
                    PipelineStage.RENDER -> executeRenderStage(project, customTypeface, callbacks)
                }

                val mode = stageStates[stage]?.approvalMode ?: ApprovalMode.AUTO
                if (mode == ApprovalMode.MANUAL && stage != PipelineStage.RENDER) {
                    callbacks.onLog(stage, "المرحلة جاهزة — بانتظار موافقتك للمتابعة.")
                    callbacks.onStageUpdate(stage) {
                        it.copy(status = StageStatus.REVIEW, progress = 1f)
                    }
                    return project
                } else {
                    callbacks.onStageUpdate(stage) {
                        it.copy(status = StageStatus.DONE, progress = 1f)
                    }
                }
            } catch (ce: CancellationException) {
                callbacks.onLog(stage, "تم إلغاء المرحلة.")
                callbacks.onStageUpdate(stage) {
                    it.copy(status = StageStatus.IDLE, progress = 0f)
                }
                throw ce
            } catch (t: Throwable) {
                val msg = t.message ?: "خطأ غير متوقع"
                callbacks.onLog(stage, "فشل: $msg")
                callbacks.onStageUpdate(stage) {
                    it.copy(status = StageStatus.ERROR, errorMessage = msg)
                }
                return project
            }
        }
        return project
    }

    private suspend fun executeScriptStage(
        project: ReelProject,
        settings: AppSettings,
        callbacks: Callbacks
    ): ReelProject {
        val topic = project.topic.ifBlank { "ليه السماء لونها أزرق؟" }
        callbacks.onLog(PipelineStage.SCRIPT, "بدء توليد السكربت للموضوع: $topic (${project.durationSec} ث)")
        val styleGuide = ScriptParser.loadStyleGuide(context)
        val (sysPrompt, userPrompt) = ScriptParser.buildPromptMessages(topic, project.durationSec, styleGuide)

        callbacks.onProjectUpdate { it.copy(streamedLlmText = "") }
        val rawResponse = apiClients.streamChatCompletion(
            baseUrl = settings.effectiveLlmBaseUrl(),
            apiKey = settings.llmApiKey,
            model = settings.llmModel,
            systemPrompt = sysPrompt,
            userPrompt = userPrompt
        ) { _, accumulated ->
            callbacks.onProjectUpdate { it.copy(streamedLlmText = accumulated) }
            callbacks.onStageUpdate(PipelineStage.SCRIPT) { it.copy(progress = 0.65f) }
        }

        val parsed = ScriptParser.parse(rawResponse, topic)
        callbacks.onLog(PipelineStage.SCRIPT, "تم استخراج العنوان (${parsed.words.size} كلمة) وإزالة مقاطع <think>.")
        val updated = project.copy(
            topic = topic,
            scriptData = parsed,
            streamedLlmText = rawResponse,
            totalDurationSec = project.durationSec.toDouble()
        )
        callbacks.onProjectUpdate { updated }
        return updated
    }

    private suspend fun executeVoiceStage(
        project: ReelProject,
        settings: AppSettings,
        callbacks: Callbacks
    ): ReelProject {
        val projectDir = File(context.filesDir, "projects/${project.id}").apply { mkdirs() }

        return if (project.voiceInputMode == VoiceInputMode.UPLOADED_AUDIO && !project.uploadedAudioPath.isNullOrBlank()) {
            val uploaded = File(project.uploadedAudioPath)
            if (!uploaded.exists()) {
                throw IllegalStateException("ملف الصوت المرفوع غير موجود")
            }
            callbacks.onLog(PipelineStage.VOICE, "استخدام الملف الصوتي المرفوع: ${uploaded.name}")
            val updated = project.copy(voiceAudioPath = uploaded.absolutePath)
            callbacks.onProjectUpdate { updated }
            updated
        } else {
            val scriptBody = project.scriptData.script.ifBlank {
                throw IllegalStateException("السكربت فارغ — أنشئ السكربت أولاً.")
            }
            val voiceId = settings.selectedVoiceId.ifBlank { "arabic_science_narrator" }
            callbacks.onLog(PipelineStage.VOICE, "توليد الصوت عبر ElevenLabs ($voiceId)...")
            val outAudio = File(projectDir, "voice.wav")
            val tts = apiClients.synthesizeElevenLabsWithTimestamps(
                baseUrl = settings.effectiveElevenLabsBaseUrl(),
                apiKey = settings.elevenLabsApiKey,
                voiceId = voiceId,
                text = scriptBody,
                targetDurationSec = project.durationSec,
                outputFile = outAudio
            )
            val audioDuration = tts.timedWords.maxOfOrNull { it.endSec }?.plus(0.25)
                ?: project.durationSec.toDouble()
            callbacks.onLog(PipelineStage.VOICE, "تم توليد الصوت (${tts.timedWords.size} كلمة موقّتة).")
            val updated = project.copy(
                voiceAudioPath = tts.audioFile.absolutePath,
                rawTimedWords = tts.timedWords,
                totalDurationSec = maxOf(project.durationSec.toDouble(), audioDuration)
            )
            callbacks.onProjectUpdate { updated }
            updated
        }
    }

    private suspend fun executeTimingStage(
        project: ReelProject,
        settings: AppSettings,
        callbacks: Callbacks
    ): ReelProject {
        val scriptText = project.scriptData.script.ifBlank {
            throw IllegalStateException("السكربت غير متاح للمحاذاة.")
        }

        val sourceTimedWords = if (project.voiceInputMode == VoiceInputMode.UPLOADED_AUDIO) {
            val audioFile = project.voiceAudioPath?.let { File(it) }
                ?: throw IllegalStateException("ارفع ملفاً صوتياً أولاً.")
            callbacks.onLog(PipelineStage.TIMING, "استخراج التوقيت من Deepgram (nova-3 ar)...")
            apiClients.transcribeDeepgramWords(
                baseUrl = settings.effectiveDeepgramBaseUrl(),
                apiKey = settings.deepgramApiKey,
                audioFile = audioFile,
                fallbackScriptText = scriptText
            )
        } else {
            callbacks.onLog(PipelineStage.TIMING, "استخدام توقيتات ElevenLabs الحرفية...")
            project.rawTimedWords
        }

        callbacks.onStageUpdate(PipelineStage.TIMING) { it.copy(progress = 0.6f) }
        val aligned = TimingAligner.alignToScript(
            scriptText = scriptText,
            timedWords = sourceTimedWords,
            fallbackDurationSec = project.totalDurationSec
        )
        val weakCount = aligned.count { it.isWeak }
        val totalDur = maxOf(
            project.durationSec.toDouble(),
            (aligned.maxOfOrNull { it.endSec } ?: 0.0) + 0.3
        )
        callbacks.onLog(
            PipelineStage.TIMING,
            "اكتملت محاذاة LCS على ${aligned.size} كلمة (كلمات ضعيفة: $weakCount)."
        )
        val updated = project.copy(
            rawTimedWords = sourceTimedWords,
            alignedWords = aligned,
            totalDurationSec = totalDur
        )
        callbacks.onProjectUpdate { updated }
        return updated
    }

    private suspend fun executeStoryboardStage(
        project: ReelProject,
        settings: AppSettings,
        customTypeface: Typeface?,
        callbacks: Callbacks
    ): ReelProject {
        val words = project.alignedWords.ifEmpty {
            TimingAligner.synthesizeEvenTiming(project.scriptData.words, project.totalDurationSec)
        }
        val (sys1, usr1) = StoryboardEngine.buildStoryboardPrompt(
            title = project.scriptData.title,
            words = words,
            availableIcons = iconLibrary.icons.keys
        )
        callbacks.onLog(PipelineStage.STORYBOARD, "توليد الستوري بورد الأولي وتنسيق المشاهد...")
        val draftRaw = apiClients.streamChatCompletion(
            baseUrl = settings.effectiveLlmBaseUrl(),
            apiKey = settings.llmApiKey,
            model = settings.llmModel,
            systemPrompt = sys1,
            userPrompt = usr1
        ) { _, _ -> }

        val draftBoard = StoryboardEngine.parseAndRepair(draftRaw, words.size, iconLibrary)
        callbacks.onStageUpdate(PipelineStage.STORYBOARD) { it.copy(progress = 0.55f) }

        // Second pass: Self-review
        callbacks.onLog(PipelineStage.STORYBOARD, "تنفيذ جولة المراجعة الذاتية الثانية لتحسين القوالب...")
        val draftJson = StoryboardEngine.toJsonString(draftBoard)
        val (sys2, usr2) = StoryboardEngine.buildSelfReviewPrompt(draftJson, words)
        val reviewedRaw = apiClients.streamChatCompletion(
            baseUrl = settings.effectiveLlmBaseUrl(),
            apiKey = settings.llmApiKey,
            model = settings.llmModel,
            systemPrompt = sys2,
            userPrompt = usr2
        ) { _, _ -> }

        val finalBoard = StoryboardEngine.parseAndRepair(reviewedRaw, words.size, iconLibrary)
        val usedIconNouns = finalBoard.scenes.flatMap { it.icons }
        val resolvedIcons = iconLibrary.extractUsedIcons(usedIconNouns)
        val finalJson = StoryboardEngine.toJsonString(finalBoard)

        callbacks.onLog(
            PipelineStage.STORYBOARD,
            "تم اعتماد ${finalBoard.scenes.size} مشاهد و ${resolvedIcons.size} أيقونة من المكتبة."
        )
        val updated = project.copy(
            alignedWords = words,
            storyboard = finalBoard,
            rawStoryboardJson = finalJson,
            usedIcons = resolvedIcons
        )
        callbacks.onProjectUpdate { updated }

        // Emit immediate preview frame
        val thumb = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(thumb)
        ReelRenderer.drawFrame(c, 360f, 640f, 0.8, updated, customTypeface)
        callbacks.onPreviewFrame(thumb)

        return updated
    }

    private suspend fun executeRenderStage(
        project: ReelProject,
        customTypeface: Typeface?,
        callbacks: Callbacks
    ): ReelProject {
        val exportDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportDir, "reel_${project.id}.mp4")
        callbacks.onLog(PipelineStage.RENDER, "بدء رندر 1080×1920 @ 30fps مع مزج المؤثرات وتطبيع -14 LUFS...")

        val result = Mp4VideoExporter.exportReel(
            project = project,
            outputMp4 = outFile,
            typeface = customTypeface
        ) { progress, frameIdx, totalFrames, snap ->
            callbacks.onStageUpdate(PipelineStage.RENDER) { it.copy(progress = progress) }
            if (snap != null) callbacks.onPreviewFrame(snap)
            if (frameIdx % 60 == 0 || frameIdx == totalFrames) {
                callbacks.onLog(
                    PipelineStage.RENDER,
                    "إطار $frameIdx / $totalFrames (${(progress * 100).toInt()}%)"
                )
            }
        }

        val lufsStr = String.format(Locale.US, "%.1f", result.measuredLufs)
        callbacks.onLog(
            PipelineStage.RENDER,
            "تم تصدير الفيديو بنجاح: ${result.mp4File.name} | ${result.frameCount} إطار | الصوت: $lufsStr LUFS"
        )
        val updated = project.copy(exportedVideoPath = result.mp4File.absolutePath)
        callbacks.onProjectUpdate { updated }
        return updated
    }

    companion object {
        fun timestampPrefix(): String =
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    }
}
