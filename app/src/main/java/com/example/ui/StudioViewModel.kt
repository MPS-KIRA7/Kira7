package com.example.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.core.IconLibrary
import com.example.core.ScriptParser
import com.example.core.StoryboardEngine
import com.example.data.SettingsRepository
import com.example.model.AppSettings
import com.example.model.ApprovalMode
import com.example.model.PipelineStage
import com.example.model.ReelProject
import com.example.model.SceneTemplate
import com.example.model.StageState
import com.example.model.StageStatus
import com.example.model.VoiceInfo
import com.example.model.VoiceInputMode
import com.example.network.ApiClients
import com.example.network.LocalMockApiServer
import com.example.pipeline.PipelineEngine
import com.example.render.ReelRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class AppTab(val label: String) {
    STUDIO("الاستوديو"),
    PREVIEW("المعاينة"),
    TEMPLATES("القوالب"),
    SETTINGS("الإعدادات")
}

data class StudioUiState(
    val activeTab: AppTab = AppTab.STUDIO,
    val project: ReelProject = ReelProject(
        id = "demo_10s",
        topic = "ليه السماء لونها أزرق؟",
        durationSec = 10
    ),
    val stages: Map<PipelineStage, StageState> = PipelineStage.entries.associateWith { StageState(it) },
    val settings: AppSettings = AppSettings(),
    val maskedKeys: SettingsRepository.MaskedSettingsSummary = SettingsRepository.MaskedSettingsSummary(
        "غير مضبوط", "غير مضبوط", "غير مضبوط"
    ),
    val availableVoices: List<VoiceInfo> = listOf(
        VoiceInfo("arabic_science_narrator", "طارق — راوي علمي مصري", "default"),
        VoiceInfo("egyptian_storyteller", "كريم — سرد سريع للريلز", "premade")
    ),
    val isLoadingVoices: Boolean = false,
    val isPipelineRunning: Boolean = false,
    val livePreviewBitmap: Bitmap? = null,
    val contactSheetBitmap: Bitmap? = null,
    val mockServerPort: Int? = null,
    val statusBanner: String? = null,
    val customTypeface: Typeface? = null
)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val settingsRepo = SettingsRepository(appContext)
    val iconLibrary: IconLibrary = IconLibrary.loadFromAssets(appContext)
    private val apiClients = ApiClients()
    private val pipelineEngine = PipelineEngine(appContext, apiClients, iconLibrary)
    private val mockServer = LocalMockApiServer(4399)

    private var activeJob: Job? = null

    private val _uiState = MutableStateFlow(StudioUiState())
    val uiState: StateFlow<StudioUiState> = _uiState.asStateFlow()

    init {
        val loadedSettings = settingsRepo.load()
        val defaultTf = ResourcesCompat.getFont(appContext, R.font.ibm_plex_sans_arabic)
        val loadedTf = loadCustomTypefaceFromUri(loadedSettings.fontFolderUri) ?: defaultTf
        _uiState.update {
            it.copy(
                settings = loadedSettings,
                maskedKeys = settingsRepo.getMaskedKeys(loadedSettings),
                customTypeface = loadedTf
            )
        }
        if (loadedSettings.useLocalMockApis) {
            ensureMockServerStarted()
        }
        refreshTemplateContactSheet()
    }

    private fun ensureMockServerStarted() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val port = mockServer.start()
                _uiState.update { it.copy(mockServerPort = port) }
            }.onFailure { err ->
                _uiState.update { it.copy(statusBanner = "تعذر تشغيل خادم المحاكاة: ${err.message}") }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun updateTopic(topic: String) {
        _uiState.update { it.copy(project = it.project.copy(topic = topic)) }
    }

    fun updateDuration(seconds: Int) {
        _uiState.update {
            it.copy(project = it.project.copy(durationSec = seconds, totalDurationSec = seconds.toDouble()))
        }
    }

    fun updateVoiceInputMode(mode: VoiceInputMode) {
        _uiState.update { it.copy(project = it.project.copy(voiceInputMode = mode)) }
    }

    fun handleUploadedAudioUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val dir = File(appContext.filesDir, "projects/${_uiState.value.project.id}").apply { mkdirs() }
                val dest = File(dir, "uploaded_voice.audio")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: error("تعذر قراءة الملف الصوتي")
                _uiState.update {
                    it.copy(
                        project = it.project.copy(
                            voiceInputMode = VoiceInputMode.UPLOADED_AUDIO,
                            uploadedAudioPath = dest.absolutePath,
                            voiceAudioPath = dest.absolutePath
                        ),
                        statusBanner = "تم رفع الملف الصوتي بنجاح وجاهز للتحليل بـ Deepgram."
                    )
                }
            }.onFailure { e ->
                _uiState.update { it.copy(statusBanner = "فشل رفع الصوت: ${e.message}") }
            }
        }
    }

    fun handleCustomFontUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val dest = File(appContext.filesDir, "custom_arabic_font.ttf")
            val copied = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                dest.exists() && dest.length() > 1024
            }.getOrDefault(false)

            val tf = if (copied) {
                runCatching { Typeface.createFromFile(dest) }.getOrNull()
            } else null

            if (tf != null) {
                val updatedSettings = _uiState.value.settings.copy(
                    fontFolderUri = dest.absolutePath,
                    fontDisplayName = "خط مخصص (${dest.name})"
                )
                settingsRepo.save(updatedSettings)
                _uiState.update {
                    it.copy(
                        settings = updatedSettings,
                        customTypeface = tf,
                        statusBanner = "تم تفعيل الخط العربي المخصص في المعاينة والرندر."
                    )
                }
                refreshTemplateContactSheet()
            } else {
                _uiState.update { it.copy(statusBanner = "اختر ملف خط بصيغة TTF أو OTF صالحة.") }
            }
        }
    }

    fun resetToBundledFont() {
        val defaultTf = ResourcesCompat.getFont(appContext, R.font.ibm_plex_sans_arabic)
        val updated = _uiState.value.settings.copy(
            fontFolderUri = "",
            fontDisplayName = "IBM Plex Sans Arabic (مدمج)"
        )
        settingsRepo.save(updated)
        _uiState.update {
            it.copy(settings = updated, customTypeface = defaultTf, statusBanner = "تم استعادة خط IBM Plex Sans Arabic.")
        }
        refreshTemplateContactSheet()
    }

    fun setApprovalMode(stage: PipelineStage, mode: ApprovalMode) {
        _uiState.update { state ->
            val updated = state.stages.toMutableMap()
            updated[stage] = (updated[stage] ?: StageState(stage)).copy(approvalMode = mode)
            state.copy(stages = updated)
        }
    }

    fun startNewPipeline(startStage: PipelineStage = PipelineStage.SCRIPT) {
        if (_uiState.value.isPipelineRunning) return
        if (_uiState.value.settings.useLocalMockApis) {
            ensureMockServerStarted()
        }
        val currentProject = if (startStage == PipelineStage.SCRIPT) {
            _uiState.value.project.copy(
                id = UUID.randomUUID().toString().take(8),
                exportedVideoPath = null
            )
        } else {
            _uiState.value.project
        }

        _uiState.update { state ->
            val resetStages = state.stages.mapValues { (stage, st) ->
                if (stage.index >= startStage.index) {
                    st.copy(status = StageStatus.IDLE, progress = 0f, errorMessage = null, logs = emptyList())
                } else st
            }
            state.copy(
                project = currentProject,
                stages = resetStages,
                isPipelineRunning = true,
                statusBanner = null
            )
        }
        launchPipelineFrom(startStage)
    }

    fun approveAndContinue(completedStage: PipelineStage) {
        if (_uiState.value.isPipelineRunning) return
        _uiState.update { state ->
            val map = state.stages.toMutableMap()
            map[completedStage] = (map[completedStage] ?: StageState(completedStage))
                .copy(status = StageStatus.DONE, progress = 1f)
            state.copy(stages = map, isPipelineRunning = true)
        }
        val next = PipelineStage.entries.firstOrNull { it.index == completedStage.index + 1 }
        if (next != null) {
            launchPipelineFrom(next)
        } else {
            _uiState.update { it.copy(isPipelineRunning = false) }
        }
    }

    fun cancelPipeline() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update { state ->
            val fixedStages = state.stages.mapValues { (_, st) ->
                if (st.status == StageStatus.RUNNING) {
                    st.copy(status = StageStatus.IDLE, progress = 0f)
                } else st
            }
            state.copy(isPipelineRunning = false, stages = fixedStages, statusBanner = "تم إيقاف التشغيل.")
        }
    }

    fun updateScriptManually(title: String, scriptBody: String) {
        _uiState.update { state ->
            val updatedScript = state.project.scriptData.copy(
                title = title,
                script = scriptBody
            )
            state.copy(
                project = state.project.copy(scriptData = updatedScript),
                statusBanner = "تم حفظ تعديلات السكربت."
            )
        }
    }

    fun updateStoryboardManually(rawJson: String) {
        val parsed = StoryboardEngine.parseAndRepair(
            rawResponse = rawJson,
            wordCount = _uiState.value.project.alignedWords.size.coerceAtLeast(1),
            iconLibrary = iconLibrary
        )
        val used = iconLibrary.extractUsedIcons(parsed.scenes.flatMap { it.icons })
        _uiState.update { state ->
            state.copy(
                project = state.project.copy(
                    storyboard = parsed,
                    rawStoryboardJson = StoryboardEngine.toJsonString(parsed),
                    usedIcons = used
                ),
                statusBanner = "تم تحديث الستوري بورد والتحقق من الفهارس."
            )
        }
    }

    fun loadVoices() {
        val s = _uiState.value.settings
        if (s.useLocalMockApis) ensureMockServerStarted()
        _uiState.update { it.copy(isLoadingVoices = true) }
        viewModelScope.launch {
            runCatching {
                apiClients.loadElevenLabsVoices(s.effectiveElevenLabsBaseUrl(), s.elevenLabsApiKey)
            }.onSuccess { voices ->
                _uiState.update {
                    val chosen = if (voices.any { v -> v.voiceId == it.settings.selectedVoiceId }) {
                        it.settings.selectedVoiceId
                    } else {
                        voices.firstOrNull()?.voiceId ?: it.settings.selectedVoiceId
                    }
                    val newSettings = it.settings.copy(selectedVoiceId = chosen)
                    settingsRepo.save(newSettings)
                    it.copy(
                        availableVoices = voices,
                        settings = newSettings,
                        isLoadingVoices = false,
                        statusBanner = "تم تحميل ${voices.size} أصوات بنجاح."
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(isLoadingVoices = false, statusBanner = "تعذر تحميل الأصوات: ${err.message}")
                }
            }
        }
    }

    fun updateSettings(
        llmBaseUrl: String? = null,
        llmApiKeyInput: String? = null,
        llmModel: String? = null,
        elevenLabsBaseUrl: String? = null,
        elevenLabsApiKeyInput: String? = null,
        selectedVoiceId: String? = null,
        deepgramBaseUrl: String? = null,
        deepgramApiKeyInput: String? = null,
        useLocalMockApis: Boolean? = null
    ) {
        val cur = _uiState.value.settings
        val updated = cur.copy(
            llmBaseUrl = llmBaseUrl ?: cur.llmBaseUrl,
            llmApiKey = if (!llmApiKeyInput.isNullOrBlank()) llmApiKeyInput.trim() else cur.llmApiKey,
            llmModel = llmModel ?: cur.llmModel,
            elevenLabsBaseUrl = elevenLabsBaseUrl ?: cur.elevenLabsBaseUrl,
            elevenLabsApiKey = if (!elevenLabsApiKeyInput.isNullOrBlank()) elevenLabsApiKeyInput.trim() else cur.elevenLabsApiKey,
            selectedVoiceId = selectedVoiceId ?: cur.selectedVoiceId,
            deepgramBaseUrl = deepgramBaseUrl ?: cur.deepgramBaseUrl,
            deepgramApiKey = if (!deepgramApiKeyInput.isNullOrBlank()) deepgramApiKeyInput.trim() else cur.deepgramApiKey,
            useLocalMockApis = useLocalMockApis ?: cur.useLocalMockApis
        )
        settingsRepo.save(updated)
        if (updated.useLocalMockApis) ensureMockServerStarted() else mockServer.stop()
        _uiState.update {
            it.copy(
                settings = updated,
                maskedKeys = settingsRepo.getMaskedKeys(updated),
                mockServerPort = if (updated.useLocalMockApis) mockServer.activePort else null,
                statusBanner = "تم حفظ الإعدادات في data/settings.json"
            )
        }
    }

    fun refreshTemplateContactSheet() {
        viewModelScope.launch(Dispatchers.Default) {
            val bmp = ReelRenderer.renderContactSheet(
                project = _uiState.value.project,
                iconLibrary = iconLibrary,
                typeface = _uiState.value.customTypeface
            )
            _uiState.update { it.copy(contactSheetBitmap = bmp) }
        }
    }

    fun shareExportedVideoIntent(): Intent? {
        val path = _uiState.value.project.exportedVideoPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clearBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    private fun launchPipelineFrom(startStage: PipelineStage) {
        activeJob = viewModelScope.launch {
            val callbacks = object : PipelineEngine.Callbacks {
                override fun onStageUpdate(stage: PipelineStage, transform: (StageState) -> StageState) {
                    _uiState.update { state ->
                        val map = state.stages.toMutableMap()
                        map[stage] = transform(map[stage] ?: StageState(stage))
                        state.copy(stages = map)
                    }
                }

                override fun onLog(stage: PipelineStage, message: String) {
                    val line = "[${PipelineEngine.timestampPrefix()}] $message"
                    onStageUpdate(stage) { st -> st.copy(logs = (st.logs + line).takeLast(40)) }
                }

                override fun onProjectUpdate(transform: (ReelProject) -> ReelProject) {
                    _uiState.update { state -> state.copy(project = transform(state.project)) }
                }

                override fun onPreviewFrame(bitmap: Bitmap?) {
                    if (bitmap != null) {
                        _uiState.update { it.copy(livePreviewBitmap = bitmap) }
                    }
                }
            }

            try {
                pipelineEngine.runFromStage(
                    startStage = startStage,
                    initialProject = _uiState.value.project,
                    settings = _uiState.value.settings,
                    stageStates = _uiState.value.stages,
                    customTypeface = _uiState.value.customTypeface,
                    callbacks = callbacks
                )
            } finally {
                _uiState.update { it.copy(isPipelineRunning = false) }
            }
        }
    }

    private fun loadCustomTypefaceFromUri(path: String): Typeface? {
        if (path.isBlank()) return null
        val f = File(path)
        return if (f.exists()) runCatching { Typeface.createFromFile(f) }.getOrNull() else null
    }

    override fun onCleared() {
        super.onCleared()
        mockServer.stop()
    }
}
