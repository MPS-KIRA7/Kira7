package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ui.StudioUiState
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SurfaceBorder
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun SettingsScreen(
    uiState: StudioUiState,
    onSaveSettings: (
        llmBaseUrl: String?,
        llmKeyInput: String?,
        llmModel: String?,
        elevenBaseUrl: String?,
        elevenKeyInput: String?,
        voiceId: String?,
        deepgramBaseUrl: String?,
        deepgramKeyInput: String?,
        useMock: Boolean?
    ) -> Unit,
    onLoadVoices: () -> Unit,
    onPickFontFile: (Uri) -> Unit,
    onResetFont: () -> Unit
) {
    val s = uiState.settings
    val masked = uiState.maskedKeys

    var llmBaseUrl by remember(s.llmBaseUrl) { mutableStateOf(s.llmBaseUrl) }
    var llmModel by remember(s.llmModel) { mutableStateOf(s.llmModel) }
    var llmKeyDraft by remember { mutableStateOf("") }

    var elevenBaseUrl by remember(s.elevenLabsBaseUrl) { mutableStateOf(s.elevenLabsBaseUrl) }
    var elevenKeyDraft by remember { mutableStateOf("") }

    var deepgramBaseUrl by remember(s.deepgramBaseUrl) { mutableStateOf(s.deepgramBaseUrl) }
    var deepgramKeyDraft by remember { mutableStateOf("") }

    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onPickFontFile(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Offline Mock API Toggle Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, if (s.useLocalMockApis) SuccessGreen.copy(alpha = 0.6f) else SurfaceBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("وضع المحاكاة المحلي (127.0.0.1:4399)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "يشغّل خادم محاكاة لـ FreeLLMAPI و ElevenLabs و Deepgram للاختبار الكامل بدون مفاتيح.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = s.useLocalMockApis,
                    onCheckedChange = { checked ->
                        onSaveSettings(null, null, null, null, null, null, null, null, checked)
                    },
                    modifier = Modifier.testTag("mock_mode_switch")
                )
            }
        }

        // LLM Settings Card (FreeLLMAPI / OpenAI-compatible)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, SurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("1. واجهة النماذج اللغوية (FreeLLMAPI / OpenAI)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = llmBaseUrl,
                    onValueChange = { llmBaseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = llmModel,
                    onValueChange = { llmModel = it },
                    label = { Text("Model (مثل auto)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "المفتاح المحفوظ: ${masked.llmKeyMask}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentCyan
                )
                OutlinedTextField(
                    value = llmKeyDraft,
                    onValueChange = { llmKeyDraft = it },
                    label = { Text("تحديث مفتاح LLM (اختياري لـ FreeLLMAPI)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ElevenLabs Settings Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, SurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("2. الصوت والتوقيت (ElevenLabs)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = elevenBaseUrl,
                    onValueChange = { elevenBaseUrl = it },
                    label = { Text("ElevenLabs Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "المفتاح المحفوظ: ${masked.elevenLabsKeyMask}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentCyan
                )
                OutlinedTextField(
                    value = elevenKeyDraft,
                    onValueChange = { elevenKeyDraft = it },
                    label = { Text("تحديث مفتاح ElevenLabs") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("اختيار الصوت:", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    OutlinedButton(
                        onClick = onLoadVoices,
                        enabled = !uiState.isLoadingVoices,
                        modifier = Modifier.testTag("load_voices_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (uiState.isLoadingVoices) "جاري التحميل..." else "تحميل الأصوات")
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.availableVoices.forEach { voice ->
                        FilterChip(
                            selected = s.selectedVoiceId == voice.voiceId,
                            onClick = {
                                onSaveSettings(null, null, null, null, null, voice.voiceId, null, null, null)
                            },
                            label = { Text(voice.name) }
                        )
                    }
                }
            }
        }

        // Deepgram Settings Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, SurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("3. محاذاة الصوت المرفوع (Deepgram nova-3 ar)", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = deepgramBaseUrl,
                    onValueChange = { deepgramBaseUrl = it },
                    label = { Text("Deepgram Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "المفتاح المحفوظ: ${masked.deepgramKeyMask}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentCyan
                )
                OutlinedTextField(
                    value = deepgramKeyDraft,
                    onValueChange = { deepgramKeyDraft = it },
                    label = { Text("تحديث مفتاح Deepgram") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Font Folder / Custom Arabic Font Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard),
            border = BorderStroke(1.dp, SurfaceBorder)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("مجلد / ملف الخطوط العربية", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "الخط الحالي: ${s.fontDisplayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AccentCyan
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { fontPicker.launch(arrayOf("font/*", "application/octet-stream", "*/*")) },
                        modifier = Modifier.testTag("pick_font_button")
                    ) {
                        Icon(Icons.Rounded.FontDownload, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("اختيار خط (.ttf/.otf)")
                    }
                    OutlinedButton(onClick = onResetFont) {
                        Text("IBM Plex الافتراضي")
                    }
                }
            }
        }

        Button(
            onClick = {
                onSaveSettings(
                    llmBaseUrl,
                    llmKeyDraft.takeIf { it.isNotBlank() },
                    llmModel,
                    elevenBaseUrl,
                    elevenKeyDraft.takeIf { it.isNotBlank() },
                    null,
                    deepgramBaseUrl,
                    deepgramKeyDraft.takeIf { it.isNotBlank() },
                    null
                )
                llmKeyDraft = ""
                elevenKeyDraft = ""
                deepgramKeyDraft = ""
            },
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 700.dp)
                .height(50.dp)
                .testTag("save_settings_button"),
            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgCanvas)
        ) {
            Icon(Icons.Rounded.Save, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("حفظ الإعدادات في data/settings.json")
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
