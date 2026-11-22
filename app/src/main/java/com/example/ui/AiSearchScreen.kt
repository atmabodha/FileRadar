package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ai.AiFeatureTab
import com.example.ai.AiSettingsState
import com.example.ai.AiSortField
import com.example.ai.DynamicLensGroup
import com.example.ai.HardwareTelemetry
import com.example.ai.JunkCandidate
import com.example.ai.NlCommandPlan
import com.example.ai.RagChatMessage
import com.example.ai.RenameSuggestion
import com.example.ai.SemanticSearchResult
import com.example.model.FileCategory
import com.example.model.FileFormatters
import com.example.model.PhoneFileItem
import com.example.model.SortOrder
import com.example.ui.components.MediaThumbnailBox
import com.example.ui.components.categoryIcon
import java.util.Locale

@Composable
private fun aiFeatureIcon(tab: AiFeatureTab): ImageVector = when (tab) {
    AiFeatureTab.SEMANTIC_SEARCH -> Icons.Default.Psychology
    AiFeatureTab.DYNAMIC_LENSES -> Icons.Default.ViewCarousel
    AiFeatureTab.AUTO_RENAME -> Icons.Default.DriveFileRenameOutline
    AiFeatureTab.JUNK_CLEANUP -> Icons.Default.CleaningServices
    AiFeatureTab.NL_COMMANDS -> Icons.Default.Terminal
    AiFeatureTab.DOCUMENT_QA -> Icons.Default.Forum
}

