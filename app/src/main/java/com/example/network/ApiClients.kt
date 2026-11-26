package com.example.network

import com.example.core.ThinkBlockStripper
import com.example.core.TimingAligner
import com.example.model.TimedWord
import com.example.model.VoiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ApiClients(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    suspend fun streamChatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        onDelta: (cleanDelta: String, accumulatedClean: String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val bodyJson = JSONObject().apply {
            put("model", model.ifBlank { "auto" })
            put("stream", true)
            put("temperature", 0.7)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                }
            )
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Bearer $apiKey")
        }

        val call = httpClient.newCall(reqBuilder.build())
        val stripper = ThinkBlockStripper()
        val fullClean = StringBuilder()

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(300) ?: "HTTP ${response.code}"
                    throw IllegalStateException("LLM API error (${response.code}): $err")
                }
                val source = response.body?.source() ?: throw IllegalStateException("Empty LLM response body")
                val contentType = response.header("Content-Type").orEmpty()

                if (contentType.contains("application/json") && !contentType.contains("event-stream")) {
                    val raw = source.readUtf8()
                    val obj = JSONObject(raw)
                    val content = obj.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                    val clean = ThinkBlockStripper.stripAll(content)
                    onDelta(clean, clean)
                    return@withContext clean
                }

                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue

                    val chunkObj = JSONObject(payload)
                    val choices = chunkObj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val deltaObj = choices.getJSONObject(0).optJSONObject("delta")
                    val messageObj = choices.getJSONObject(0).optJSONObject("message")
                    val rawDelta = deltaObj?.optString("content", "")
                        ?: messageObj?.optString("content", "")
                        ?: ""
                    if (rawDelta.isNotEmpty()) {
                        val cleanDelta = stripper.feed(rawDelta)
                        if (cleanDelta.isNotEmpty()) {
                            fullClean.append(cleanDelta)
                            onDelta(cleanDelta, fullClean.toString())
                        }
                    }
                }
                val tail = stripper.flush()
                if (tail.isNotEmpty()) {
                    fullClean.append(tail)
                    onDelta(tail, fullClean.toString())
                }
            }
        } catch (t: Throwable) {
            call.cancel()
            throw t
        }
        fullClean.toString().trim()
    }

    suspend fun loadElevenLabsVoices(
        baseUrl: String,
        apiKey: String
    ): List<VoiceInfo> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/v1/voices"
        val reqBuilder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank()) {
            reqBuilder.header("xi-api-key", apiKey)
        }
        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("ElevenLabs voices error (${response.code})")
            }
            val raw = response.body?.string() ?: "{}"
            val arr = JSONObject(raw).optJSONArray("voices") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.getJSONObject(i)
                    add(
                        VoiceInfo(
                            voiceId = v.getString("voice_id"),
                            name = v.optString("name", v.getString("voice_id")),
                            category = v.optString("category", "")
                        )
                    )
                }
            }
        }
    }

    data class TtsResult(
        val audioFile: File,
        val timedWords: List<TimedWord>
    )

    suspend fun synthesizeElevenLabsWithTimestamps(
        baseUrl: String,
        apiKey: String,
        voiceId: String,
        text: String,
        targetDurationSec: Int,
        outputFile: File
    ): TtsResult = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/v1/text-to-speech/${voiceId.ifBlank { "arabic_science_narrator" }}/with-timestamps"
        val payload = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_multilingual_v2")
            put("target_duration_sec", targetDurationSec)
            put(
                "voice_settings",
                JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.78)
                }
            )
        }
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.header("xi-api-key", apiKey)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(250) ?: "HTTP ${response.code}"
                throw IllegalStateException("ElevenLabs TTS error (${response.code}): $err")
            }
            val obj = JSONObject(response.body?.string() ?: "{}")
            val audioB64 = obj.getString("audio_base64")
            val audioBytes = audioB64.decodeBase64()?.toByteArray()
                ?: throw IllegalStateException("Invalid audio base64")
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(audioBytes)

            val alignment = obj.optJSONObject("alignment")
            val words = if (alignment != null) {
                val charsArr = alignment.optJSONArray("characters") ?: JSONArray()
                val startsArr = alignment.optJSONArray("character_start_times_seconds") ?: JSONArray()
                val endsArr = alignment.optJSONArray("character_end_times_seconds") ?: JSONArray()
                val chars = List(charsArr.length()) { charsArr.getString(it) }
                val starts = List(startsArr.length()) { startsArr.getDouble(it) }
                val ends = List(endsArr.length()) { endsArr.getDouble(it) }
                TimingAligner.elevenLabsCharsToWords(chars, starts, ends)
            } else {
                emptyList()
            }
            TtsResult(audioFile = outputFile, timedWords = words)
        }
    }

    suspend fun transcribeDeepgramWords(
        baseUrl: String,
        apiKey: String,
        audioFile: File,
        mimeType: String = "audio/mpeg",
        fallbackScriptText: String = ""
    ): List<TimedWord> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/v1/listen?model=nova-3&language=ar&punctuate=true"
        val bytes = audioFile.readBytes()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("X-Mock-Script-Hint", fallbackScriptText.toByteArray(Charsets.UTF_8).toByteString().base64())
            .post(bytes.toRequestBody(mimeType.toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.header("Authorization", "Token $apiKey")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string()?.take(250) ?: "HTTP ${response.code}"
                throw IllegalStateException("Deepgram error (${response.code}): $err")
            }
            val root = JSONObject(response.body?.string() ?: "{}")
            val wordsArr = root.optJSONObject("results")
                ?.optJSONArray("channels")
                ?.optJSONObject(0)
                ?.optJSONArray("alternatives")
                ?.optJSONObject(0)
                ?.optJSONArray("words")
                ?: JSONArray()

            buildList {
                for (i in 0 until wordsArr.length()) {
                    val w = wordsArr.getJSONObject(i)
                    val text = w.optString("punctuated_word", w.optString("word", ""))
                    if (text.isNotBlank()) {
                        add(
                            TimedWord(
                                index = size,
                                text = text,
                                startSec = w.optDouble("start", 0.0),
                                endSec = w.optDouble("end", 0.2),
                                confidence = w.optDouble("confidence", 0.9)
                            )
                        )
                    }
                }
            }
        }
    }
}
