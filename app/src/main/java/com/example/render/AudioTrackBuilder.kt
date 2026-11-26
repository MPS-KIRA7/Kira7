package com.example.render

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

object AudioTrackBuilder {

    const val TARGET_SAMPLE_RATE = 44100

    /**
     * Loads voice audio (WAV or compressed MP3/M4A via MediaExtractor+MediaCodec),
     * mixes soft synthesized whoosh/tick SFX on scene cuts, and normalizes loudness to -14 LUFS.
     */
    fun buildNormalizedMixedPcm(
        voiceFile: File?,
        totalDurationSec: Double,
        sceneCutTimesSec: List<Double>,
        outputSampleRate: Int = TARGET_SAMPLE_RATE
    ): ShortArray {
        val totalSamples = (totalDurationSec * outputSampleRate).toInt().coerceAtLeast(outputSampleRate)
        val mix = FloatArray(totalSamples)

        if (voiceFile != null && voiceFile.exists()) {
            val decoded = decodeAudioToMonoFloat(voiceFile, outputSampleRate)
            val copyLen = minOf(decoded.size, mix.size)
            for (i in 0 until copyLen) {
                mix[i] = decoded[i]
            }
        }

        // Synthesize soft whoosh + tick SFX on scene transitions (skip t=0)
        for (cutSec in sceneCutTimesSec) {
            if (cutSec <= 0.05 || cutSec >= totalDurationSec - 0.05) continue
            addSceneCutSfx(mix, cutSec, outputSampleRate)
        }

        // Measure integrated RMS / LUFS proxy and normalize to -14 LUFS
        normalizeToTargetLufs(mix, targetLufs = -14.0)

        return ShortArray(mix.size) { i ->
            (mix[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun writeWavFile(pcm: ShortArray, sampleRate: Int, outFile: File) {
        outFile.parentFile?.mkdirs()
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(1)
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)
        buf.putShort(2)
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm) buf.putShort(s)
        outFile.writeBytes(buf.array())
    }

    private fun addSceneCutSfx(buffer: FloatArray, cutSec: Double, sampleRate: Int) {
        val centerSample = (cutSec * sampleRate).toInt()
        // 1. Soft filtered whoosh (180ms)
        val whooshLen = (0.18 * sampleRate).toInt()
        val whooshStart = (centerSample - whooshLen / 2).coerceAtLeast(0)
        val whooshEnd = (whooshStart + whooshLen).coerceAtMost(buffer.size)
        var noiseState = 1234567
        var lp = 0f
        for (i in whooshStart until whooshEnd) {
            val u = (i - whooshStart).toFloat() / whooshLen
            val env = sin(PI * u).toFloat().pow(1.6f) * 0.085f
            noiseState = noiseState * 1664525 + 1013904223
            val white = ((noiseState ushr 16) and 0xFFFF) / 32768f - 1f
            val cutoff = 0.08f + 0.22f * sin(PI * u).toFloat()
            lp += cutoff * (white - lp)
            buffer[i] += lp * env
        }

        // 2. Crisp gentle UI tick at exact cut (45ms)
        val tickLen = (0.045 * sampleRate).toInt()
        val tickEnd = (centerSample + tickLen).coerceAtMost(buffer.size)
        for (i in centerSample.coerceAtLeast(0) until tickEnd) {
            val t = (i - centerSample).toDouble() / sampleRate
            val env = exp(-t * 95.0).toFloat() * 0.14f
            val tone = sin(2.0 * PI * 980.0 * t).toFloat() * 0.6f +
                sin(2.0 * PI * 1470.0 * t).toFloat() * 0.4f
            buffer[i] += tone * env
        }
    }

    fun normalizeToTargetLufs(samples: FloatArray, targetLufs: Double = -14.0) {
        if (samples.isEmpty()) return
        // Gated RMS approximation (-0.691 + 10*log10(meanSq))
        var sumSq = 0.0
        var activeCount = 0
        for (s in samples) {
            val a = abs(s)
            if (a > 0.002f) {
                sumSq += s * s
                activeCount++
            }
        }
        val effectiveCount = activeCount.coerceAtLeast(samples.size / 6).coerceAtLeast(1)
        val meanSq = (sumSq / effectiveCount).coerceAtLeast(1e-8)
        val currentLufs = -0.691 + 10.0 * log10(meanSq)
        val gainDb = (targetLufs - currentLufs).coerceIn(-18.0, 22.0)
        val gain = 10.0.pow(gainDb / 20.0).toFloat()

        for (i in samples.indices) {
            val amplified = samples[i] * gain
            // Soft-knee limiting at 0.94 to preserve transient clarity
            samples[i] = if (abs(amplified) > 0.85f) {
                tanh(amplified.toDouble()).toFloat() * 0.94f
            } else {
                amplified
            }
        }
    }

    fun measureApproxLufs(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return -70.0
        var sumSq = 0.0
        var count = 0
        for (s in pcm) {
            val f = s / 32768.0
            if (abs(f) > 0.002) {
                sumSq += f * f
                count++
            }
        }
        val effective = count.coerceAtLeast(pcm.size / 6).coerceAtLeast(1)
        val meanSq = (sumSq / effective).coerceAtLeast(1e-9)
        return -0.691 + 10.0 * log10(meanSq)
    }

    private fun decodeAudioToMonoFloat(file: File, targetRate: Int): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size >= 44 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()
        ) {
            return parseWavToMonoFloat(bytes, targetRate)
        }
        return runCatching { decodeWithMediaCodec(file, targetRate) }.getOrElse { FloatArray(0) }
    }