@Composable
fun AiSearchScreen(
    aiSettings: AiSettingsState,
    hardware: HardwareTelemetry,
    selectedAiTab: AiFeatureTab,
    selectedAiCategory: FileCategory,
    aiCategoryCounts: Map<FileCategory, Int>,
    selectedAiSortField: AiSortField,
    aiSortOrder: SortOrder,
    semanticQuery: String,
    semanticResults: List<SemanticSearchResult>,
    dynamicLenses: List<DynamicLensGroup>,
    renameSuggestions: List<RenameSuggestion>,
    appliedRenames: Map<String, String>,
    junkCandidates: List<JunkCandidate>,
    nlCommandInput: String,
    nlCommandPlan: NlCommandPlan,
    selectedRagFile: PhoneFileItem?,
    allFiles: List<PhoneFileItem>,
    ragMessages: List<RagChatMessage>,
    onSelectAiTab: (AiFeatureTab) -> Unit,
    onSelectAiCategory: (FileCategory) -> Unit,
    onSelectAiSortField: (AiSortField) -> Unit,
    onToggleAiSortOrder: () -> Unit,
    onUpdateSemanticQuery: (String) -> Unit,
    onApplyRename: (RenameSuggestion) -> Unit,
    onToggleJunkReview: (String) -> Unit,
    onUpdateNlCommand: (String) -> Unit,
    onExecuteNlCommand: () -> Unit,
    onSelectRagDocument: (PhoneFileItem) -> Unit,
    onSendRagQuestion: (String) -> Unit,
    onEnableMasterAi: () -> Unit,
    onFileClicked: (PhoneFileItem) -> Unit,
    onCopyPath: (String) -> Unit,
    onShareFile: (PhoneFileItem) -> Unit,
    onOpenPdfChat: ((PhoneFileItem) -> Unit)? = null,
    onOpenImageOcrScanner: () -> Unit = {},
    isScanning: Boolean = false,
    hasAllFilesAccess: Boolean = true,
    onRescan: () -> Unit = {},
    onGrantAllFilesAccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Graceful degradation when Master AI Kill Switch is OFF
    if (!aiSettings.masterAiEnabled) {
        MasterAiDisabledFallbackView(
            allFiles = allFiles,
            selectedAiCategory = selectedAiCategory,
            aiCategoryCounts = aiCategoryCounts,
            selectedAiSortField = selectedAiSortField,
            aiSortOrder = aiSortOrder,
            onSelectAiCategory = onSelectAiCategory,
            onSelectAiSortField = onSelectAiSortField,
            onToggleAiSortOrder = onToggleAiSortOrder,
            onEnableMasterAi = onEnableMasterAi,
            onFileClicked = onFileClicked,
            onCopyPath = onCopyPath,
            onShareFile = onShareFile,
            isScanning = isScanning,
            hasAllFilesAccess = hasAllFilesAccess,
            onRescan = onRescan,
            onGrantAllFilesAccess = onGrantAllFilesAccess,
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("ai_search_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. On-Device AI Status Header + Feature Mode Selector + File Type & Sort Controls
        item(key = "ai_header") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "On-Device AI Intelligence",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = "${aiSettings.totalVectorChunks} vector chunks (${aiSettings.indexedFileCount} files) • RAM ${hardware.availableRamGb}/${hardware.totalRamGb} GB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onOpenImageOcrScanner,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("ai_open_image_ocr_scanner_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DocumentScanner,
                                contentDescription = "Scan Text (OCR)",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Scan Text (OCR)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        IconButton(
                            onClick = onRescan,
                            enabled = !isScanning,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag("ai_rescan_button")
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Sync & rescan phone files and recent images",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                if (!hasAllFilesAccess) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            ) {
                                Text(
                                    text = "Full Storage Scan Required",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Allow access to index all recent photos, screenshots & PDFs on your phone.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Surface(
                                onClick = onGrantAllFilesAccess,
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("ai_grant_all_files_access_button")
                            ) {
                                Text(
                                    text = "Grant Access",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSecondary,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }

                if (isScanning) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Syncing recent phone images & storage files…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else if (aiSettings.isIndexingPipelineRunning) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Thermal-paced background indexing: ${aiSettings.currentPipelineFile ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 6 Core AI Feature Mode Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AiFeatureTab.entries.forEach { tab ->
                        val isSelected = tab == selectedAiTab
                        FilterChip(
                            selected = isSelected,
                            onClick = { onSelectAiTab(tab) },
                            modifier = Modifier.testTag("ai_feature_tab_${tab.name}"),
                            leadingIcon = {
                                Icon(
                                    imageVector = aiFeatureIcon(tab),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.primary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                Text(
                    text = selectedAiTab.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // File Type Dropdown Selector (same FileCategory list as Standard screen)
                AiFileTypeDropdownBar(
                    selectedCategory = selectedAiCategory,
                    categoryCounts = aiCategoryCounts,
                    onSelectCategory = onSelectAiCategory
                )

                // Single-Row Sort Controls (Sort By Dropdown + Ascending/Descending Toggle) matching Standard screen
                if (selectedAiTab != AiFeatureTab.DOCUMENT_QA) {
                    AiSortControlBar(
                        selectedSortField = selectedAiSortField,
                        sortOrder = aiSortOrder,
                        onSelectSortField = onSelectAiSortField,
                        onToggleSortOrder = onToggleAiSortOrder
                    )
                }
            }
        }

        // 2. Render Selected AI Feature Content
        when (selectedAiTab) {
            AiFeatureTab.SEMANTIC_SEARCH -> {
                item(key = "semantic_search_bar") {
                    SemanticSearchInputSection(
                        query = semanticQuery,
                        onQueryChange = onUpdateSemanticQuery
                    )
                }

                items(
                    items = semanticResults,
                    key = { "sem_${it.file.id}" }
                ) { result ->
                    SemanticResultCard(
                        result = result,
                        activeSortField = selectedAiSortField,
                        appliedRename = appliedRenames[result.file.id],
                        onFileClicked = onFileClicked,
                        onCopyPath = onCopyPath,
                        onShareFile = onShareFile
                    )
                }
            }

            AiFeatureTab.DYNAMIC_LENSES -> {
                items(
                    items = dynamicLenses,
                    key = { "lens_${it.id}" }
                ) { lensGroup ->
                    DynamicLensCard(
                        lens = lensGroup,
                        onFileClicked = onFileClicked,
                        onShareFile = onShareFile
                    )
                }
            }

            AiFeatureTab.AUTO_RENAME -> {
                items(
                    items = renameSuggestions,
                    key = { "rename_${it.file.id}" }
                ) { suggestion ->
                    AutoRenameCard(
                        suggestion = suggestion,
                        onApplyToggle = { onApplyRename(suggestion) },
                        onFileClicked = onFileClicked
                    )
                }
            }

            AiFeatureTab.JUNK_CLEANUP -> {
                items(
                    items = junkCandidates,
                    key = { "junk_${it.file.id}" }
                ) { candidate ->
                    JunkCandidateCard(
                        candidate = candidate,
                        onToggleReview = { onToggleJunkReview(candidate.file.id) },
                        onFileClicked = onFileClicked
                    )
                }
            }

            AiFeatureTab.NL_COMMANDS -> {
                item(key = "nl_command_box") {
                    NaturalLanguageCommandSection(
                        commandInput = nlCommandInput,
                        plan = nlCommandPlan,
                        onUpdateCommand = onUpdateNlCommand,
                        onExecutePlan = onExecuteNlCommand,
                        onFileClicked = onFileClicked
                    )
                }
            }

            AiFeatureTab.DOCUMENT_QA -> {
                item(key = "doc_qa_section") {
                    val categoryFilteredRagFiles = remember(allFiles, selectedAiCategory) {
                        if (selectedAiCategory == FileCategory.ALL) allFiles
                        else allFiles.filter { it.category == selectedAiCategory }.ifEmpty { allFiles }
                    }
                    DocumentQaRagSection(
                        selectedFile = selectedRagFile,
                        allFiles = categoryFilteredRagFiles,
                        messages = ragMessages,
                        onSelectDocument = onSelectRagDocument,
                        onSendQuestion = onSendRagQuestion,
                        onOpenPdfChat = onOpenPdfChat
                    )
                }
            }
        }
    }
}

@Composable
private fun AiFileTypeDropdownBar(
    selectedCategory: FileCategory,
    categoryCounts: Map<FileCategory, Int>,
    onSelectCategory: (FileCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    var fileTypeMenuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = { fileTypeMenuExpanded = !fileTypeMenuExpanded },
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("ai_file_type_dropdown_trigger"),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = if (selectedCategory != FileCategory.ALL || fileTypeMenuExpanded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = categoryIcon(selectedCategory),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = selectedCategory.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${categoryCounts[selectedCategory] ?: 0}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }

        DropdownMenu(
            expanded = fileTypeMenuExpanded,
            onDismissRequest = { fileTypeMenuExpanded = false },
            modifier = Modifier.fillMaxWidth(0.92f),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            FileCategory.entries.forEach { category ->
                val isSelected = category == selectedCategory
                val count = categoryCounts[category] ?: 0
                DropdownMenuItem(
                    modifier = Modifier.testTag("ai_dropdown_category_${category.name}"),
                    leadingIcon = {
                        Icon(
                            imageVector = categoryIcon(category),
                            contentDescription = null,
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = category.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Text(
                                text = category.extensionsHint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    trailingIcon = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelectCategory(category)
                        fileTypeMenuExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AiSortControlBar(
    selectedSortField: AiSortField,
    sortOrder: SortOrder,
    onSelectSortField: (AiSortField) -> Unit,
    onToggleSortOrder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Box {
            Surface(
                onClick = { sortMenuExpanded = true },
                modifier = Modifier
                    .height(36.dp)
                    .testTag("ai_sort_by_dropdown_trigger"),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Sort: ${selectedSortField.displayName}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = sortMenuExpanded,
                onDismissRequest = { sortMenuExpanded = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                AiSortField.entries.forEach { field ->
                    val isCurrent = field == selectedSortField
                    DropdownMenuItem(
                        modifier = Modifier.testTag("ai_sort_menu_item_${field.name}"),
                        text = {
                            Column {
                                Text(
                                    text = field.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                                Text(
                                    text = field.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        trailingIcon = {
                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        },
                        onClick = {
                            onSelectSortField(field)
                            sortMenuExpanded = false
                        }
                    )
                }
            }
        }

        Surface(
            onClick = onToggleSortOrder,
            modifier = Modifier
                .height(36.dp)
                .testTag("ai_sort_order_toggle"),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (sortOrder == SortOrder.ASCENDING) {
                        Icons.Default.ArrowUpward
                    } else {
                        Icons.Default.ArrowDownward
                    },
                    contentDescription = "Toggle AI sort order",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = sortOrder.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun MasterAiDisabledFallbackView(
    allFiles: List<PhoneFileItem>,
    selectedAiCategory: FileCategory,
    aiCategoryCounts: Map<FileCategory, Int>,
    selectedAiSortField: AiSortField,
    aiSortOrder: SortOrder,
    onSelectAiCategory: (FileCategory) -> Unit,
    onSelectAiSortField: (AiSortField) -> Unit,
    onToggleAiSortOrder: () -> Unit,
    onEnableMasterAi: () -> Unit,
    onFileClicked: (PhoneFileItem) -> Unit,
    onCopyPath: (String) -> Unit,
    onShareFile: (PhoneFileItem) -> Unit,
    isScanning: Boolean = false,
    hasAllFilesAccess: Boolean = true,
    onRescan: () -> Unit = {},
    onGrantAllFilesAccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var fallbackQuery by remember { mutableStateOf("") }
    val filtered = remember(allFiles, fallbackQuery, selectedAiCategory, selectedAiSortField, aiSortOrder) {
        val categoryFiltered = if (selectedAiCategory == FileCategory.ALL) {
            allFiles
        } else {
            allFiles.filter { it.category == selectedAiCategory }
        }
        val q = fallbackQuery.trim().lowercase(Locale.ROOT)
        val matched = if (q.isEmpty()) categoryFiltered.take(300)
        else categoryFiltered.filter { it.name.lowercase(Locale.ROOT).contains(q) }
        val comparator: Comparator<PhoneFileItem> = when (selectedAiSortField) {
            AiSortField.RELEVANCE, AiSortField.MODIFIED_DATE ->
                compareBy<PhoneFileItem> { it.modifiedAtMillis }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            AiSortField.NAME ->
                compareBy<PhoneFileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            AiSortField.SIZE ->
                compareBy<PhoneFileItem> { it.sizeBytes }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            AiSortField.CREATED_DATE ->
                compareBy<PhoneFileItem> { it.createdAtMillis }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            AiSortField.EXTENSION ->
                compareBy<PhoneFileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.extension }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        if (aiSortOrder == SortOrder.ASCENDING) matched.sortedWith(comparator)
        else matched.sortedWith(comparator.reversed())
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("ai_kill_switch_fallback_view"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Master AI Kill Switch Active",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        IconButton(
                            onClick = onRescan,
                            enabled = !isScanning,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .testTag("ai_fallback_rescan_button")
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Sync & rescan phone files",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "All background OCR, vector embedding, and LLM inference are disabled to conserve battery. Gracefully degraded to lightweight filename-only search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = onEnableMasterAi,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reenable_master_ai_button")
                    ) {
                        Text("Re-Enable On-Device AI Pipeline")
                    }
                }
            }
        }

        if (!hasAllFilesAccess) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = "Full Storage Scan Required",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Allow access to scan all recent photos, PDFs & files.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            onClick = onGrantAllFilesAccess,
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Text(
                                text = "Grant Access",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        item {
            AiFileTypeDropdownBar(
                selectedCategory = selectedAiCategory,
                categoryCounts = aiCategoryCounts,
                onSelectCategory = onSelectAiCategory
            )
        }

        item {
            OutlinedTextField(
                value = fallbackQuery,
                onValueChange = { fallbackQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Lightweight filename-only search…") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
            )
        }

        item {
            AiSortControlBar(
                selectedSortField = selectedAiSortField,
                sortOrder = aiSortOrder,
                onSelectSortField = onSelectAiSortField,
                onToggleSortOrder = onToggleAiSortOrder
            )
        }

        items(filtered, key = { "fb_${it.id}" }) { file ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFileClicked(file) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${file.formattedSize()} • ${file.fullPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(onClick = { onCopyPath(file.fullPath) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy path", modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { onShareFile(file) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share file", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SemanticSearchInputSection(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val sampleQueries = listOf(
        "recent phone images & photos",
        "receipt for monitor or cloud hosting",
        "flight or train pass to Tokyo",
        "photo of sunset or beach",
        "software architecture or Linux books",
        "tax return transcript"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("semantic_search_input"),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            placeholder = {
                Text(
                    text = "Describe content (e.g. 'receipt for monitor', 'photo of sunset')…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            sampleQueries.forEach { preset ->
                Surface(
                    onClick = { onQueryChange(preset) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = preset,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SemanticResultCard(
    result: SemanticSearchResult,
    activeSortField: AiSortField,
    appliedRename: String?,
    onFileClicked: (PhoneFileItem) -> Unit,
    onCopyPath: (String) -> Unit,
    onShareFile: (PhoneFileItem) -> Unit
) {
    val file = result.file
    val similarityPct = (result.cosineSimilarity * 100).toInt().coerceIn(1, 99)
    val dateLabel = if (activeSortField == AiSortField.CREATED_DATE) {
        "Created ${file.formattedShortDate(file.createdAtMillis)}"
    } else {
        "Modified ${file.formattedShortDate(file.modifiedAtMillis)}"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onFileClicked(file) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (file.category == FileCategory.IMAGE || file.category == FileCategory.VIDEO) {
                    MediaThumbnailBox(file = file)
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = categoryIcon(file.category),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appliedRename ?: file.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${result.lensTag} • ${file.formattedSize()} • $dateLabel • ${file.storageLocation.displayName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Text(
                        text = "$similarityPct% Cosine",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Matched Sentence Chunk Snippet
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.75f)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Matched Vector Chunk #${result.matchedChunkIndex + 1}/${result.totalFileChunks}:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = result.matchedSnippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = file.fullPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row {
                    IconButton(
                        onClick = { onCopyPath(file.fullPath) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy path",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = { onShareFile(file) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share file",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicLensCard(
    lens: DynamicLensGroup,
    onFileClicked: (PhoneFileItem) -> Unit,
    onShareFile: (PhoneFileItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (lens.isUserPreset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (lens.isUserPreset) "PRE-SET LENS (SETTINGS)" else "AUTO-GROUPED BY AI",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (lens.isUserPreset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = lens.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = lens.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "${lens.files.size} files • ${FileFormatters.formatFileSize(lens.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            val previewList = if (expanded) lens.files else lens.files.take(3)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

            previewList.forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFileClicked(file) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${file.storageLocation.displayName} • ${file.fullPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = { onShareFile(file) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (lens.files.size > 3) {
                Text(
                    text = if (expanded) "Show fewer" else "View all ${lens.files.size} files in this Lens",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AutoRenameCard(
    suggestion: RenameSuggestion,
    onApplyToggle: () -> Unit,
    onFileClicked: (PhoneFileItem) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onFileClicked(suggestion.file) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = 1.dp,
            color = if (suggestion.isApplied) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Original: ${suggestion.originalName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "→ ${suggestion.suggestedName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (suggestion.isApplied) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "${suggestion.confidencePercent}% Conf",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = suggestion.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = suggestion.file.fullPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onApplyToggle,
                    modifier = Modifier.testTag("apply_rename_${suggestion.file.name}")
                ) {
                    Icon(
                        imageVector = if (suggestion.isApplied) Icons.Default.Check else Icons.Default.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (suggestion.isApplied) "Applied" else "Apply Name")
                }
            }
        }
    }
}

@Composable
private fun JunkCandidateCard(
    candidate: JunkCandidate,
    onToggleReview: () -> Unit,
    onFileClicked: (PhoneFileItem) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onFileClicked(candidate.file) },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (candidate.file.category == FileCategory.IMAGE || candidate.file.category == FileCategory.VIDEO) {
                        MediaThumbnailBox(file = candidate.file)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = candidate.file.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${candidate.issueCategory} • Sharpness: ${candidate.sharpnessVariance}/100",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Text(
                    text = candidate.file.formattedSize(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = candidate.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = "Sharpest Kept Version: ${candidate.keptCounterpartName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                FilterChip(
                    selected = candidate.isMarkedForReview,
                    onClick = onToggleReview,
                    label = {
                        Text(
                            if (candidate.isMarkedForReview) {
                                "Flagged as Duplicate (${candidate.file.formattedSize()})"
                            } else {
                                "Flag for Cleanup Review"
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun NaturalLanguageCommandSection(
    commandInput: String,
    plan: NlCommandPlan,
    onUpdateCommand: (String) -> Unit,
    onExecutePlan: () -> Unit,
    onFileClicked: (PhoneFileItem) -> Unit
) {
    val presetCommands = listOf(
        "Move PDFs older than 30 days to Documents/Old",
        "Organize travel PDFs into Documents/Travel_2026",
        "Group images larger than 5 MB into Pictures/HighRes",
        "Backup invoice PDFs to Documents/Tax_Vault"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = commandInput,
            onValueChange = onUpdateCommand,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("nl_command_input"),
            shape = MaterialTheme.shapes.medium,
            placeholder = {
                Text("e.g. Move PDFs older than 6 months to Documents/Old")
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presetCommands.forEach { preset ->
                Surface(
                    onClick = { onUpdateCommand(preset) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Text(
                        text = preset,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Intent Classification: ${plan.intentType}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Extracted Filter: ${plan.extractedCriteria}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Target Destination: ${plan.targetFolder}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = plan.executionSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                plan.matchedFiles.take(5).forEach { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFileClicked(file) }
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = file.formattedSize(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Button(
                    onClick = onExecutePlan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("execute_nl_command_button")
                ) {
                    Text(if (plan.isExecuted) "Virtual Staging Complete ✓" else "Stage Operation Plan (${plan.matchedFiles.size} files)")
                }
            }
        }
    }
}

@Composable
private fun DocumentQaRagSection(
    selectedFile: PhoneFileItem?,
    allFiles: List<PhoneFileItem>,
    messages: List<RagChatMessage>,
    onSelectDocument: (PhoneFileItem) -> Unit,
    onSendQuestion: (String) -> Unit,
    onOpenPdfChat: ((PhoneFileItem) -> Unit)? = null
) {
    var docPickerExpanded by remember { mutableStateOf(false) }
    var questionInput by remember { mutableStateOf("") }
    val docCandidates = remember(allFiles) {
        allFiles.filter {
            it.category == FileCategory.PDF ||
                it.category == FileCategory.DOCUMENT ||
                it.category == FileCategory.CODE_DATA
        }.ifEmpty { allFiles }
    }
    val currentDoc = selectedFile ?: docCandidates.firstOrNull()

    val quickPrompts = listOf(
        "Summarize this document",
        "What dates or amounts are mentioned?",
        "Where is this file stored and what is its exact size?"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Document Picker Trigger
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                onClick = { docPickerExpanded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("rag_document_selector"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ACTIVE RAG DOCUMENT (ON-DEVICE)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = currentDoc?.name ?: "Select a document",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (currentDoc != null) {
                            Text(
                                text = currentDoc.fullPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        text = "Change ▾",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            DropdownMenu(
                expanded = docPickerExpanded,
                onDismissRequest = { docPickerExpanded = false },
                modifier = Modifier.fillMaxWidth(0.92f),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                docCandidates.take(20).forEach { doc ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = doc.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${doc.formattedSize()} • ${doc.storageLocation.displayName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            onSelectDocument(doc)
                            docPickerExpanded = false
                        }
                    )
                }
            }
        }

        // 2. Prominent Query Entry Card at the Top (Always Visible Without Scrolling)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            ),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forum,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "ENTER YOUR QUESTION ABOUT THIS DOCUMENT",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = questionInput,
                        onValueChange = { questionInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("rag_question_input"),
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = "Type your question about ${currentDoc?.name ?: "this document"}…",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            if (questionInput.isNotEmpty()) {
                                IconButton(onClick = { questionInput = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear question"
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                        )
                    )
                    Button(
                        onClick = {
                            if (questionInput.isNotBlank()) {
                                onSendQuestion(questionInput)
                                questionInput = ""
                            }
                        },
                        enabled = questionInput.isNotBlank(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        modifier = Modifier
                            .height(54.dp)
                            .testTag("rag_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Ask document",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Ask",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Quick RAG Question Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickPrompts.forEach { prompt ->
                        Surface(
                            onClick = { onSendQuestion(prompt) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = prompt,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                if (currentDoc != null &&
                    (currentDoc.category == FileCategory.PDF || currentDoc.extension.equals("pdf", ignoreCase = true)) &&
                    onOpenPdfChat != null
                ) {
                    OutlinedButton(
                        onClick = { onOpenPdfChat(currentDoc) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("rag_open_pdf_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Full PDF Chat (Gemma E2B / Gemini)",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 3. Chat Messages History Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "DOCUMENT Q&A CONVERSATION",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                messages.forEach { msg ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (msg.isUser) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (msg.isUser) "YOU" else "ON-DEVICE RAG ENGINE",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (msg.isUser) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                }
                            )
                            Text(
                                text = msg.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            msg.citations.forEach { citation ->
                                Text(
                                    text = "↳ $citation",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
