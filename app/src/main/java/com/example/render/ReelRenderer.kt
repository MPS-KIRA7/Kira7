package com.example.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import com.example.core.IconLibrary
import com.example.model.AccentTone
import com.example.model.IconDefinition
import com.example.model.ReelProject
import com.example.model.SceneTemplate
import com.example.model.StoryboardScene
import com.example.model.TimedWord
import com.example.model.WordAccent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Deterministic technical illustration renderer.
 * Design canvas: 720x1280, scaled x1.5 to 1080x1920 for final MP4 output.
 * Every visual property depends strictly on timeSec (t).
 */
object ReelRenderer {

    const val DESIGN_WIDTH = 720f
    const val DESIGN_HEIGHT = 1280f
    const val OUTPUT_WIDTH = 1080
    const val OUTPUT_HEIGHT = 1920
    const val SCALE_FACTOR = 1.5f

    private const val COLOR_BG = 0xFF0C1014.toInt()
    private const val COLOR_CARD = 0xFF161C22.toInt()
    private const val COLOR_ELEVATED = 0xFF202830.toInt()
    private const val COLOR_TEXT = 0xFFF4F3EE.toInt()
    private const val COLOR_SECONDARY = 0xFF9AA5AE.toInt()
    private const val COLOR_CYAN = 0xFF27B8C7.toInt()
    private const val COLOR_INDIGO = 0xFF5978FF.toInt()
    private const val COLOR_SUCCESS = 0xFF36C98F.toInt()
    private const val COLOR_WARNING = 0xFFF28A30.toInt()

    fun drawFrame(
        canvas: Canvas,
        widthPx: Float,
        heightPx: Float,
        timeSec: Double,
        project: ReelProject,
        typeface: Typeface? = null
    ) {
        val scaleX = widthPx / DESIGN_WIDTH
        val scaleY = heightPx / DESIGN_HEIGHT
        canvas.save()
        canvas.scale(scaleX, scaleY)

        drawBackgroundGrid(canvas, timeSec)

        val words = project.alignedWords
        val scenes = project.storyboard.scenes
        val sceneTimes = resolveSceneStartTimes(scenes, words, project.totalDurationSec)

        val activeSceneIdx = findActiveSceneIndex(timeSec, sceneTimes)
        val activeScene = scenes.getOrNull(activeSceneIdx)
        val sceneStart = sceneTimes.getOrElse(activeSceneIdx) { 0.0 }
        val nextSceneStart = sceneTimes.getOrElse(activeSceneIdx + 1) { project.totalDurationSec }
        val sceneDuration = (nextSceneStart - sceneStart).coerceAtLeast(0.5)
        val localProgress = ((timeSec - sceneStart) / sceneDuration).toFloat().coerceIn(0f, 1f)
        val enterT = ((timeSec - sceneStart) / 0.55).toFloat().coerceIn(0f, 1f)

        // Top series tag + project title
        drawTopHeader(canvas, project, activeScene, typeface)

        // Central rounded "film" card (720 design coords: x=44..676, y=170..830)
        val cardRect = RectF(44f, 170f, 676f, 830f)
        drawFilmCard(
            canvas = canvas,
            cardRect = cardRect,
            scene = activeScene,
            usedIcons = project.usedIcons,
            enterT = enterT,
            localProgress = localProgress,
            timeSec = timeSec,
            typeface = typeface
        )

        // Synced word-chunk captions underneath the film card
        drawCaptions(
            canvas = canvas,
            timeSec = timeSec,
            words = words,
            accents = project.storyboard.accents,
            typeface = typeface
        )

        // Bottom timeline telemetry strip
        drawBottomTelemetry(canvas, timeSec, project.totalDurationSec, activeSceneIdx, scenes.size, typeface)

        canvas.restore()
    }

