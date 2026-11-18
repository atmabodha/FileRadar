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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    modifier: Modifier = Modifier
) {
    // Graceful degradation when Master AI Kill Switch is OFF
    if (!aiSettings.masterAiEnabled) {
        MasterAiDisabledFallbackView(
            allFiles = allFiles,
            onEnableMasterAi = onEnableMasterAi,
            onFileClicked = onFileClicked,
            onCopyPath = onCopyPath,
            onShareFile = onShareFile,
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
        // 1. On-Device AI Status Header + Feature Mode Selector
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
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Text(
                            text = "${aiSettings.totalVectorChunks} vector chunks (${aiSettings.indexedFileCount} files) • RAM ${hardware.availableRamGb}/${hardware.totalRamGb} GB",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (aiSettings.isIndexingPipelineRunning) {
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
                    DocumentQaRagSection(
                        selectedFile = selectedRagFile,
                        allFiles = allFiles,
                        messages = ragMessages,
                        onSelectDocument = onSelectRagDocument,
                        onSendQuestion = onSendRagQuestion
                    )
                }
            }
        }
    }
}

@Composable
private fun MasterAiDisabledFallbackView(
    allFiles: List<PhoneFileItem>,
    onEnableMasterAi: () -> Unit,
    onFileClicked: (PhoneFileItem) -> Unit,
    onCopyPath: (String) -> Unit,
    onShareFile: (PhoneFileItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var fallbackQuery by remember { mutableStateOf("") }
    val filtered = remember(allFiles, fallbackQuery) {
        val q = fallbackQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) allFiles.take(12)
        else allFiles.filter { it.name.lowercase(Locale.ROOT).contains(q) }
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                            text = file.fullPath,
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
    appliedRename: String?,
    onFileClicked: (PhoneFileItem) -> Unit,
    onCopyPath: (String) -> Unit,
    onShareFile: (PhoneFileItem) -> Unit
) {
    val file = result.file
    val similarityPct = (result.cosineSimilarity * 100).toInt().coerceIn(1, 99)

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
                        text = "${result.lensTag} • ${file.formattedSize()} • ${file.storageLocation.displayName}",
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
                Column(modifier = Modifier.weight(1f)) {
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
    onSendQuestion: (String) -> Unit
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
        // Document Picker Trigger
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
                    color = MaterialTheme.colorScheme.surfaceVariant,
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

        // Chat Messages List
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

                // Input Row
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
                        placeholder = { Text("Ask about this document locally…") }
                    )
                    IconButton(
                        onClick = {
                            if (questionInput.isNotBlank()) {
                                onSendQuestion(questionInput)
                                questionInput = ""
                            }
                        },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag("rag_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Ask document",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}
