package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.core.IconLibrary
import com.example.core.ScriptParser
import com.example.core.StoryboardEngine
import com.example.core.ThinkBlockStripper
import com.example.core.TimingAligner
import com.example.data.SettingsRepository
import com.example.model.AppSettings
import com.example.model.ReelProject
import com.example.model.SceneTemplate
import com.example.model.SceneType
import com.example.model.StoryboardData
import com.example.model.StoryboardScene
import com.example.model.TimedWord
import com.example.network.ApiClients
import com.example.network.LocalMockApiServer
import com.example.render.AudioTrackBuilder
import com.example.render.ReelRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReelStudioEndToEndTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `think block stripper handles split SSE tags`() {
        val stripper = ThinkBlockStripper()
        val chunks = listOf("مقدمة ", "<thi", "nk>تفكير داخلي", " سري</thi", "nk>\nTITLE: ", "سر السماء")
        val out = StringBuilder()
        chunks.forEach { out.append(stripper.feed(it)) }
        out.append(stripper.flush())
        assertEquals("مقدمة \nTITLE: سر السماء", out.toString())
    }

    @Test
    fun `timing aligner preserves script spelling via character LCS and flags weak words`() {
        val script = "عارف ليه السماء لونها أزرق بسبب Photons"
        val timed = listOf(
            TimedWord(0, "عارف", 0.1, 0.4, 0.96),
            TimedWord(1, "ليه", 0.45, 0.7, 0.95),
            TimedWord(2, "السما", 0.75, 1.1, 0.92),
            TimedWord(3, "لونها", 1.15, 1.5, 0.94),
            TimedWord(4, "بسبب", 1.9, 2.2, 0.91),
            TimedWord(5, "photons", 2.25, 2.7, 0.95)
        )

        val aligned = TimingAligner.alignToScript(script, timed, 3.0)
        assertEquals(7, aligned.size)
        assertEquals("السماء", aligned[2].text)
        assertEquals("أزرق", aligned[4].text)
        assertTrue(aligned[4].isWeak)
        assertFalse(aligned[0].isWeak)
        for (i in 1 until aligned.size) {
            assertTrue(aligned[i].startSec >= aligned[i - 1].endSec)
        }
    }

    @Test
    fun `storyboard validator repairs unsorted indices intro and 3-in-a-row templates`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val iconLib = IconLibrary.loadFromAssets(context)
        val raw = StoryboardData(
            scenes = listOf(
                StoryboardScene(SceneType.CONCEPT, SceneTemplate.CARDS, 5, "ثاني"),
                StoryboardScene(SceneType.STAT, SceneTemplate.CARDS, 999, "أخير"),
                StoryboardScene(SceneType.PROCESS, SceneTemplate.CARDS, 2, "أول"),
                StoryboardScene(SceneType.RECAP, SceneTemplate.CARDS, 7, "تالت")
            )
        )
        val repaired = StoryboardEngine.validateAndRepair(raw, wordCount = 10, iconLibrary = iconLib)
        assertEquals(SceneType.INTRO, repaired.scenes.first().type)
        assertEquals(0, repaired.scenes.first().atWordIndex)
        for (i in 1 until repaired.scenes.size) {
            assertTrue(repaired.scenes[i].atWordIndex > repaired.scenes[i - 1].atWordIndex)
            assertTrue(repaired.scenes[i].atWordIndex < 10)
        }
        for (i in 2 until repaired.scenes.size) {
            val threeSame = repaired.scenes[i].template == repaired.scenes[i - 1].template &&
                repaired.scenes[i - 1].template == repaired.scenes[i - 2].template
            assertFalse("Template repeated 3 times in a row", threeSame)
        }
    }

    @Test
    fun `settings masks api keys to last 4 chars`() {
        assertEquals("غير مضبوط", AppSettings.maskKey(""))
        assertEquals("••••••••7890", AppSettings.maskKey("sk-test-1234567890"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)
        val saved = AppSettings(llmApiKey = "secret-key-abcd")
        repo.save(saved)
        val masked = repo.getMaskedKeys(repo.load())
        assertEquals("••••••••abcd", masked.llmKeyMask)
    }

    @Test
    fun `mock api server executes full 10s script voice timing storyboard and frame render`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val iconLib = IconLibrary.loadFromAssets(context)
        val server = LocalMockApiServer(0)
        val port = server.start()
        try {
            val base = "http://127.0.0.1:$port"
            val clients = ApiClients()

            val styleGuide = ScriptParser.loadStyleGuide(context)
            val (sys, usr) = ScriptParser.buildPromptMessages("ليه السماء لونها أزرق؟", 10, styleGuide)
            val rawScript = clients.streamChatCompletion("$base/v1", "", "auto", sys, usr) { _, _ -> }
            assertFalse(rawScript.contains("<think>"))
            val scriptData = ScriptParser.parse(rawScript, "ليه السماء لونها أزرق؟")
            assertTrue(scriptData.script.contains("عارف ليه"))

            val voices = clients.loadElevenLabsVoices(base, "")
            assertTrue(voices.isNotEmpty())
            val wavFile = File(context.cacheDir, "test_voice.wav")
            val tts = clients.synthesizeElevenLabsWithTimestamps(
                baseUrl = base,
                apiKey = "",
                voiceId = voices.first().voiceId,
                text = scriptData.script,
                targetDurationSec = 10,
                outputFile = wavFile
            )
            assertTrue(wavFile.exists() && wavFile.length() > 1000)
            assertTrue(tts.timedWords.isNotEmpty())

            val aligned = TimingAligner.alignToScript(scriptData.script, tts.timedWords, 10.0)
            assertEquals(scriptData.words.size, aligned.size)

            val (sbSys, sbUsr) = StoryboardEngine.buildStoryboardPrompt(scriptData.title, aligned, iconLib.icons.keys)
            val sbRaw = clients.streamChatCompletion("$base/v1", "", "auto", sbSys, sbUsr) { _, _ -> }
            val draftBoard = StoryboardEngine.parseAndRepair(sbRaw, aligned.size, iconLib)

            val (revSys, revUsr) = StoryboardEngine.buildSelfReviewPrompt(StoryboardEngine.toJsonString(draftBoard), aligned)
            val revRaw = clients.streamChatCompletion("$base/v1", "", "auto", revSys, revUsr) { _, _ -> }
            val finalBoard = StoryboardEngine.parseAndRepair(revRaw, aligned.size, iconLib)
            assertTrue(finalBoard.scenes.size >= 4)

            val duration = maxOf(10.0, (aligned.maxOfOrNull { it.endSec } ?: 10.0) + 0.25)
            val project = ReelProject(
                id = "test_10s",
                topic = "ليه السماء لونها أزرق؟",
                durationSec = 10,
                scriptData = scriptData,
                voiceAudioPath = wavFile.absolutePath,
                alignedWords = aligned,
                storyboard = finalBoard,
                usedIcons = iconLib.extractUsedIcons(finalBoard.scenes.flatMap { it.icons }),
                totalDurationSec = duration
            )

            val cutTimes = ReelRenderer.resolveSceneStartTimes(finalBoard.scenes, aligned, duration)
            val pcm = AudioTrackBuilder.buildNormalizedMixedPcm(wavFile, duration, cutTimes)
            val lufs = AudioTrackBuilder.measureApproxLufs(pcm)
            assertTrue("Expected LUFS near -14, got $lufs", abs(lufs - (-14.0)) < 4.5)

            val frameBmp = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
            ReelRenderer.drawFrame(Canvas(frameBmp), 360f, 640f, 1.2, project, null)
            assertNotEquals(0, frameBmp.getPixel(180, 320))

            val contactSheet = ReelRenderer.renderContactSheet(project, iconLib, null)
            assertEquals(1080, contactSheet.width)
            assertEquals(1920, contactSheet.height)
            assertNotEquals(0, contactSheet.getPixel(540, 960))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `compose ui navigates across studio preview templates and settings`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("topic_input").assertIsDisplayed()
        composeRule.onNodeWithTag("start_pipeline_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("studio_lazy_list")
            .performScrollToNode(androidx.compose.ui.test.hasTestTag("stage_card_script"))
        composeRule.onNodeWithTag("stage_card_script").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_templates").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("template_chip_body").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("mock_mode_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("load_voices_button").performScrollTo().assertIsDisplayed()
    }
}