    fun renderContactSheet(
        project: ReelProject,
        iconLibrary: IconLibrary,
        typeface: Typeface? = null
    ): Bitmap {
        val templates = SceneTemplate.entries
        val cellW = 360
        val cellH = 640
        val cols = 3
        val rows = 3
        val sheet = Bitmap.createBitmap(cellW * cols, cellH * rows, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(COLOR_BG)

        templates.forEachIndexed { idx, tpl ->
            val col = idx % cols
            val row = idx / cols
            val sampleScene = StoryboardScene(
                type = com.example.model.SceneType.CONCEPT,
                template = tpl,
                atWordIndex = 0,
                title = tpl.arabicLabel,
                subtitle = tpl.wireName.uppercase(),
                icons = listOf("brain", "atom", "wave"),
                items = listOf("خطوة أ", "خطوة ب", "خطوة ج"),
                metricValue = "84%",
                metricPercent = 0.84f
            )
            val tempProj = project.copy(
                storyboard = project.storyboard.copy(scenes = listOf(sampleScene)),
                usedIcons = iconLibrary.icons,
                totalDurationSec = 4.0
            )
            canvas.save()
            canvas.translate(col * cellW.toFloat(), row * cellH.toFloat())
            canvas.clipRect(0f, 0f, cellW.toFloat(), cellH.toFloat())
            drawFrame(canvas, cellW.toFloat(), cellH.toFloat(), 1.6, tempProj, typeface)
            canvas.restore()
        }
        return sheet
    }

    fun resolveSceneStartTimes(
        scenes: List<StoryboardScene>,
        words: List<TimedWord>,
        totalDurationSec: Double
    ): List<Double> {
        if (scenes.isEmpty()) return listOf(0.0)
        return scenes.mapIndexed { idx, scene ->
            if (idx == 0) 0.0
            else words.getOrNull(scene.atWordIndex)?.startSec
                ?: ((idx.toDouble() / scenes.size) * totalDurationSec)
        }
    }

    private fun findActiveSceneIndex(timeSec: Double, sceneTimes: List<Double>): Int {
        var idx = 0
        for (i in sceneTimes.indices) {
            if (timeSec >= sceneTimes[i]) idx = i else break
        }
        return idx
    }

    private fun drawBackgroundGrid(canvas: Canvas, timeSec: Double) {
        canvas.drawColor(COLOR_BG)

        // Subtle ambient radial glow
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                DESIGN_WIDTH * 0.5f,
                480f + (sin(timeSec * 0.8) * 14f).toFloat(),
                460f,
                intArrayOf(withAlpha(COLOR_CYAN, 26), withAlpha(COLOR_INDIGO, 12), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, DESIGN_WIDTH, DESIGN_HEIGHT, glowPaint)

        // Faint technical grid (40px pitch)
        val gridPaint = Paint().apply {
            color = withAlpha(COLOR_SECONDARY, 20)
            strokeWidth = 1f
        }
        var x = 40f
        while (x < DESIGN_WIDTH) {
            canvas.drawLine(x, 0f, x, DESIGN_HEIGHT, gridPaint)
            x += 40f
        }
        var y = 40f
        while (y < DESIGN_HEIGHT) {
            canvas.drawLine(0f, y, DESIGN_WIDTH, y, gridPaint)
            y += 40f
        }
    }

    private fun drawTopHeader(
        canvas: Canvas,
        project: ReelProject,
        activeScene: StoryboardScene?,
        typeface: Typeface?
    ) {
        // Series tag pill: "عارف ليه؟"
        val pillRect = RectF(44f, 54f, 204f, 102f)
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ELEVATED
        }
        val pillStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CYAN
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRoundRect(pillRect, 24f, 24f, pillPaint)
        canvas.drawRoundRect(pillRect, 24f, 24f, pillStroke)

        val tagPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CYAN
            textSize = 24f
            this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("عارف ليه؟", pillRect.centerX(), pillRect.centerY() + 8f, tagPaint)

        // Scene badge on opposite side
        val badgeText = activeScene?.template?.wireName?.uppercase() ?: "INTRO"
        val badgeRect = RectF(540f, 56f, 676f, 100f)
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_INDIGO, 35)
        }
        val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_INDIGO, 150)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        canvas.drawRoundRect(badgeRect, 14f, 14f, badgePaint)
        canvas.drawRoundRect(badgeRect, 14f, 14f, badgeStroke)

        val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textSize = 18f
            this.typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(badgeText, badgeRect.centerX(), badgeRect.centerY() + 6f, monoPaint)

        // Project title
        val titleText = project.scriptData.title.ifBlank { project.topic.ifBlank { "استوديو الريلز العلمية" } }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textSize = 26f
            this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText(titleText.take(40), 676f, 145f, titlePaint)
    }

    private fun drawFilmCard(
        canvas: Canvas,
        cardRect: RectF,
        scene: StoryboardScene?,
        usedIcons: Map<String, IconDefinition>,
        enterT: Float,
        localProgress: Float,
        timeSec: Double,
        typeface: Typeface?
    ) {
        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CARD
        }
        val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ELEVATED
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(cardRect, 28f, 28f, cardBgPaint)
        canvas.drawRoundRect(cardRect, 28f, 28f, cardBorderPaint)

        // Corner registration ticks
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_CYAN, 170)
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
        }
        val m = 18f
        val l = 16f
        canvas.drawLine(cardRect.left + m, cardRect.top + m, cardRect.left + m + l, cardRect.top + m, tickPaint)
        canvas.drawLine(cardRect.left + m, cardRect.top + m, cardRect.left + m, cardRect.top + m + l, tickPaint)
        canvas.drawLine(cardRect.right - m, cardRect.top + m, cardRect.right - m - l, cardRect.top + m, tickPaint)
        canvas.drawLine(cardRect.right - m, cardRect.top + m, cardRect.right - m, cardRect.top + m + l, tickPaint)

        if (scene == null) return

        // Clip and apply smooth camera push-in inside the film card
        canvas.save()
        val clipPath = Path().apply { addRoundRect(cardRect, 28f, 28f, Path.Direction.CW) }
        canvas.clipPath(clipPath)

        val pushScale = 1f + 0.045f * easeOutCubic(localProgress)
        canvas.scale(pushScale, pushScale, cardRect.centerX(), cardRect.centerY())

        // Scene header inside card
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textSize = 30f
            this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_SECONDARY
            textSize = 20f
            this.typeface = typeface ?: Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(scene.title.take(30), cardRect.centerX(), cardRect.top + 68f, headerPaint)
        if (scene.subtitle.isNotBlank()) {
            canvas.drawText(scene.subtitle.take(44), cardRect.centerX(), cardRect.top + 102f, subPaint)
        }

        // Illustration area
        val artRect = RectF(cardRect.left + 44f, cardRect.top + 130f, cardRect.right - 44f, cardRect.bottom - 40f)
        val drawOn = easeOutCubic((enterT * 1.15f).coerceIn(0f, 1f))
        val popScale = easeOutBack((enterT * 1.1f).coerceIn(0f, 1f))

        when (scene.template) {
            SceneTemplate.BODY -> drawBodyTemplate(canvas, artRect, scene, usedIcons, drawOn, popScale, timeSec, typeface)
            SceneTemplate.ZOOM -> drawZoomTemplate(canvas, artRect, scene, usedIcons, drawOn, popScale, timeSec, typeface)
            SceneTemplate.FLOW -> drawFlowTemplate(canvas, artRect, scene, usedIcons, drawOn, popScale, typeface)
            SceneTemplate.CYCLE -> drawCycleTemplate(canvas, artRect, scene, usedIcons, drawOn, popScale, timeSec, typeface)
            SceneTemplate.TIMELINE -> drawTimelineTemplate(canvas, artRect, scene, usedIcons, drawOn, popScale, typeface)
            SceneTemplate.CHART -> drawChartTemplate(canvas, artRect, scene, drawOn, typeface)
            SceneTemplate.LAYERS -> drawLayersTemplate(canvas, artRect, scene, usedIcons, drawOn, popScale, typeface)
            SceneTemplate.CARDS -> drawCardsTemplate(canvas, artRect, scene, usedIcons, drawOn, popScale, typeface)
            SceneTemplate.METER -> drawMeterTemplate(canvas, artRect, scene, drawOn, popScale, typeface)
        }

        canvas.restore()
    }

    private fun drawBodyTemplate(
        canvas: Canvas,
        r: RectF,
        scene: StoryboardScene,
        usedIcons: Map<String, IconDefinition>,
        drawOn: Float,
        pop: Float,
        timeSec: Double,
        typeface: Typeface?
    ) {
        val cx = r.centerX()
        val cy = r.centerY()
        // Faint human torso & head outline (always visible faintly so no empty frame)
        val bodyPath = Path().apply {
            addCircle(cx, r.top + 78f, 44f, Path.Direction.CW)
            moveTo(cx - 95f, r.bottom - 20f)
            cubicTo(cx - 98f, cy - 30f, cx - 65f, r.top + 135f, cx - 22f, r.top + 135f)
            lineTo(cx + 22f, r.top + 135f)
            cubicTo(cx + 65f, r.top + 135f, cx + 98f, cy - 30f, cx + 95f, r.bottom - 20f)
        }
        drawProgressivePath(canvas, bodyPath, withAlpha(COLOR_SECONDARY, 60), 4f, 1f)
        drawProgressivePath(canvas, bodyPath, COLOR_SECONDARY, 3.5f, drawOn)

        // Glowing target organ pulse
        val pulse = 1f + 0.08f * sin(timeSec * 5.0).toFloat()
        val organCenterY = cy + 28f
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_CYAN, 70)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(cx, organCenterY, 72f * pulse, ringPaint)

        val icon = resolveIcon(usedIcons, scene.icons.firstOrNull() ?: "heart")
        drawVectorIcon(canvas, icon, cx, organCenterY, 108f * pop, COLOR_CYAN)

        val label = scene.items.firstOrNull() ?: "استجابة فسيولوجية"
        drawChipLabel(canvas, label, cx, r.bottom - 34f, COLOR_ELEVATED, COLOR_CYAN, typeface)
    }

    private fun drawZoomTemplate(
        canvas: Canvas,
        r: RectF,
        scene: StoryboardScene,
        usedIcons: Map<String, IconDefinition>,
        drawOn: Float,
        pop: Float,
        timeSec: Double,
        typeface: Typeface?
    ) {
        val leftX = r.left + 125f
        val rightX = r.right - 135f
        val cy = r.centerY() - 10f

        // Macro circle
        val macroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(COLOR_INDIGO, 50)
            style = Paint.Style.FILL
        }
        val macroStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INDIGO
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(leftX, cy, 74f, macroPaint)
        canvas.drawCircle(leftX, cy, 74f, macroStroke)
        drawVectorIcon(canvas, resolveIcon(usedIcons, scene.icons.firstOrNull() ?: "sun"), leftX, cy, 72f * pop, COLOR_TEXT)

        // Projection cone lines (draw-on)
        val rays = Path().apply {
            moveTo(leftX + 54f, cy - 48f)
            lineTo(rightX - 88f, cy - 105f)
            moveTo(leftX + 54f, cy + 48f)
            lineTo(rightX - 88f, cy + 105f)
        }
        drawProgressivePath(canvas, rays, withAlpha(COLOR_CYAN, 180), 2.5f, drawOn, dashed = true)

        // Micro zoom lens
        val microRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ELEVATED
            style = Paint.Style.FILL
        }
        val microStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CYAN
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val breathe = 1f + 0.03f * sin(timeSec * 3.5).toFloat()
        canvas.drawCircle(rightX, cy, 112f * breathe, microRing)
        canvas.drawCircle(rightX, cy, 112f * breathe, microStroke)
        val microIcon = resolveIcon(usedIcons, scene.icons.getOrElse(1) { "atom" })
        drawVectorIcon(canvas, microIcon, rightX, cy, 110f * pop, COLOR_CYAN)

        drawChipLabel(canvas, scene.items.getOrElse(0) { "MACRO" }, leftX, cy + 122f, COLOR_ELEVATED, COLOR_INDIGO, typeface)
        drawChipLabel(canvas, scene.items.getOrElse(1) { "MICRO" }, rightX, cy + 150f, COLOR_ELEVATED, COLOR_CYAN, typeface)
    }

    private fun drawFlowTemplate(
        canvas: Canvas,
        r: RectF,
        scene: StoryboardScene,
        usedIcons: Map<String, IconDefinition>,
        drawOn: Float,
        pop: Float,
        typeface: Typeface?
    ) {
        val labels = (scene.items + listOf("المدخل", "المعالجة", "النتيجة")).take(3)
        val icons = listOf(
            scene.icons.getOrElse(0) { "sun" },
            scene.icons.getOrElse(1) { "gear" },
            scene.icons.getOrElse(2) { "check" }
        )
        val xs = listOf(r.left + 90f, r.centerX(), r.right - 90f)
        val cy = r.centerY() - 10f

        // Connecting arrows with draw-on
        val arrows = Path().apply {
            moveTo(xs[0] + 62f, cy)
            lineTo(xs[1] - 62f, cy)
            moveTo(xs[1] + 62f, cy)
            lineTo(xs[2] - 62f, cy)
        }
        drawProgressivePath(canvas, arrows, withAlpha(COLOR_SECONDARY, 55), 4f, 1f)
        drawProgressivePath(canvas, arrows, COLOR_CYAN, 4f, drawOn)

        val colors = listOf(COLOR_INDIGO, COLOR_CYAN, COLOR_SUCCESS)
        for (i in 0..2) {
            val box = RectF(xs[i] - 58f, cy - 58f, xs[i] + 58f, cy + 58f)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ELEVATED }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors[i]
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(box, 20f, 20f, fill)
            canvas.drawRoundRect(box, 20f, 20f, stroke)
            drawVectorIcon(canvas, resolveIcon(usedIcons, icons[i]), xs[i], cy, 58f * pop, colors[i])
            drawChipLabel(canvas, labels[i], xs[i], cy + 96f, COLOR_ELEVATED, colors[i], typeface)
        }
    }

    private fun drawCycleTemplate(
        canvas: Canvas,
        r: RectF,
        scene: StoryboardScene,
        usedIcons: Map<String, IconDefinition>,
        drawOn: Float,
        pop: Float,
        timeSec: Double,
        typeface: Typeface?
    ) {
        val cx = r.centerX()
        val cy = r.centerY()
        val radius = 148f
        val ringPath = Path().apply { addCircle(cx, cy, radius, Path.Direction.CW) }
        drawProgressivePath(canvas, ringPath, withAlpha(COLOR_SECONDARY, 55), 4f, 1f)
        drawProgressivePath(canvas, ringPath, COLOR_CYAN, 4f, drawOn)

        // Orbiting dot
        val angle = (timeSec * 1.4) % (2 * PI)
        val dotX = cx + (radius * cos(angle)).toFloat()
        val dotY = cy + (radius * sin(angle)).toFloat()
        canvas.drawCircle(dotX, dotY, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_WARNING })

        val labels = (scene.items + listOf("محفز", "تفاعل", "توازن")).take(3)
        val nodeAngles = listOf(-90.0, 30.0, 150.0)
        val nodeColors = listOf(COLOR_CYAN, COLOR_INDIGO, COLOR_SUCCESS)

        nodeAngles.forEachIndexed { idx, deg ->
            val rad = Math.toRadians(deg)
            val nx = cx + (radius * cos(rad)).toFloat()
            val ny = cy + (radius * sin(rad)).toFloat()
            canvas.drawCircle(nx, ny, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ELEVATED })
            canvas.drawCircle(
                nx,
                ny,
                44f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = nodeColors[idx]
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
            )
            val iconName = scene.icons.getOrElse(idx) { listOf("lightning", "gear", "check")[idx] }
            drawVectorIcon(canvas, resolveIcon(usedIcons, iconName), nx, ny, 42f * pop, nodeColors[idx])
            drawChipLabel(canvas, labels[idx], nx, ny + 68f, COLOR_BG, nodeColors[idx], typeface)
        }
    }

    private fun drawTimelineTemplate(
        canvas: Canvas,
        r: RectF,
        scene: StoryboardScene,
        usedIcons: Map<String, IconDefinition>,
        drawOn: Float,
        pop: Float,
        typeface: Typeface?
    ) {
        val cy = r.centerY() - 16f
        val line = Path().apply {
            moveTo(r.left + 44f, cy)
            lineTo(r.right - 44f, cy)
        }
        drawProgressivePath(canvas, line, withAlpha(COLOR_SECONDARY, 60), 4f, 1f)
        drawProgressivePath(canvas, line, COLOR_CYAN, 4f, drawOn)

        val steps = (scene.items + listOf("البداية", "التطور", "النتيجة")).take(3)
        val xs = listOf(r.left + 90f, r.centerX(), r.right - 90f)
        val colors = listOf(COLOR_INDIGO, COLOR_CYAN, COLOR_SUCCESS)

        steps.forEachIndexed { idx, label ->
            val x = xs[idx]
            canvas.drawCircle(x, cy, 16f * pop, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors[idx] })
            val cardY = if (idx % 2 == 0) cy - 95f else cy + 95f
            drawChipLabel(canvas, label, x, cardY, COLOR_ELEVATED, colors[idx], typeface)
            val iconName = scene.icons.getOrElse(idx) { "clock" }
            drawVectorIcon(canvas, resolveIcon(usedIcons, iconName), x, if (idx % 2 == 0) cy - 146f else cy + 146f, 38f * pop, colors[idx])
        }
    }

    private fun drawChartTemplate(
        canvas: Canvas,
        r: RectF,
        scene: StoryboardScene,
        drawOn: Float,
        typeface: Typeface?
    ) {
        val baseY = r.bottom - 60f
        val topY = r.top + 40f
        val axis = Path().apply {
            moveTo(r.left + 40f, topY)
            lineTo(r.left + 40f, baseY)
            lineTo(r.right - 40f, baseY)
        }
        drawProgressivePath(canvas, axis, COLOR_SECONDARY, 3f, 1f)

        val labels = (scene.items + listOf("أ", "ب", "ج")).take(3)
        val heights = listOf(0.32f, 0.58f, 0.92f)
        val colors = listOf(COLOR_SECONDARY, COLOR_INDIGO, COLOR_CYAN)
        val barWidth = 92f
        val startX = r.left + 96f
        val gap = 148f

        heights.forEachIndexed { idx, h ->
            val left = startX + idx * gap
            val fullHeight = (baseY - topY - 24f) * h
            val animHeight = fullHeight * drawOn
            val barRect = RectF(left, baseY - animHeight, left + barWidth, baseY)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    left,
                    barRect.top,
                    left,
                    baseY,
                    colors[idx],
                    withAlpha(colors[idx], 90),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(barRect, 14f, 14f, paint)
            drawChipLabel(canvas, labels[idx], left + barWidth / 2f, baseY + 32f, COLOR_ELEVATED, colors[idx], typeface)
        }
    }

    private fun drawLayersTemplate(
        canvas: Canvas,
        r: RectF,
        scene: StoryboardScene,
        usedIcons: Map<String, IconDefinition>,
        drawOn: Float,
        pop: Float,
        typeface: Typeface?
    ) {
        val labels = (scene.items + listOf("الطبقة الأولى", "الطبقة الثانية", "الطبقة الثالثة")).take(3)
        val colors = listOf(COLOR_INDIGO, COLOR_CYAN, COLOR_SUCCESS)
        val cx = r.centerX()
        val baseTop = r.top + 40f
        val stepY = 110f

        for (i in 0..2) {
            val slide = (1f - drawOn) * (30f * (i + 1))
            val top = baseTop + i * stepY + slide
            val rect = RectF(cx - 215f, top, cx + 215f, top + 84f)
            canvas.drawRoundRect(rect, 18f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ELEVATED })
            canvas.drawRoundRect(
                rect,
                18f,
                18f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = colors[i]
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f
                }
            )
            val iconName = scene.icons.getOrElse(i % scene.icons.size.coerceAtLeast(1)) { "atom" }
            drawVectorIcon(canvas, resolveIcon(usedIcons, iconName), rect.left + 44f, rect.centerY(), 42f * pop, colors[i])
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_TEXT
                textSize = 24f
                this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText(labels[i].take(22), rect.right - 26f, rect.centerY() + 8f, tp)
        }
    }

    private fun drawCardsTemplate(
        canvas: Canvas,
        r: RectF,
        scene: StoryboardScene,
        usedIcons: Map<String, IconDefinition>,
        drawOn: Float,
        pop: Float,
        typeface: Typeface?
    ) {
        val labels = (scene.items + listOf("العامل الأول", "العامل الثاني")).take(2)
        val colors = listOf(COLOR_CYAN, COLOR_WARNING)
        val halfW = (r.width() - 28f) / 2f

        for (i in 0..1) {
            val left = r.left + i * (halfW + 28f)
            val card = RectF(left, r.top + 36f, left + halfW, r.bottom - 36f)
            canvas.drawRoundRect(card, 22f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ELEVATED })
            canvas.drawRoundRect(
                card,
                22f,
                22f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = withAlpha(colors[i], (80 + 175 * drawOn).toInt())
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
            )
            val iconName = scene.icons.getOrElse(i) { if (i == 0) "lightbulb" else "wave" }
            drawVectorIcon(canvas, resolveIcon(usedIcons, iconName), card.centerX(), card.centerY() - 36f, 84f * pop, colors[i])

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_TEXT
                textSize = 24f
                this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(labels[i].take(18), card.centerX(), card.centerY() + 72f, tp)
        }
    }

    private fun drawMeterTemplate(
        canvas: Canvas,
        r: RectF,
        scene: StoryboardScene,
        drawOn: Float,
        pop: Float,
        typeface: Typeface?
    ) {
        val cx = r.centerX()
        val cy = r.centerY() + 55f
        val radius = 178f
        val arcRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val bgArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_ELEVATED
            style = Paint.Style.STROKE
            strokeWidth = 28f
            strokeCap = Paint.Cap.ROUND
        }
        val fgArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_CYAN
            style = Paint.Style.STROKE
            strokeWidth = 28f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(arcRect, 180f, 180f, false, bgArc)
        val sweep = 180f * scene.metricPercent.coerceIn(0.05f, 1f) * drawOn
        canvas.drawArc(arcRect, 180f, sweep, false, fgArc)

        // Needle
        val needleRad = Math.toRadians((180f + sweep).toDouble())
        val nx = cx + ((radius - 36f) * cos(needleRad)).toFloat()
        val ny = cy + ((radius - 36f) * sin(needleRad)).toFloat()
        canvas.drawLine(
            cx,
            cy,
            nx,
            ny,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = COLOR_WARNING
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
            }
        )
        canvas.drawCircle(cx, cy, 14f * pop, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_TEXT })

        val valueText = scene.metricValue.ifBlank { "${(scene.metricPercent * 100).toInt()}%" }
        val valPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textSize = 52f
            this.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(valueText, cx, cy + 70f, valPaint)
    }

    private fun drawCaptions(
        canvas: Canvas,
        timeSec: Double,
        words: List<TimedWord>,
        accents: List<WordAccent>,
        typeface: Typeface?
    ) {
        if (words.isEmpty()) return

        val chunkSize = 4
        val activeWordIdx = words.indexOfLast { timeSec >= it.startSec }.coerceAtLeast(0)
        val chunkIdx = activeWordIdx / chunkSize
        val startIdx = chunkIdx * chunkSize
        val endIdx = minOf(startIdx + chunkSize, words.size)
        val chunk = words.subList(startIdx, endIdx)

        val accentMap = accents.associateBy { it.wordIndex }

        // Caption backdrop strip
        val capBox = RectF(44f, 868f, 676f, 1140f)
        canvas.drawRoundRect(capBox, 24f, 24f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(COLOR_CARD, 210) })

        val wordPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 40f
            this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
        }

        // Layout words right-to-left in 1 or 2 clean centered rows
        val maxRowWidth = capBox.width() - 48f
        val rows = mutableListOf<MutableList<TimedWord>>()
        var currentRow = mutableListOf<TimedWord>()
        var currentWidth = 0f
        val gap = 18f

        for (w in chunk) {
            val wWidth = wordPaint.measureText(w.text)
            if (currentRow.isNotEmpty() && currentWidth + gap + wWidth > maxRowWidth) {
                rows.add(currentRow)
                currentRow = mutableListOf(w)
                currentWidth = wWidth
            } else {
                if (currentRow.isNotEmpty()) currentWidth += gap
                currentRow.add(w)
                currentWidth += wWidth
            }
        }
        if (currentRow.isNotEmpty()) rows.add(currentRow)

        val rowHeight = 68f
        val totalTextHeight = rows.size * rowHeight
        var y = capBox.centerY() - totalTextHeight / 2f + 42f

        for (row in rows) {
            val rowWidth = row.sumOf { wordPaint.measureText(it.text).toDouble() }.toFloat() +
                (row.size - 1).coerceAtLeast(0) * gap
            // Draw RTL: start from right edge of centered block and move left
            var rightCursor = capBox.centerX() + rowWidth / 2f

            for (w in row) {
                val wWidth = wordPaint.measureText(w.text)
                val wordLeft = rightCursor - wWidth
                val isSpoken = timeSec >= w.startSec
                val isCurrent = timeSec in w.startSec..w.endSec
                val accent = accentMap[w.index]
                val toneColor = when (accent?.tone) {
                    AccentTone.INDIGO -> COLOR_INDIGO
                    AccentTone.SUCCESS -> COLOR_SUCCESS
                    AccentTone.WARNING -> COLOR_WARNING
                    AccentTone.CYAN, null -> COLOR_CYAN
                }

                // Active word highlight pill
                if (isCurrent) {
                    val pill = RectF(wordLeft - 10f, y - 40f, rightCursor + 10f, y + 14f)
                    canvas.drawRoundRect(
                        pill,
                        12f,
                        12f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(toneColor, 55) }
                    )
                }

                wordPaint.color = when {
                    isSpoken && accent != null -> toneColor
                    isCurrent -> COLOR_CYAN
                    isSpoken -> COLOR_TEXT
                    else -> withAlpha(COLOR_SECONDARY, 145)
                }
                wordPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(w.text, rightCursor, y, wordPaint)

                // Accent underline
                if (accent != null && isSpoken) {
                    val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = toneColor
                        strokeWidth = 4f
                        strokeCap = Paint.Cap.ROUND
                    }
                    canvas.drawLine(wordLeft, y + 10f, rightCursor, y + 10f, underlinePaint)
                }

                rightCursor -= (wWidth + gap)
            }
            y += rowHeight
        }
    }

    private fun drawBottomTelemetry(
        canvas: Canvas,
        timeSec: Double,
        totalDurationSec: Double,
        activeSceneIdx: Int,
        totalScenes: Int,
        typeface: Typeface?
    ) {
        val barRect = RectF(44f, 1185f, 676f, 1193f)
        canvas.drawRoundRect(barRect, 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_ELEVATED })

        val frac = (timeSec / totalDurationSec.coerceAtLeast(0.5)).toFloat().coerceIn(0f, 1f)
        val fillRect = RectF(barRect.left, barRect.top, barRect.left + barRect.width() * frac, barRect.bottom)
        canvas.drawRoundRect(fillRect, 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_CYAN })

        val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_SECONDARY
            textSize = 18f
            this.typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }
        val formattedTime = String.format(java.util.Locale.US, "%.1fs / %.1fs", timeSec, totalDurationSec)
        canvas.drawText(formattedTime, 44f, 1228f, infoPaint)

        val sceneCountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_SECONDARY
            textSize = 19f
            this.typeface = typeface ?: Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("مشهد ${activeSceneIdx + 1} / ${totalScenes.coerceAtLeast(1)}", 676f, 1228f, sceneCountPaint)
    }

    private fun drawProgressivePath(
        canvas: Canvas,
        path: Path,
        color: Int,
        strokeWidth: Float,
        progress: Float,
        dashed: Boolean = false
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (dashed) pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        if (progress >= 0.99f) {
            canvas.drawPath(path, paint)
            return
        }
        if (progress <= 0.01f) return
        val pm = PathMeasure(path, false)
        val segment = Path()
        do {
            val len = pm.length
            pm.getSegment(0f, len * progress, segment, true)
        } while (pm.nextContour())
        canvas.drawPath(segment, paint)
    }

    private fun drawVectorIcon(
        canvas: Canvas,
        icon: IconDefinition,
        cx: Float,
        cy: Float,
        sizePx: Float,
        color: Int
    ) {
        if (sizePx <= 1f) return
        val scale = sizePx / 24f
        val ox = cx - sizePx / 2f
        val oy = cy - sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = (2.1f * scale).coerceAtLeast(2.2f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (d in icon.paths) {
            val path = IconLibrary.svgToAndroidPath(d, scale = scale, offsetX = ox, offsetY = oy)
            canvas.drawPath(path, paint)
        }
    }

    private fun drawChipLabel(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        bgColor: Int,
        borderColor: Int,
        typeface: Typeface?
    ) {
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_TEXT
            textSize = 20f
            this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val clean = text.take(18)
        val w = (tp.measureText(clean) + 28f).coerceAtLeast(86f)
        val rect = RectF(cx - w / 2f, cy - 20f, cx + w / 2f, cy + 16f)
        canvas.drawRoundRect(rect, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor })
        canvas.drawRoundRect(
            rect,
            12f,
            12f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = borderColor
                style = Paint.Style.STROKE
                strokeWidth = 2f
            }
        )
        canvas.drawText(clean, cx, cy + 5f, tp)
    }

    private fun resolveIcon(usedIcons: Map<String, IconDefinition>, noun: String): IconDefinition {
        usedIcons[noun]?.let { return it }
        return usedIcons.values.firstOrNull() ?: IconDefinition(
            name = "lightbulb",
            source = "hand_drawn",
            license = "CC0-1.0",
            aliases = emptyList(),
            paths = listOf("M12 4C8.7 4 6.5 6.4 6.5 9.2C6.5 11.4 7.8 12.8 9 14.2H15C16.2 12.8 17.5 11.4 17.5 9.2C17.5 6.4 15.3 4 12 4Z")
        )
    }

    private fun easeOutCubic(x: Float): Float = 1f - (1f - x).pow(3)

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return (1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2)).coerceAtLeast(0f)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}