    private fun parseWavToMonoFloat(bytes: ByteArray, targetRate: Int): FloatArray {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt().coerceAtLeast(1)
        val srcRate = bb.getInt(24).coerceAtLeast(8000)
        val bitsPerSample = bb.getShort(34).toInt().coerceAtLeast(8)

        // Find "data" chunk
        var offset = 12
        var dataStart = 44
        var dataSize = bytes.size - 44
        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkLen = bb.getInt(offset + 4)
            if (chunkId == "data") {
                dataStart = offset + 8
                dataSize = chunkLen.coerceAtMost(bytes.size - dataStart)
                break
            }
            offset += 8 + chunkLen + (chunkLen % 2)
        }

        val bytesPerFrame = (bitsPerSample / 8) * channels
        val frameCount = (dataSize / bytesPerFrame).coerceAtLeast(0)
        val monoSrc = FloatArray(frameCount)
        for (f in 0 until frameCount) {
            var acc = 0f
            val base = dataStart + f * bytesPerFrame
            for (c in 0 until channels) {
                val sampleIdx = base + c * (bitsPerSample / 8)
                val v = if (bitsPerSample == 16 && sampleIdx + 1 < bytes.size) {
                    bb.getShort(sampleIdx) / 32768f
                } else {
                    0f
                }
                acc += v
            }
            monoSrc[f] = acc / channels
        }
        return resampleLinear(monoSrc, srcRate, targetRate)
    }

    private fun decodeWithMediaCodec(file: File, targetRate: Int): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = fmt
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return FloatArray(0)
        }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmChunks = mutableListOf<Float>()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val outBuf = codec.getOutputBuffer(outIdx)
                if (outBuf != null && info.size > 0) {
                    outBuf.position(info.offset)
                    outBuf.limit(info.offset + info.size)
                    val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val shorts = ShortArray(sb.remaining())
                    sb.get(shorts)
                    var i = 0
                    while (i + channels - 1 < shorts.size) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            sum += shorts[i + c] / 32768f
                        }
                        pcmChunks.add(sum / channels)
                        i += channels
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEos = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
        return resampleLinear(pcmChunks.toFloatArray(), srcRate, targetRate)
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (input.isEmpty() || srcRate == dstRate) return input
        val outLen = ((input.size.toLong() * dstRate) / srcRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = srcRate.toDouble() / dstRate
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt().coerceIn(0, input.lastIndex)
            val next = (idx + 1).coerceIn(0, input.lastIndex)
            val frac = (pos - idx).toFloat()
            out[i] = input[idx] * (1f - frac) + input[next] * frac
        }
        return out
    }
}
