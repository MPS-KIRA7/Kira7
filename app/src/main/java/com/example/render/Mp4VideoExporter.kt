package com.example.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.model.ReelProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Mp4VideoExporter {

    private const val FPS = 30
    private const val VIDEO_BITRATE = 4_500_000
    private const val AUDIO_BITRATE = 128_000

    data class ExportResult(
        val mp4File: File,
        val frameCount: Int,
        val durationSec: Double,
        val measuredLufs: Double
    )

    suspend fun exportReel(
        project: ReelProject,
        outputMp4: File,
        typeface: Typeface? = null,
        onProgress: (progress: Float, frameIdx: Int, totalFrames: Int, previewBitmap: Bitmap?) -> Unit
    ): ExportResult = withContext(Dispatchers.Default) {
        outputMp4.parentFile?.mkdirs()
        if (outputMp4.exists()) outputMp4.delete()

        val durationSec = project.totalDurationSec.coerceAtLeast(2.0)
        val totalFrames = (durationSec * FPS).toInt().coerceAtLeast(30)
        val sceneCutTimes = ReelRenderer.resolveSceneStartTimes(
            project.storyboard.scenes,
            project.alignedWords,
            durationSec
        )
        val voiceFile = project.voiceAudioPath?.let { File(it) }
        val mixedPcm = AudioTrackBuilder.buildNormalizedMixedPcm(
            voiceFile = voiceFile,
            totalDurationSec = durationSec,
            sceneCutTimesSec = sceneCutTimes,
            outputSampleRate = AudioTrackBuilder.TARGET_SAMPLE_RATE
        )
        val measuredLufs = AudioTrackBuilder.measureApproxLufs(mixedPcm)

        val videoFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            ReelRenderer.OUTPUT_WIDTH,
            ReelRenderer.OUTPUT_HEIGHT
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            AudioTrackBuilder.TARGET_SAMPLE_RATE,
            1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false

        try {
            videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = videoCodec.createInputSurface()
            videoCodec.start()

            audioCodec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec.start()

            val videoInfo = MediaCodec.BufferInfo()
            val audioInfo = MediaCodec.BufferInfo()

            var audioSampleOffset = 0
            var audioInputDone = false
            var videoDone = false
            var audioDone = false
            var frameIndex = 0
            var encodedVideoFrameIndex = 0

            val previewBmp = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
            val previewCanvas = Canvas(previewBmp)

            while (!videoDone || !audioDone) {
                currentCoroutineContext().ensureActive()

                // Feed next video frame via Surface Canvas
                if (frameIndex < totalFrames) {
                    val t = frameIndex.toDouble() / FPS.toDouble()
                    val surfaceCanvas = inputSurface.lockCanvas(null)
                    try {
                        ReelRenderer.drawFrame(
                            canvas = surfaceCanvas,
                            widthPx = ReelRenderer.OUTPUT_WIDTH.toFloat(),
                            heightPx = ReelRenderer.OUTPUT_HEIGHT.toFloat(),
                            timeSec = t,
                            project = project,
                            typeface = typeface
                        )
                    } finally {
                        inputSurface.unlockCanvasAndPost(surfaceCanvas)
                    }

                    val sendPreview = (frameIndex % 15 == 0 || frameIndex == totalFrames - 1)
                    val snap = if (sendPreview) {
                        ReelRenderer.drawFrame(previewCanvas, 360f, 640f, t, project, typeface)
                        previewBmp.copy(Bitmap.Config.ARGB_8888, false)
                    } else null

                    onProgress(
                        (frameIndex + 1).toFloat() / totalFrames,
                        frameIndex + 1,
                        totalFrames,
                        snap
                    )
                    frameIndex++
                    if (frameIndex == totalFrames) {
                        videoCodec.signalEndOfInputStream()
                    }
                }

                // Drain video encoder
                if (!videoDone) {
                    val vIdx = videoCodec.dequeueOutputBuffer(videoInfo, 4_000)
                    when {
                        vIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoTrack = muxer.addTrack(videoCodec.outputFormat)
                            if (audioTrack >= 0 && !muxerStarted) {
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        vIdx >= 0 -> {
                            val outBuf = videoCodec.getOutputBuffer(vIdx)
                            if (outBuf != null && (videoInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && videoInfo.size > 0 && muxerStarted) {
                                outBuf.position(videoInfo.offset)
                                outBuf.limit(videoInfo.offset + videoInfo.size)
                                videoInfo.presentationTimeUs = (encodedVideoFrameIndex.toLong() * 1_000_000L) / FPS
                                encodedVideoFrameIndex++
                                muxer.writeSampleData(videoTrack, outBuf, videoInfo)
                            }
                            videoCodec.releaseOutputBuffer(vIdx, false)
                            if ((videoInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                videoDone = true
                            }
                        }
                    }
                }

                // Feed audio samples
                if (!audioInputDone) {
                    val aInIdx = audioCodec.dequeueInputBuffer(4_000)
                    if (aInIdx >= 0) {
                        val inBuf = audioCodec.getInputBuffer(aInIdx)!!
                        inBuf.clear()
                        if (audioSampleOffset >= mixedPcm.size) {
                            audioCodec.queueInputBuffer(aInIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            audioInputDone = true
                        } else {
                            val maxShorts = inBuf.remaining() / 2
                            val count = minOf(maxShorts, mixedPcm.size - audioSampleOffset, 1024)
                            val bb = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (k in 0 until count) {
                                bb.putShort(mixedPcm[audioSampleOffset + k])
                            }
                            inBuf.put(bb.array())
                            val ptsUs = (audioSampleOffset.toLong() * 1_000_000L) / AudioTrackBuilder.TARGET_SAMPLE_RATE
                            audioCodec.queueInputBuffer(aInIdx, 0, count * 2, ptsUs, 0)
                            audioSampleOffset += count
                        }
                    }
                }

                // Drain audio encoder
                if (!audioDone) {
                    val aOutIdx = audioCodec.dequeueOutputBuffer(audioInfo, 4_000)
                    when {
                        aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            audioTrack = muxer.addTrack(audioCodec.outputFormat)
                            if (videoTrack >= 0 && !muxerStarted) {
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        aOutIdx >= 0 -> {
                            val outBuf = audioCodec.getOutputBuffer(aOutIdx)
                            if (outBuf != null && (audioInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && audioInfo.size > 0 && muxerStarted) {
                                outBuf.position(audioInfo.offset)
                                outBuf.limit(audioInfo.offset + audioInfo.size)
                                muxer.writeSampleData(audioTrack, outBuf, audioInfo)
                            }
                            audioCodec.releaseOutputBuffer(aOutIdx, false)
                            if ((audioInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                audioDone = true
                            }
                        }
                    }
                }
            }

            previewBmp.recycle()
        } finally {
            runCatching { videoCodec.stop() }
            runCatching { videoCodec.release() }
            runCatching { audioCodec.stop() }
            runCatching { audioCodec.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        ExportResult(
            mp4File = outputMp4,
            frameCount = totalFrames,
            durationSec = durationSec,
            measuredLufs = measuredLufs
        )
    }
}
