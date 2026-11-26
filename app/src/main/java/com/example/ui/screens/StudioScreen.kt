package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.automirrored.rounded.ViewQuilt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ApprovalMode
import com.example.model.PipelineStage
import com.example.model.StageState
import com.example.model.StageStatus
import com.example.model.VoiceInputMode
import com.example.ui.AppTab
import com.example.ui.StudioUiState
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentIndigo
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningOrange

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(
    uiState: StudioUiState,
    onTopicChange: (String) -> Unit,
    onDurationChange: (Int) -> Unit,
    onVoiceModeChange: (VoiceInputMode) -> Unit,
    onAudioPicked: (Uri) -> Unit,
    onStartPipeline: (PipelineStage) -> Unit,
    onApproveStage: (PipelineStage) -> Unit,
    onApprovalModeChange: (PipelineStage, ApprovalMode) -> Unit,
    onCancelPipeline: () -> Unit,
    onSaveScriptEdit: (String, String) -> Unit,
    onSaveStoryboardEdit: (String) -> Unit,
    onOpenPreviewTab: () -> Unit
) {
    val audioPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onAudioPicked(uri)
    }

    var showScriptEditor by remember { mutableStateOf(false) }
    var showStoryboardEditor by remember { mutableStateOf(false) }

    val quickTopics = listOf(
        "ليه السماء لونها أزرق؟",
        "ليه بنحس بالنعاس بعد الأكل؟",
        "إزاي المخ بيخزن الذكريات؟",
        "ليه القهوة بتفوقنا؟"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("studio_lazy_list"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            HeaderBanner(uiState = uiState)
        }

        // Topic & Duration Config Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 700.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                border = BorderStroke(1.dp, SurfaceBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "موضوع الريل العلمي",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )

                    OutlinedTextField(
                        value = uiState.project.topic,
                        onValueChange = onTopicChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("topic_input"),
                        placeholder = { Text("مثال: ليه السماء لونها أزرق؟", color = TextSecondary) },
                        singleLine = true
                    )

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quickTopics.forEach { sample ->
                            FilterChip(
                                selected = uiState.project.topic == sample,
                                onClick = { onTopicChange(sample) },
                                label = { Text(sample, style = MaterialTheme.typography.bodyMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                    selectedLabelColor = AccentCyan
                                )
                            )
                        }
                    }

                    Text(
                        text = "مدة الفيديو المستهدفة (بمعدل ~2.6 كلمة/ثانية)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(10, 20, 30, 45, 60).forEach { sec ->
                            FilterChip(
                                selected = uiState.project.durationSec == sec,
                                onClick = { onDurationChange(sec) },
                                modifier = Modifier.testTag("duration_chip_$sec"),
                                label = { Text("$sec ث") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentIndigo.copy(alpha = 0.25f),
                                    selectedLabelColor = TextPrimary
                                )
                            )
                        }
                    }

                    Text(
                        text = "مصدر التعليق الصوتي",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VoiceInputMode.entries.forEach { mode ->
                            val selected = uiState.project.voiceInputMode == mode
                            FilterChip(
                                selected = selected,
                                onClick = { onVoiceModeChange(mode) },
                                modifier = Modifier.weight(1f),
                                label = {
                                    Text(
                                        mode.arabicLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }

                    if (uiState.project.voiceInputMode == VoiceInputMode.UPLOADED_AUDIO) {
                        OutlinedButton(
                            onClick = { audioPicker.launch(arrayOf("audio/*")) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("upload_audio_button")
                        ) {
                            Icon(Icons.Rounded.AudioFile, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = uiState.project.uploadedAudioPath?.let {
                                    "ملف مرفوع جاهز (${java.io.File(it).name})"
                                } ?: "اختر ملف صوتي (.mp3 / .wav / .m4a)"
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (uiState.isPipelineRunning) {
                            Button(
                                onClick = onCancelPipeline,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("cancel_pipeline_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed)
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("إيقاف خط الإنتاج")
                            }
                        } else {
                            Button(
                                onClick = { onStartPipeline(PipelineStage.SCRIPT) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .testTag("start_pipeline_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentCyan,
                                    contentColor = BgCanvas
                                )
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("ابدأ إنتاج الريل (${uiState.project.durationSec}ث)", fontWeight = FontWeight.Bold)
                            }
                        }

                        if (uiState.project.exportedVideoPath != null || uiState.project.storyboard.scenes.isNotEmpty()) {
                            OutlinedButton(
                                onClick = onOpenPreviewTab,
                                modifier = Modifier
                                    .height(50.dp)
                                    .testTag("open_preview_button")
                            ) {
                                Icon(Icons.Rounded.Movie, contentDescription = "معاينة")
                                Spacer(Modifier.width(6.dp))
                                Text("المعاينة")
                            }
                        }
                    }
                }
            }
        }

        // Live preview thumbnail card during storyboard/render
        if (uiState.livePreviewBitmap != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 700.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Image(
                            bitmap = uiState.livePreviewBitmap.asImageBitmap(),
                            contentDescription = "إطار المعاينة المباشر",
                            modifier = Modifier
                                .size(width = 72.dp, height = 128.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("إطار الرندر المباشر (9:16)", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = uiState.project.scriptData.title.ifBlank { uiState.project.topic },
                                color = AccentCyan,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            uiState.project.exportedVideoPath?.let { path ->
                                Text(
                                    text = "تم تصدير MP4 جاهز: ${java.io.File(path).name}",
                                    color = SuccessGreen,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5 Pipeline Stages
        items(PipelineStage.entries, key = { it.name }) { stage ->
            val st = uiState.stages[stage] ?: StageState(stage)
            PipelineStageCard(
                stage = stage,
                state = st,
                uiState = uiState,
                isPipelineRunning = uiState.isPipelineRunning,
                onApprovalModeChange = { mode -> onApprovalModeChange(stage, mode) },
                onApprove = { onApproveStage(stage) },
                onRerun = { onStartPipeline(stage) },
                onEdit = {
                    when (stage) {
                        PipelineStage.SCRIPT -> showScriptEditor = true
                        PipelineStage.STORYBOARD -> showStoryboardEditor = true
                        else -> Unit
                    }
                }
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }

    if (showScriptEditor) {
        EditScriptDialog(
            initialTitle = uiState.project.scriptData.title,
            initialScript = uiState.project.scriptData.script,
            onDismiss = { showScriptEditor = false },
            onSave = { title, script ->
                onSaveScriptEdit(title, script)
                showScriptEditor = false
            }
        )
    }

    if (showStoryboardEditor) {
        EditStoryboardDialog(
            initialJson = uiState.project.rawStoryboardJson,
            onDismiss = { showStoryboardEditor = false },
            onSave = { json ->
                onSaveStoryboardEdit(json)
                showStoryboardEditor = false
            }
        )
    }
}

@Composable
private fun HeaderBanner(uiState: StudioUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 700.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
        border = BorderStroke(1.dp, SurfaceBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = AccentCyan.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AccentCyan)
                    ) {
                        Text(
                            text = "عارف ليه؟",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            color = AccentCyan,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Text(
                        text = "Reel Studio v1",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                }
                Text(
                    text = "خط إنتاج ريلز علمية (1080×1920 @ 30fps)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (uiState.settings.useLocalMockApis) SuccessGreen.copy(alpha = 0.15f) else AccentIndigo.copy(alpha = 0.2f),
                border = BorderStroke(
                    1.dp,
                    if (uiState.settings.useLocalMockApis) SuccessGreen else AccentIndigo
                )
            ) {
                Text(
                    text = if (uiState.settings.useLocalMockApis) "محلي :${uiState.mockServerPort ?: 4399}" else "API مباشر",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = if (uiState.settings.useLocalMockApis) SuccessGreen else TextPrimary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun PipelineStageCard(
    stage: PipelineStage,
    state: StageState,
    uiState: StudioUiState,
    isPipelineRunning: Boolean,
    onApprovalModeChange: (ApprovalMode) -> Unit,
    onApprove: () -> Unit,
    onRerun: () -> Unit,
    onEdit: () -> Unit
) {
    var expandedLogs by remember { mutableStateOf(false) }

    val stageIcon: ImageVector = when (stage) {
        PipelineStage.SCRIPT -> Icons.Rounded.AutoAwesome
        PipelineStage.VOICE -> Icons.Rounded.GraphicEq
        PipelineStage.TIMING -> Icons.Rounded.Schedule
        PipelineStage.STORYBOARD -> Icons.AutoMirrored.Rounded.ViewQuilt
        PipelineStage.RENDER -> Icons.Rounded.Movie
    }

    val statusColor = when (state.status) {
        StageStatus.IDLE -> TextSecondary
        StageStatus.RUNNING -> AccentCyan
        StageStatus.REVIEW -> WarningOrange
        StageStatus.DONE -> SuccessGreen
        StageStatus.ERROR -> ErrorRed
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 700.dp)
            .testTag("stage_card_${stage.name.lowercase()}"),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = BorderStroke(
            width = if (state.status == StageStatus.RUNNING || state.status == StageStatus.REVIEW) 1.5.dp else 1.dp,
            color = if (state.status == StageStatus.IDLE) SurfaceBorder else statusColor.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(stageIcon, contentDescription = stage.arabicTitle, tint = statusColor, modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text(
                            text = "${stage.index + 1}. ${stage.arabicTitle}",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = state.status.arabicLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor
                        )
                    }
                }

                if (stage != PipelineStage.RENDER) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (state.approvalMode == ApprovalMode.MANUAL) "موافقة" else "تلقائي",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Switch(
                            checked = state.approvalMode == ApprovalMode.MANUAL,
                            onCheckedChange = { manual ->
                                onApprovalModeChange(if (manual) ApprovalMode.MANUAL else ApprovalMode.AUTO)
                            },
                            modifier = Modifier.testTag("approval_switch_${stage.name.lowercase()}")
                        )
                    }
                }
            }

            if (state.status == StageStatus.RUNNING || state.progress > 0f && state.status != StageStatus.DONE) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentCyan,
                    trackColor = SurfaceElevated
                )
            }

            // Stage-specific summary output
            when (stage) {
                PipelineStage.SCRIPT -> {
                    val previewText = uiState.project.scriptData.script.ifBlank { uiState.project.streamedLlmText }
                    if (previewText.isNotBlank()) {
                        Surface(
                            color = BgCanvas,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SurfaceBorder)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (uiState.project.scriptData.title.isNotBlank()) {
                                    Text(
                                        text = uiState.project.scriptData.title,
                                        color = AccentCyan,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                Text(
                                    text = previewText,
                                    color = TextPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                PipelineStage.TIMING -> {
                    val words = uiState.project.alignedWords
                    if (words.isNotEmpty()) {
                        val weak = words.count { it.isWeak }
                        Text(
                            text = "تمت محاذاة ${words.size} كلمة (كلمات ضعيفة تحتاج مراجعة: $weak)",
                            color = if (weak > 0) WarningOrange else SuccessGreen,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                PipelineStage.STORYBOARD -> {
                    val scenes = uiState.project.storyboard.scenes
                    if (scenes.isNotEmpty()) {
                        Text(
                            text = "${scenes.size} مشاهد | الأيقونات المستخدمة: ${uiState.project.usedIcons.keys.joinToString(", ")}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.project.storyboard.reviewNotes.isNotBlank()) {
                            Text(
                                text = uiState.project.storyboard.reviewNotes,
                                color = SuccessGreen,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                else -> Unit
            }

            if (state.errorMessage != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = ErrorRed, modifier = Modifier.size(18.dp))
                    Text(state.errorMessage, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Stage Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.status == StageStatus.REVIEW) {
                    Button(
                        onClick = onApprove,
                        modifier = Modifier.testTag("approve_${stage.name.lowercase()}"),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = BgCanvas)
                    ) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("موافقة ومتابعة")
                    }
                }

                if (stage == PipelineStage.SCRIPT || stage == PipelineStage.STORYBOARD) {
                    OutlinedButton(
                        onClick = onEdit,
                        enabled = !isPipelineRunning,
                        modifier = Modifier.testTag("edit_${stage.name.lowercase()}")
                    ) {
                        Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("تعديل")
                    }
                }

                OutlinedButton(
                    onClick = onRerun,
                    enabled = !isPipelineRunning,
                    modifier = Modifier.testTag("rerun_${stage.name.lowercase()}")
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("تشغيل")
                }

                Spacer(Modifier.weight(1f))

                if (state.logs.isNotEmpty()) {
                    Text(
                        text = if (expandedLogs) "إخفاء السجل" else "السجل (${state.logs.size})",
                        color = AccentCyan,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clickable { expandedLogs = !expandedLogs }
                            .padding(horizontal = 6.dp, vertical = 8.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expandedLogs && state.logs.isNotEmpty()) {
                Surface(
                    color = BgCanvas,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, SurfaceBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.logs.forEach { log ->
                            Text(
                                text = log,
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditScriptDialog(
    initialTitle: String,
    initialScript: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var body by remember { mutableStateOf(initialScript) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = { Text("تعديل السكربت", color = TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("العنوان") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("نص السكربت") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(title, body) }) { Text("حفظ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun EditStoryboardDialog(
    initialJson: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var jsonText by remember { mutableStateOf(initialJson) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = { Text("تعديل الستوري بورد (JSON)", color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = jsonText,
                onValueChange = { jsonText = it },
                minLines = 8,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onSave(jsonText) }) { Text("حفظ والتحقق") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
