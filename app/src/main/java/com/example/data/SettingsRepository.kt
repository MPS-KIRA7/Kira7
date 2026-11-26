package com.example.data

import android.content.Context
import com.example.model.AppSettings
import org.json.JSONObject
import java.io.File

class SettingsRepository(private val context: Context) {

    private val settingsFile: File
        get() {
            val dir = File(context.filesDir, "data")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "settings.json")
        }

    fun load(): AppSettings {
        val envDefaults = AppSettings(
            llmBaseUrl = System.getenv("LLM_BASE_URL") ?: "http://127.0.0.1:31415/v1",
            llmApiKey = System.getenv("LLM_API_KEY") ?: "",
            llmModel = System.getenv("LLM_MODEL") ?: "auto",
            elevenLabsBaseUrl = System.getenv("ELEVENLABS_BASE_URL") ?: "https://api.elevenlabs.io",
            elevenLabsApiKey = System.getenv("ELEVENLABS_API_KEY") ?: "",
            deepgramBaseUrl = System.getenv("DEEPGRAM_BASE_URL") ?: "https://api.deepgram.com",
            deepgramApiKey = System.getenv("DEEPGRAM_API_KEY") ?: "",
            useLocalMockApis = true
        )
        val file = settingsFile
        if (!file.exists()) {
            save(envDefaults)
            return envDefaults
        }
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            AppSettings(
                llmBaseUrl = json.optString("llmBaseUrl", envDefaults.llmBaseUrl),
                llmApiKey = json.optString("llmApiKey", envDefaults.llmApiKey),
                llmModel = json.optString("llmModel", envDefaults.llmModel),
                elevenLabsBaseUrl = json.optString("elevenLabsBaseUrl", envDefaults.elevenLabsBaseUrl),
                elevenLabsApiKey = json.optString("elevenLabsApiKey", envDefaults.elevenLabsApiKey),
                selectedVoiceId = json.optString("selectedVoiceId", envDefaults.selectedVoiceId),
                deepgramBaseUrl = json.optString("deepgramBaseUrl", envDefaults.deepgramBaseUrl),
                deepgramApiKey = json.optString("deepgramApiKey", envDefaults.deepgramApiKey),
                fontFolderUri = json.optString("fontFolderUri", ""),
                fontDisplayName = json.optString("fontDisplayName", envDefaults.fontDisplayName),
                useLocalMockApis = json.optBoolean("useLocalMockApis", true)
            )
        } catch (_: Exception) {
            envDefaults
        }
    }

    fun save(settings: AppSettings) {
        val json = JSONObject().apply {
            put("llmBaseUrl", settings.llmBaseUrl)
            put("llmApiKey", settings.llmApiKey)
            put("llmModel", settings.llmModel)
            put("elevenLabsBaseUrl", settings.elevenLabsBaseUrl)
            put("elevenLabsApiKey", settings.elevenLabsApiKey)
            put("selectedVoiceId", settings.selectedVoiceId)
            put("deepgramBaseUrl", settings.deepgramBaseUrl)
            put("deepgramApiKey", settings.deepgramApiKey)
            put("fontFolderUri", settings.fontFolderUri)
            put("fontDisplayName", settings.fontDisplayName)
            put("useLocalMockApis", settings.useLocalMockApis)
        }
        settingsFile.writeText(json.toString(2), Charsets.UTF_8)
    }

    data class MaskedSettingsSummary(
        val llmKeyMask: String,
        val elevenLabsKeyMask: String,
        val deepgramKeyMask: String
    )

    fun getMaskedKeys(settings: AppSettings): MaskedSettingsSummary =
        MaskedSettingsSummary(
            llmKeyMask = AppSettings.maskKey(settings.llmApiKey),
            elevenLabsKeyMask = AppSettings.maskKey(settings.elevenLabsApiKey),
            deepgramKeyMask = AppSettings.maskKey(settings.deepgramApiKey)
        )
}
