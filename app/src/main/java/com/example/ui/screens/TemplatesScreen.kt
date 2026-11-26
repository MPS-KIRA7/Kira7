package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.IconLibrary
import com.example.model.SceneTemplate
import com.example.model.SceneType
import com.example.model.StoryboardData
import com.example.model.StoryboardScene
import com.example.model.TimedWord
import com.example.render.ReelRenderer
import com.example.ui.StudioUiState
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatesScreen(
    uiState: StudioUiState,
    iconLibrary: IconLibrary,
    onRefreshContactSheet: () -> Unit
) {
    var selectedTemplate by remember { mutableStateOf(SceneTemplate.BODY) }
    var animTimeSec by remember(selectedTemplate) { mutableDoubleStateOf(1.6) }
    var isAnimating by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTemplate, isAnimating) {
        if (!isAnimating) return@LaunchedEffect
        var last = 0L
        while (isAnimating) {
            withFrameNanos { now ->
                if (last != 0L) {
                    animTimeSec = (animTimeSec + (now - last) / 1_000_000_000.0) % 3.5
                }
                last = now
            }
        }
    }

    val sampleProject = remember(selectedTemplate, iconLibrary) {
        val sampleWords = listOf(
            TimedWord(0, "نموذج", 0.0, 0.7),
            TimedWord(1, selectedTemplate.arabicLabel, 0.7, 1.5),
            TimedWord(2, selectedTemplate.wireName.uppercase(), 1.5, 2.5),
            TimedWord(3, "العملي", 2.5, 3.5)
        )
        uiState.project.copy(
            alignedWords = sampleWords,
            storyboard = StoryboardData(
                scenes = listOf(
                    StoryboardScene(
                        type = SceneType.CONCEPT,
                        template = selectedTemplate,
                        atWordIndex = 0,
                        title = selectedTemplate.arabicLabel,
                        subtitle = "معاينة القالب: ${selectedTemplate.wireName}",
                        icons = listOf("brain", "atom", "wave"),
                        items = listOf("الخطوة ١", "الخطوة ٢", "الخطوة ٣"),
                        metricValue = "86%",
                        metricPercent = 0.86f
                    )
                )
            ),
            usedIcons = iconLibrary.icons,
            totalDurationSec = 3.5
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, SurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "فحص القوالب الـ 9 للرسم التقني",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    OutlinedButton(onClick = { isAnimating = !isAnimating }) {
                        Text(if (isAnimating) "إيقاف الحركة" else "تشغيل الحركة")
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SceneTemplate.entries.forEach { tpl ->
                        FilterChip(
                            selected = selectedTemplate == tpl,
                            onClick = { selectedTemplate = tpl },
                            modifier = Modifier.testTag("template_chip_${tpl.wireName}"),
                            label = { Text("${tpl.arabicLabel} (${tpl.wireName})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                                selectedLabelColor = AccentCyan
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .widthIn(max = 260.dp)
                        .fillMaxWidth()
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        ReelRenderer.drawFrame(
                            canvas = drawContext.canvas.nativeCanvas,
                            widthPx = size.width,
                            heightPx = size.height,
                            timeSec = animTimeSec,
                            project = sampleProject,
                            typeface = uiState.customTypeface
                        )
                    }
                }
            }
        }

        // Contact Sheet Card (all 9 templates in one grid)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, SurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("لوحة الفحص الشاملة (Contact Sheet 3×3)", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onRefreshContactSheet) {
                        Text("تحديث")
                    }
                }
                uiState.contactSheetBitmap?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "لوحة فحص جميع القوالب",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }

        // Local Icon Library & Licenses
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, SurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "مكتبة الأيقونات المحلية (${iconLibrary.icons.size} أيقونة — CC0 & MIT)",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    iconLibrary.icons.values.forEach { icon ->
                        Surface(
                            color = SurfaceElevated,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, SurfaceBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Canvas(modifier = Modifier.size(22.dp)) {
                                    val s = size.minDimension / 24f
                                    for (d in icon.paths) {
                                        val androidPath = IconLibrary.svgToAndroidPath(d, scale = s)
                                        drawContext.canvas.nativeCanvas.drawPath(
                                            androidPath,
                                            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                                color = 0xFF27B8C7.toInt()
                                                style = android.graphics.Paint.Style.STROKE
                                                strokeWidth = 2f * s
                                                strokeCap = android.graphics.Paint.Cap.ROUND
                                                strokeJoin = android.graphics.Paint.Join.ROUND
                                            }
                                        )
                                    }
                                }
                                Column {
                                    Text(icon.name, style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                                    Text(icon.license, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
