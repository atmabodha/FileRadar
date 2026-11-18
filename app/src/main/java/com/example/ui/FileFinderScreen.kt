package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ai.AppMainTab
import com.example.model.FileCategory
import com.example.model.PhoneFileItem
import com.example.ui.components.EmptyFilesState
import com.example.ui.components.FileControlHeader
import com.example.ui.components.FileInspectorBottomSheet
import com.example.ui.components.FileResultCard
import com.example.ui.components.FullScreenImageViewerDialog
import com.example.ui.components.FullScreenVideoPlayerDialog
import com.example.viewmodel.FileFinderViewModel
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun FileFinderScreen(
    viewModel: FileFinderViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.path_copied_toast)

    // Automatically re-check All Files Access and rescan when returning from Android Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAppResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val launchAllFilesAccessSettings: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            } catch (_: Throwable) {
                try {
                    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(fallback)
                } catch (_: Throwable) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Open Android Settings > Apps > Special app access > All files access > File Radar")
                    }
                }
            }
        } else {
            viewModel.rescanStorage()
        }
    }

    BackHandler(
        enabled = uiState.selectedInspectorFile != null ||
            uiState.activeMediaPreviewFile != null ||
            uiState.selectedMainTab != AppMainTab.STANDARD
    ) {
        when {
            uiState.selectedInspectorFile != null -> viewModel.closeFileInspector()
            uiState.activeMediaPreviewFile != null -> viewModel.closeMediaPreview()
            uiState.selectedMainTab != AppMainTab.STANDARD -> viewModel.selectMainTab(AppMainTab.STANDARD)
        }
    }

    val copyPathToClipboard: (String) -> Unit = { fullPath ->
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("File Full Path", fullPath))
        coroutineScope.launch {
            snackbarHostState.showSnackbar("$copiedMessage: $fullPath")
        }
    }

    val shareFileWithApps: (PhoneFileItem) -> Unit = { fileItem ->
        try {
            val realFile = File(fileItem.fullPath)
            val fileToShare = if (realFile.exists() && realFile.canRead()) {
                realFile
            } else {
                val shareStagingDir = File(context.cacheDir, "shared_files").apply { mkdirs() }
                File(shareStagingDir, fileItem.name).apply {
                    if (!exists()) {
                        writeText("File: ${fileItem.name}\nPath: ${fileItem.fullPath}\nSize: ${fileItem.formattedSize()}\n")
                    }
                }
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                fileToShare
            )
            val resolvedMime = fileItem.mimeType.ifBlank { "*/*" }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = resolvedMime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, fileItem.name)
                clipData = ClipData.newRawUri(fileItem.name, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooserIntent = Intent.createChooser(shareIntent, "Share ${fileItem.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        } catch (e: Throwable) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Unable to share ${fileItem.name}: ${e.localizedMessage ?: "Check storage permission"}")
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                NavigationBarItem(
                    selected = uiState.selectedMainTab == AppMainTab.STANDARD,
                    onClick = { viewModel.selectMainTab(AppMainTab.STANDARD) },
                    modifier = Modifier.testTag("nav_tab_standard"),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Standard"
                        )
                    },
                    label = { Text("Standard") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                NavigationBarItem(
                    selected = uiState.selectedMainTab == AppMainTab.AI_SEARCH,
                    onClick = { viewModel.selectMainTab(AppMainTab.AI_SEARCH) },
                    modifier = Modifier.testTag("nav_tab_ai_search"),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Search"
                        )
                    },
                    label = { Text("AI Search") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                NavigationBarItem(
                    selected = uiState.selectedMainTab == AppMainTab.SETTINGS,
                    onClick = { viewModel.selectMainTab(AppMainTab.SETTINGS) },
                    modifier = Modifier.testTag("nav_tab_settings"),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = { Text("Settings") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            when (uiState.selectedMainTab) {
                AppMainTab.STANDARD -> {
                    // Current Standard Screen — completely preserved as-is
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                            .testTag("file_results_list"),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "control_header") {
                            FileControlHeader(
                                selectedCategory = uiState.selectedCategory,
                                selectedStorage = uiState.selectedStorage,
                                selectedSortField = uiState.selectedSortField,
                                sortOrder = uiState.sortOrder,
                                searchQuery = uiState.searchQuery,
                                matchingCount = uiState.matchingFiles.size,
                                totalMatchingBytes = uiState.totalMatchingBytes,
                                categoryCounts = uiState.categoryCounts,
                                storageCounts = uiState.storageCounts,
                                hasAllFilesAccess = uiState.hasAllFilesAccess,
                                includeSampleIndex = uiState.includeSampleIndex,
                                onSelectCategory = viewModel::selectCategory,
                                onSelectStorage = viewModel::selectStorageLocation,
                                onSelectSortField = viewModel::selectSortField,
                                onToggleSortOrder = viewModel::toggleSortOrder,
                                onSearchQueryChange = viewModel::updateSearchQuery,
                                onGrantAllFilesAccess = launchAllFilesAccessSettings,
                                onToggleSampleIndex = viewModel::toggleIncludeSampleIndex,
                                onRescan = viewModel::rescanStorage
                            )
                        }

                        if (uiState.isScanning) {
                            item(key = "scanning_indicator") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else if (uiState.matchingFiles.isEmpty()) {
                            item(key = "empty_state") {
                                EmptyFilesState(
                                    onResetFilters = viewModel::resetFilters
                                )
                            }
                        } else {
                            items(
                                items = uiState.matchingFiles,
                                key = { it.id }
                            ) { fileItem ->
                                FileResultCard(
                                    file = fileItem,
                                    activeSortField = uiState.selectedSortField,
                                    onCopyPath = copyPathToClipboard,
                                    onShareFile = shareFileWithApps,
                                    onInspectFile = viewModel::onFileClicked,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }

                AppMainTab.AI_SEARCH -> {
                    AiSearchScreen(
                        aiSettings = uiState.aiSettings,
                        hardware = uiState.hardwareTelemetry,
                        selectedAiTab = uiState.selectedAiFeatureTab,
                        semanticQuery = uiState.semanticQuery,
                        semanticResults = uiState.semanticResults,
                        dynamicLenses = uiState.dynamicLenses,
                        renameSuggestions = uiState.renameSuggestions,
                        appliedRenames = uiState.appliedRenames,
                        junkCandidates = uiState.junkCandidates,
                        nlCommandInput = uiState.nlCommandInput,
                        nlCommandPlan = uiState.nlCommandPlan,
                        selectedRagFile = uiState.selectedRagFile,
                        allFiles = uiState.allFiles,
                        ragMessages = uiState.ragMessages,
                        onSelectAiTab = viewModel::selectAiFeatureTab,
                        onUpdateSemanticQuery = viewModel::updateSemanticQuery,
                        onApplyRename = viewModel::toggleApplyRename,
                        onToggleJunkReview = viewModel::toggleJunkReview,
                        onUpdateNlCommand = viewModel::updateNlCommandInput,
                        onExecuteNlCommand = viewModel::executeNlCommandPlan,
                        onSelectRagDocument = viewModel::selectRagDocument,
                        onSendRagQuestion = viewModel::sendRagQuestion,
                        onEnableMasterAi = { viewModel.setMasterAiEnabled(true) },
                        onFileClicked = viewModel::onFileClicked,
                        onCopyPath = copyPathToClipboard,
                        onShareFile = shareFileWithApps,
                        modifier = Modifier.widthIn(max = 760.dp)
                    )
                }

                AppMainTab.SETTINGS -> {
                    AiSettingsScreen(
                        aiSettings = uiState.aiSettings,
                        hardware = uiState.hardwareTelemetry,
                        onToggleMasterAi = viewModel::setMasterAiEnabled,
                        onToggleBatteryNotLow = viewModel::setRequiresBatteryNotLow,
                        onToggleRequiresCharging = viewModel::setRequiresChargingForBacklog,
                        onUpdateThermalDelay = viewModel::setThermalPacingDelayMs,
                        onUpdateChunkSize = viewModel::setChunkSizeTokens,
                        onTriggerReindex = { viewModel.startBackgroundAiIndexing(paced = true) },
                        onDownloadOrLoadGemma = viewModel::warmUpGemmaInBackgroundIo,
                        onFlushModelRam = {
                            viewModel.flushModelFromRam()
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Inference engine closed and flushed from RAM.")
                            }
                        },
                        modifier = Modifier.widthIn(max = 760.dp)
                    )
                }
            }

            // Enlarged Image Viewer or In-App Video Player when an image/video row is clicked from any tab
            uiState.activeMediaPreviewFile?.let { mediaFile ->
                when (mediaFile.category) {
                    FileCategory.IMAGE -> {
                        FullScreenImageViewerDialog(
                            file = mediaFile,
                            onCopyPath = copyPathToClipboard,
                            onShareFile = shareFileWithApps,
                            onOpenMetadata = { file ->
                                viewModel.openFileInspector(file)
                            },
                            onDismiss = viewModel::closeMediaPreview
                        )
                    }
                    FileCategory.VIDEO -> {
                        FullScreenVideoPlayerDialog(
                            file = mediaFile,
                            onCopyPath = copyPathToClipboard,
                            onShareFile = shareFileWithApps,
                            onOpenMetadata = { file ->
                                viewModel.openFileInspector(file)
                            },
                            onDismiss = viewModel::closeMediaPreview
                        )
                    }
                    else -> Unit
                }
            }

            // File Metadata Inspector Bottom Sheet
            uiState.selectedInspectorFile?.let { inspectedFile ->
                FileInspectorBottomSheet(
                    file = inspectedFile,
                    onCopyPath = copyPathToClipboard,
                    onShareFile = shareFileWithApps,
                    onDismiss = viewModel::closeFileInspector
                )
            }
        }
    }
}
