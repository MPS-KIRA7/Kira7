package com.example.ui.screens

import android.media.MediaPlayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.render.ReelRenderer
import com.example.ui.StudioUiState
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningOrange
import java.io.File
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreviewScreen(
    uiState: StudioUiState,
    onExportMp4: () -> Unit,
    onShareMp4: () -> Unit
) {
    val project = uiState.project
    val totalDuration = project.totalDurationSec.coerceAtLeast(1.0)
    var currentTimeSec by remember(project.id) { mutableDoubleStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }

    val mediaPlayer = remember(project.voiceAudioPath) {
        val path = project.voiceAudioPath
        if (!path.isNullOrBlank() && File(path).exists()) {
            runCatching {
                MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                }
            }.getOrNull()
        } else null
    }

    DisposableEffect(mediaPlayer) {
        onDispose {
            runCatching { mediaPlayer?.release() }
        }
    }

    LaunchedEffect(isPlaying, totalDuration, mediaPlayer) {
        if (!isPlaying) {
            runCatching { if (mediaPlayer?.isPlaying == true) mediaPlayer.pause() }
            return@LaunchedEffect
        }
        runCatching {
            mediaPlayer?.seekTo((currentTimeSec * 1000).toInt())
            mediaPlayer?.start()
        }
        var lastNanos = 0L
        while (isPlaying) {
            withFrameNanos { frameNanos ->
                if (lastNanos != 0L) {
                    val dt = (frameNanos - lastNanos) / 1_000_000_000.0
                    currentTimeSec = (currentTimeSec + dt)
                    if (currentTimeSec >= totalDuration) {
                        currentTimeSec = 0.0
                        isPlaying = false
                        runCatching { mediaPlayer?.pause(); mediaPlayer?.seekTo(0) }
                    }
                }
                lastNanos = frameNanos
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 9:16 Deterministic Canvas Preview
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(22.dp))
                .background(BgCanvas)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                ReelRenderer.drawFrame(
                    canvas = drawContext.canvas.nativeCanvas,
                    widthPx = size.width,
                    heightPx = size.height,
                    timeSec = currentTimeSec,
                    project = project,
                    typeface = uiState.customTypeface
                )
            }
        }

        // Playback Controls + Scrubber
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 680.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, SurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { isPlaying = !isPlaying },
                            modifier = Modifier.testTag("preview_play_pause")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                                tint = AccentCyan
                            )
                        }
                        IconButton(
                            onClick = {
                                isPlaying = false
                                currentTimeSec = 0.0
                                runCatching { mediaPlayer?.seekTo(0) }
                            },
                            modifier = Modifier.testTag("preview_restart")
                        ) {
                            Icon(Icons.Rounded.Replay, contentDescription = "إعادة", tint = TextPrimary)
                        }
                    }

                    Text(
                        text = String.format(Locale.US, "%.2fs / %.2fs", currentTimeSec, totalDuration),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Slider(
                    value = currentTimeSec.toFloat().coerceIn(0f, totalDuration.toFloat()),
                    onValueChange = { newSec ->
                        currentTimeSec = newSec.toDouble()
                        runCatching { mediaPlayer?.seekTo((newSec * 1000).toInt()) }
                    },
                    valueRange = 0f..totalDuration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentCyan,
                        activeTrackColor = AccentCyan,
                        inactiveTrackColor = SurfaceElevated
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onExportMp4,
                        enabled = !uiState.isPipelineRunning && project.alignedWords.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_mp4_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgCanvas)
                    ) {
                        Icon(Icons.Rounded.MovieCreation, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("تصدير MP4 (1080×1920)")
                    }

                    if (project.exportedVideoPath != null) {
                        OutlinedButton(
                            onClick = onShareMp4,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("share_mp4_button")
                        ) {
                            Icon(Icons.Rounded.Share, contentDescription = "مشاركة")
                            Spacer(Modifier.width(6.dp))
                            Text("مشاركة")
                        }
                    }
                }
            }
        }

        // Synced Word Timeline Chips (showing weak words flagged by LCS alignment)
        if (project.alignedWords.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                border = BorderStroke(1.dp, SurfaceBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "خريطة الكلمات المزامنة (اضغط للانتقال)",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        project.alignedWords.forEach { word ->
                            val isActive = currentTimeSec in word.startSec..word.endSec
                            val chipColor = when {
                                isActive -> AccentCyan
                                word.isWeak -> WarningOrange
                                else -> SurfaceElevated
                            }
                            Surface(
                                color = chipColor.copy(alpha = if (isActive) 0.28f else 0.8f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    1.dp,
                                    when {
                                        isActive -> AccentCyan
                                        word.isWeak -> WarningOrange
                                        else -> SurfaceBorder
                                    }
                                ),
                                modifier = Modifier.clickable {
                                    currentTimeSec = word.startSec
                                    runCatching { mediaPlayer?.seekTo((word.startSec * 1000).toInt()) }
                                }
                            ) {
                                Text(
                                    text = word.text,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                    color = if (isActive) AccentCyan else TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    Text(
                        text = "الكلمات باللون البرتقالي ضعيفة الثقة في محاذاة التوقيت.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        if (project.exportedVideoPath != null) {
            Surface(
                color = SuccessGreen.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SuccessGreen),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
            ) {
                Text(
                    text = "تم حفظ ملف الفيديو النهائي: ${project.exportedVideoPath}",
                    modifier = Modifier.padding(12.dp),
                    color = SuccessGreen,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
