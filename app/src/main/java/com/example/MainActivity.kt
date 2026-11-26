package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ViewQuilt
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.PipelineStage
import com.example.ui.AppTab
import com.example.ui.StudioViewModel
import com.example.ui.screens.PreviewScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.screens.TemplatesScreen
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.BgCanvas
import com.example.ui.theme.ReelStudioTheme
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReelStudioRoot()
        }
    }
}

@Composable
fun ReelStudioRoot(
    viewModel: StudioViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(uiState.statusBanner) {
        val msg = uiState.statusBanner
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    ReelStudioTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavRail = maxWidth >= 720.dp

                Scaffold(
                    containerColor = BgCanvas,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    bottomBar = {
                        if (!useNavRail) {
                            NavigationBar(containerColor = SurfaceCard) {
                                AppTab.entries.forEach { tab ->
                                    val icon = when (tab) {
                                        AppTab.STUDIO -> Icons.Rounded.AutoAwesome
                                        AppTab.PREVIEW -> Icons.Rounded.Movie
                                        AppTab.TEMPLATES -> Icons.AutoMirrored.Rounded.ViewQuilt
                                        AppTab.SETTINGS -> Icons.Rounded.Settings
                                    }
                                    NavigationBarItem(
                                        selected = uiState.activeTab == tab,
                                        onClick = { viewModel.selectTab(tab) },
                                        icon = { Icon(icon, contentDescription = tab.label) },
                                        label = { Text(tab.label) },
                                        modifier = Modifier.testTag("nav_${tab.name.lowercase()}"),
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = AccentCyan,
                                            selectedTextColor = AccentCyan,
                                            indicatorColor = SurfaceElevated,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (useNavRail) {
                            NavigationRail(containerColor = SurfaceCard) {
                                AppTab.entries.forEach { tab ->
                                    val icon = when (tab) {
                                        AppTab.STUDIO -> Icons.Rounded.AutoAwesome
                                        AppTab.PREVIEW -> Icons.Rounded.Movie
                                        AppTab.TEMPLATES -> Icons.AutoMirrored.Rounded.ViewQuilt
                                        AppTab.SETTINGS -> Icons.Rounded.Settings
                                    }
                                    NavigationRailItem(
                                        selected = uiState.activeTab == tab,
                                        onClick = { viewModel.selectTab(tab) },
                                        icon = { Icon(icon, contentDescription = tab.label) },
                                        label = { Text(tab.label) },
                                        modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize()) {
                            when (uiState.activeTab) {
                                AppTab.STUDIO -> StudioScreen(
                                    uiState = uiState,
                                    onTopicChange = viewModel::updateTopic,
                                    onDurationChange = viewModel::updateDuration,
                                    onVoiceModeChange = viewModel::updateVoiceInputMode,
                                    onAudioPicked = viewModel::handleUploadedAudioUri,
                                    onStartPipeline = viewModel::startNewPipeline,
                                    onApproveStage = viewModel::approveAndContinue,
                                    onApprovalModeChange = viewModel::setApprovalMode,
                                    onCancelPipeline = viewModel::cancelPipeline,
                                    onSaveScriptEdit = viewModel::updateScriptManually,
                                    onSaveStoryboardEdit = viewModel::updateStoryboardManually,
                                    onOpenPreviewTab = { viewModel.selectTab(AppTab.PREVIEW) }
                                )
                                AppTab.PREVIEW -> PreviewScreen(
                                    uiState = uiState,
                                    onExportMp4 = { viewModel.startNewPipeline(PipelineStage.RENDER) },
                                    onShareMp4 = {
                                        viewModel.shareExportedVideoIntent()?.let { intent ->
                                            context.startActivity(
                                                android.content.Intent.createChooser(intent, "مشاركة الريل")
                                            )
                                        }
                                    }
                                )
                                AppTab.TEMPLATES -> TemplatesScreen(
                                    uiState = uiState,
                                    iconLibrary = viewModel.iconLibrary,
                                    onRefreshContactSheet = viewModel::refreshTemplateContactSheet
                                )
                                AppTab.SETTINGS -> SettingsScreen(
                                    uiState = uiState,
                                    onSaveSettings = viewModel::updateSettings,
                                    onLoadVoices = viewModel::loadVoices,
                                    onPickFontFile = viewModel::handleCustomFontUri,
                                    onResetFont = viewModel::resetToBundledFont
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
