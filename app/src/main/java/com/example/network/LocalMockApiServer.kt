package com.example.network

import com.example.model.ScriptData
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Self-contained local HTTP/1.1 + SSE mock API server on 127.0.0.1:4399
 * mirroring FreeLLMAPI, ElevenLabs /with-timestamps, and Deepgram /v1/listen
 * so the full 5-stage pipeline runs offline end-to-end.
 */
class LocalMockApiServer(private val preferredPort: Int = 4399) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    val activePort: Int
        get() = serverSocket?.localPort ?: preferredPort

    fun start(): Int {
        if (running && serverSocket != null && serverSocket?.isClosed == false) {
            return activePort
        }
        val socket = ServerSocket(preferredPort, 32, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        running = true
        thread(name = "ReelMockApiServer", isDaemon = true) {
            while (running && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    thread(name = "MockClientHandler", isDaemon = true) {
                        handleClient(client)
                    }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()

            val requestLine = readLineAscii(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = fullPath.substringBefore("?")

            val headers = linkedMapOf<String, String>()
            while (true) {
                val h = readLineAscii(input) ?: break
                if (h.isEmpty()) break
                val colon = h.indexOf(':')
                if (colon > 0) {
                    headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = input.read(buf, readTotal, contentLength - readTotal)
                    if (r < 0) break
                    readTotal += r
                }
                buf
            } else {
                ByteArray(0)
            }

            when {
                method == "POST" && path.endsWith("/chat/completions") -> {
                    handleChatCompletions(String(bodyBytes, Charsets.UTF_8), out)
                }
                method == "GET" && path.endsWith("/v1/voices") -> {
                    handleVoices(out)
                }
                method == "POST" && path.contains("/v1/text-to-speech/") -> {
                    handleTtsWithTimestamps(String(bodyBytes, Charsets.UTF_8), out)
                }
                method == "POST" && path.endsWith("/v1/listen") -> {
                    val hintB64 = headers["x-mock-script-hint"].orEmpty()
                    val hintText = if (hintB64.isNotBlank()) {
                        hintB64.decodeBase64()?.utf8().orEmpty()
                    } else {
                        ""
                    }
                    handleDeepgramListen(hintText, out)
                }
                else -> {
                    writeJsonResponse(out, 404, """{"error":"Not found: $path"}""")
                }
            }
        }
    }

    private fun handleChatCompletions(bodyStr: String, out: OutputStream) {
        val req = runCatching { JSONObject(bodyStr) }.getOrElse { JSONObject() }
        val stream = req.optBoolean("stream", false)
        val messages = req.optJSONArray("messages") ?: JSONArray()
        val allText = buildString {
            for (i in 0 until messages.length()) {
                append(messages.getJSONObject(i).optString("content", ""))
                append("\n")
            }
        }

        val responseContent = when {
            allText.contains("راجع JSON الستوري بورد") || allText.contains("الستوري بورد الحالي") -> {
                buildMockSelfReviewedStoryboard(allText)
            }
            allText.contains("كلمات السكربت المفهرسة") -> {
                buildMockStoryboard(allText)
            }
            else -> {
                buildMockScriptWithThink(allText)
            }
        }

        if (!stream) {
            val payload = JSONObject().apply {
                put("id", "mock-chat-1")
                put(
                    "choices",
                    JSONArray().put(
                        JSONObject().apply {
                            put("index", 0)
                            put(
                                "message",
                                JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", responseContent)
                                }
                            )
                        }
                    )
                )
            }
            writeJsonResponse(out, 200, payload.toString())
            return
        }

        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))

        val chunkSize = 24
        var idx = 0
        while (idx < responseContent.length) {
            val end = minOf(idx + chunkSize, responseContent.length)
            val piece = responseContent.substring(idx, end)
            val sseJson = JSONObject().apply {
                put(
                    "choices",
                    JSONArray().put(
                        JSONObject().apply {
                            put("index", 0)
                            put("delta", JSONObject().put("content", piece))
                        }
                    )
                )
            }
            out.write("data: ${sseJson}\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
            Thread.sleep(16)
            idx = end
        }
        out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun buildMockScriptWithThink(promptText: String): String {
        val topic = Regex("الموضوع:\\s*\"([^\"]+)\"").find(promptText)?.groupValues?.getOrNull(1)
            ?: "ليه السماء لونها أزرق؟"
        val durationSec = Regex("المدة المطلوبة:\\s*(\\d+)").find(promptText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 10

        val isLonger = durationSec >= 20
        val coreScript = if (!isLonger) {
            """
                <think>
                سأكتب سكربت سريع حوالي 26 كلمة يناسب 10 ثواني باللهجة المصرية مع سؤال عارف ليه؟ وتشبيه بسيط ومصطلحات إنجليزية.
                </think>
                TITLE: سر اللون الأزرق للسماء
                SCRIPT:
                بتبص للسماء الصبح وتشوفها زرقا مع إن الشمس نورها أبيض؟
                عارف ليه؟
                ضوء الشمس فيه كل الألوان، زي عربية صغيرة ماشية في زحمة.
                جزيئات الغلاف الجوي بتعمل Rayleigh Scattering فتشتت Photons اللون الأزرق في كل حتة.
                والخلاصة: السماء زرقا لأن جزيئات الهواء بتبعتر الأزرق أكتر من الباقي.
                SOURCES:
                - NASA Space Place: Why Is the Sky Blue?
                - National Oceanic and Atmospheric Administration (NOAA)
            """.trimIndent()
        } else {
            """
                <think>
                تخطيط سكربت أطول يناسب $durationSec ثانية بمعدل 2.6 كلمة في الثانية حول $topic.
                </think>
                TITLE: $topic
                SCRIPT:
                كل يوم بتشوف الظاهرة دي قدام عينك بس عمرك وقفت وسألت نفسك السبب الحقيقي؟
                عارف ليه؟
                تخيل جسمك أو البيئة حواليك زي شبكة إشارات دقيقة شغالة على طول.
                أول ما يدخل مؤثر زي Photons أو إشارة Dopamine، الخلايا بتستقبله بسرعة كبيرة.
                العملية دي اسمها Signal Transduction وبتحوّل الطاقة لإشارة واضحة ومفهومة.
                ولما تتكرر كل يوم، الجسم بيظبط الـ Homeostasis عشان يفضل متوازن ومستقر.
                والخلاصة: ورا كل تفصيلة بسيطة قانون علمي ذكي بيحافظ على التوازن.
                SOURCES:
                - Nature Reviews Science Explainers
                - MIT OpenCourseWare Physics & Biology
            """.trimIndent()
        }
        return coreScript
    }

    private fun buildMockStoryboard(promptText: String): String {
        val wordsCount = Regex("\\[(\\d+):").findAll(promptText).count().coerceAtLeast(12)
        val p1 = (wordsCount * 0.16).toInt().coerceAtLeast(1)
        val p2 = (wordsCount * 0.34).toInt().coerceAtLeast(p1 + 1)
        val p3 = (wordsCount * 0.54).toInt().coerceAtLeast(p2 + 1)
        val p4 = (wordsCount * 0.74).toInt().coerceAtLeast(p3 + 1)
        val p5 = (wordsCount * 0.88).toInt().coerceAtLeast(p4 + 1)

        return """
            {
              "scenes": [
                {
                  "type": "intro",
                  "template": "zoom",
                  "at": 0,
                  "title": "لغز الضوء الأبيض",
                  "subtitle": "رصد الظاهرة من السطح للغلاف الجوي",
                  "icons": ["sun", "eye"],
                  "items": ["ضوء أبيض", "غلاف جوي", "تشتت الأزرق"]
                },
                {
                  "type": "question",
                  "template": "cards",
                  "at": $p1,
                  "title": "عارف ليه؟",
                  "subtitle": "ليه الأزرق بالذات بيغلب؟",
                  "icons": ["question", "wave"],
                  "items": ["طول موجي قصير", "تفاعل سريع"]
                },
                {
                  "type": "concept",
                  "template": "flow",
                  "at": $p2,
                  "title": "مسار الفوتونات",
                  "subtitle": "رحلة Photons من الشمس للهواء",
                  "icons": ["sun", "atom", "wave"],
                  "items": ["الشمس", "جزيئات N2", "انتشار الأزرق"]
                },
                {
                  "type": "process",
                  "template": "layers",
                  "at": $p3,
                  "title": "Rayleigh Scattering",
                  "subtitle": "تشتت انتقائي عبر طبقات الجو",
                  "icons": ["atom", "lightning"],
                  "items": ["الطبقة العليا", "تشتت أزرق", "وصول للأرض"]
                },
                {
                  "type": "stat",
                  "template": "meter",
                  "at": $p4,
                  "title": "كفاءة التشتت",
                  "subtitle": "الأزرق بيتشتت أضعاف الأحمر",
                  "icons": ["chart", "wave"],
                  "metricValue": "9.4x",
                  "metricPercent": 0.86
                },
                {
                  "type": "recap",
                  "template": "timeline",
                  "at": $p5,
                  "title": "الخلاصة",
                  "subtitle": "الهواء يشتت الأزرق في كل اتجاه",
                  "icons": ["check", "star"],
                  "items": ["فوتون", "اصطدام", "سماء زرقاء"]
                }
              ],
              "accents": [
                [0, "cyan"],
                [$p1, "warning"],
                [$p2, "indigo"],
                [$p3, "cyan"],
                [$p5, "success"]
              ]
            }
        """.trimIndent()
    }

    private fun buildMockSelfReviewedStoryboard(promptText: String): String {
        val start = promptText.indexOf('{')
        val end = promptText.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching {
                val obj = JSONObject(promptText.substring(start, end + 1))
                val scenes = obj.optJSONArray("scenes")
                if (scenes != null && scenes.length() >= 4) {
                    // Self-review polish: upgrade 4th scene to chart or cycle for visual variety
                    val target = scenes.getJSONObject(3)
                    if (target.optString("template") == "layers") {
                        target.put("template", "chart")
                        target.put("items", JSONArray(listOf("الأحمر 1x", "الأخضر 3x", "الأزرق 9x")))
                    }
                }
                obj.put("reviewNotes", "تمت المراجعة الذاتية: تنويع القوالب البصرية وتحسين تباين الألوان.")
                obj.toString(2)
            }.getOrElse { buildMockStoryboard(promptText) }
        }
        return buildMockStoryboard(promptText)
    }

    private fun handleVoices(out: OutputStream) {
        val json = """
            {
              "voices": [
                {"voice_id": "arabic_science_narrator", "name": "طارق — راوي علمي مصري", "category": "cloned"},
                {"voice_id": "egyptian_storyteller", "name": "كريم — سرد سريع للريلز", "category": "premade"},
                {"voice_id": "calm_explainer_f", "name": "ليلى — شرح هادئ وواضح", "category": "premade"}
              ]
            }
        """.trimIndent()
        writeJsonResponse(out, 200, json)
    }

    private fun handleTtsWithTimestamps(bodyStr: String, out: OutputStream) {
        val req = runCatching { JSONObject(bodyStr) }.getOrElse { JSONObject() }
        val text = req.optString("text", "عارف ليه السماء لونها أزرق؟")
        val targetDuration = req.optInt("target_duration_sec", 10).coerceIn(5, 90)
        val tokens = ScriptData.tokenizeWords(text).ifEmpty { listOf("عارف", "ليه") }

        // Build per-character timings and realistic synthesized voice-cadence WAV audio
        val chars = JSONArray()
        val starts = JSONArray()
        val ends = JSONArray()

        val totalSpeechSec = maxOf(targetDuration.toDouble() * 0.94, tokens.size * 0.31)
        val leadIn = 0.15
        val perWordTime = (totalSpeechSec - leadIn) / tokens.size

        val wordWindows = mutableListOf<Pair<Double, Double>>()
        var cursor = leadIn
        tokens.forEachIndexed { wIdx, word ->
            val wStart = cursor
            val wDur = (perWordTime * 0.86).coerceAtLeast(0.14)
            val wEnd = wStart + wDur
            wordWindows.add(wStart to wEnd)

            val charStep = wDur / word.length.coerceAtLeast(1)
            for (cIdx in word.indices) {
                chars.put(word[cIdx].toString())
                starts.put(wStart + cIdx * charStep)
                ends.put(wStart + (cIdx + 1) * charStep)
            }
            if (wIdx < tokens.lastIndex) {
                chars.put(" ")
                starts.put(wEnd)
                ends.put(wStart + perWordTime)
            }
            cursor += perWordTime
        }

        val wavBytes = synthesizeCadenceWav(
            durationSec = (cursor + 0.25).coerceAtLeast(targetDuration.toDouble()),
            wordWindows = wordWindows
        )
        val audioBase64 = wavBytes.toByteString().base64()

        val response = JSONObject().apply {
            put("audio_base64", audioBase64)
            put(
                "alignment",
                JSONObject().apply {
                    put("characters", chars)
                    put("character_start_times_seconds", starts)
                    put("character_end_times_seconds", ends)
                }
            )
        }
        writeJsonResponse(out, 200, response.toString())
    }

    private fun handleDeepgramListen(hintText: String, out: OutputStream) {
        val tokens = ScriptData.tokenizeWords(
            hintText.ifBlank { "عارف ليه السماء لونها أزرق كل يوم الصبح" }
        )
        val wordsArr = JSONArray()
        var t = 0.18
        tokens.forEach { w ->
            val dur = 0.32
            wordsArr.put(
                JSONObject().apply {
                    put("word", w)
                    put("punctuated_word", w)
                    put("start", t)
                    put("end", t + dur * 0.88)
                    put("confidence", 0.94)
                }
            )
            t += dur
        }
        val resp = JSONObject().apply {
            put(
                "results",
                JSONObject().put(
                    "channels",
                    JSONArray().put(
                        JSONObject().put(
                            "alternatives",
                            JSONArray().put(
                                JSONObject().put("words", wordsArr)
                            )
                        )
                    )
                )
            )
        }
        writeJsonResponse(out, 200, resp.toString())
    }

    private fun synthesizeCadenceWav(
        durationSec: Double,
        wordWindows: List<Pair<Double, Double>>,
        sampleRate: Int = 22050
    ): ByteArray {
        val totalSamples = (durationSec * sampleRate).toInt().coerceAtLeast(sampleRate)
        val pcm = ShortArray(totalSamples)

        for ((idx, window) in wordWindows.withIndex()) {
            val s0 = (window.first * sampleRate).toInt().coerceIn(0, totalSamples - 1)
            val s1 = (window.second * sampleRate).toInt().coerceIn(s0 + 1, totalSamples)
            val baseFreq = 135.0 + (idx % 5) * 14.0
            val len = (s1 - s0).toDouble()
            for (i in s0 until s1) {
                val u = (i - s0) / len
                val env = sin(PI * u) * exp(-0.4 * u)
                val t = i.toDouble() / sampleRate
                val sample = (
                    0.55 * sin(2.0 * PI * baseFreq * t) +
                        0.28 * sin(2.0 * PI * (baseFreq * 2.02) * t) +
                        0.12 * sin(2.0 * PI * (baseFreq * 3.1) * t)
                    ) * env * 0.35
                pcm[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        val dataSize = pcm.size * 2
        val byteBuffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        byteBuffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        byteBuffer.putInt(36 + dataSize)
        byteBuffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        byteBuffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        byteBuffer.putInt(16) // PCM chunk size
        byteBuffer.putShort(1) // AudioFormat = PCM
        byteBuffer.putShort(1) // Mono
        byteBuffer.putInt(sampleRate)
        byteBuffer.putInt(sampleRate * 2)
        byteBuffer.putShort(2)
        byteBuffer.putShort(16)
        byteBuffer.put("data".toByteArray(Charsets.US_ASCII))
        byteBuffer.putInt(dataSize)
        for (s in pcm) {
            byteBuffer.putShort(s)
        }
        return byteBuffer.array()
    }

    private fun readLineAscii(input: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString("UTF-8")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) baos.write(b)
        }
        return baos.toString("UTF-8")
    }

    private fun writeJsonResponse(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusText = if (status == 200) "OK" else "Not Found"
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
