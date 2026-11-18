package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiFeatureTab
import com.example.ai.AiSettingsState
import com.example.ai.AiSortField
import com.example.ai.AppMainTab
import com.example.ai.DEFAULT_PRESET_LENS_LABELS
import com.example.ai.DynamicLensGroup
import com.example.ai.GemmaModelStatus
import com.example.ai.HardwareTelemetry
import com.example.ai.JunkCandidate
import com.example.ai.NlCommandPlan
import com.example.ai.OnDeviceAiEngine
import com.example.ai.RagChatMessage
import com.example.ai.RenameSuggestion
import com.example.ai.SemanticSearchResult
import com.example.data.PhoneFileScannerRepository
import com.example.model.FileCategory
import com.example.model.PhoneFileItem
import com.example.model.SortField
import com.example.model.SortOrder
import com.example.model.StorageLocation
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class FileFinderUiState(
    val selectedMainTab: AppMainTab = AppMainTab.STANDARD,
    val allFiles: List<PhoneFileItem> = emptyList(),
    val matchingFiles: List<PhoneFileItem> = emptyList(),
    val selectedCategory: FileCategory = FileCategory.ALL,
    val selectedStorage: StorageLocation = StorageLocation.ALL,
    val selectedSortField: SortField = SortField.MODIFIED_DATE,
    val sortOrder: SortOrder = SortOrder.DESCENDING,
    val searchQuery: String = "",
    val isScanning: Boolean = true,
    val selectedInspectorFile: PhoneFileItem? = null,
    val activeMediaPreviewFile: PhoneFileItem? = null,
    val activePdfReaderFile: PhoneFileItem? = null,
    val activePdfChatFile: PhoneFileItem? = null,
    val categoryCounts: Map<FileCategory, Int> = emptyMap(),
    val storageCounts: Map<StorageLocation, Int> = emptyMap(),
    val totalMatchingBytes: Long = 0L,
    val hasAllFilesAccess: Boolean = false,
    val authorizedFolderCount: Int = 0,
    val includeSampleIndex: Boolean = true,
    // Separate On-Device AI & Settings State
    val aiSettings: AiSettingsState = AiSettingsState(),
    val hardwareTelemetry: HardwareTelemetry = HardwareTelemetry(),
    val selectedAiFeatureTab: AiFeatureTab = AiFeatureTab.SEMANTIC_SEARCH,
    val selectedAiCategory: FileCategory = FileCategory.ALL,
    val aiCategoryCounts: Map<FileCategory, Int> = emptyMap(),
    val selectedAiSortField: AiSortField = AiSortField.RELEVANCE,
    val aiSortOrder: SortOrder = SortOrder.DESCENDING,
    val semanticQuery: String = "",
    val semanticResults: List<SemanticSearchResult> = emptyList(),
    val dynamicLenses: List<DynamicLensGroup> = emptyList(),
    val renameSuggestions: List<RenameSuggestion> = emptyList(),
    val appliedRenames: Map<String, String> = emptyMap(),
    val junkCandidates: List<JunkCandidate> = emptyList(),
    val markedJunkIds: Set<String> = emptySet(),
    val nlCommandInput: String = "Move PDFs older than 30 days to Documents/Old",
    val nlCommandPlan: NlCommandPlan = NlCommandPlan(
        rawCommand = "Move PDFs older than 30 days to Documents/Old",
        intentType = "MOVE_FILES_INTENT",
        extractedCriteria = "Type = PDF Documents • Age > 30 days",
        targetFolder = "/storage/emulated/0/Documents/Old",
        matchedFiles = emptyList(),
        executionSummary = "Ready"
    ),
    val selectedRagFile: PhoneFileItem? = null,
    val ragMessages: List<RagChatMessage> = emptyList()
)

class FileFinderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PhoneFileScannerRepository(application.applicationContext)
    private val aiEngine = OnDeviceAiEngine(application.applicationContext)
    private val prefs = application.applicationContext.getSharedPreferences("file_radar_ai_prefs", Context.MODE_PRIVATE)
    private val sessionTreeUris = mutableListOf<Uri>()
    private var scanJob: Job? = null
    private var indexingJob: Job? = null

    private val _uiState = MutableStateFlow(FileFinderUiState())
    val uiState: StateFlow<FileFinderUiState> = _uiState.asStateFlow()

    init {
        val fullAccess = repository.hasAllFilesAccess()
        val persisted = repository.getPersistedFolderUris()
        val hw = aiEngine.readHardwareTelemetry()
        val savedPresetsRaw = prefs.getString("preset_lens_labels", null)
        val initialPresets = if (savedPresetsRaw != null) {
            savedPresetsRaw.split("||").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            DEFAULT_PRESET_LENS_LABELS
        }
        _uiState.update {
            it.copy(
                hasAllFilesAccess = fullAccess,
                authorizedFolderCount = persisted.size,
                includeSampleIndex = !fullAccess && persisted.isEmpty(),
                hardwareTelemetry = hw,
                aiSettings = it.aiSettings.copy(presetLensLabels = initialPresets)
            )
        }
        rescanStorage()
    }

    fun selectMainTab(tab: AppMainTab) {
        _uiState.update { it.copy(selectedMainTab = tab) }
    }

    fun selectAiFeatureTab(tab: AiFeatureTab) {
        _uiState.update { it.copy(selectedAiFeatureTab = tab) }
    }

    fun onAppResumed() {
        val fullAccessNow = repository.hasAllFilesAccess()
        val previousAccess = _uiState.value.hasAllFilesAccess
        if (fullAccessNow != previousAccess) {
            _uiState.update {
                it.copy(
                    hasAllFilesAccess = fullAccessNow,
                    includeSampleIndex = if (fullAccessNow) false else it.includeSampleIndex
                )
            }
            rescanStorage()
        }
    }

    fun onFolderTreeAuthorized(treeUri: Uri?) {
        if (treeUri == null) return
        repository.persistFolderTreeAccess(treeUri)
        if (treeUri !in sessionTreeUris) {
            sessionTreeUris.add(treeUri)
        }
        val totalFolders = (repository.getPersistedFolderUris() + sessionTreeUris).distinct().size
        _uiState.update {
            it.copy(
                authorizedFolderCount = totalFolders,
                includeSampleIndex = false
            )
        }
        rescanStorage()
    }

    fun toggleIncludeSampleIndex() {
        _uiState.update { it.copy(includeSampleIndex = !it.includeSampleIndex) }
        rescanStorage()
    }

    fun rescanStorage() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.Default) {
            val fullAccess = repository.hasAllFilesAccess()
            val hw = aiEngine.readHardwareTelemetry()
            _uiState.update {
                it.copy(
                    isScanning = true,
                    hasAllFilesAccess = fullAccess,
                    hardwareTelemetry = hw
                )
            }
            val currentIncludeSample = _uiState.value.includeSampleIndex
            val persisted = (repository.getPersistedFolderUris() + sessionTreeUris).distinct()
            val scanned = repository.scanPhoneFiles(
                additionalTreeUris = persisted,
                includeSampleIndex = currentIncludeSample
            )

            // 1. Immediately compute and publish Standard screen state off the Main Thread so the UI renders right away
            val standardUpdated = recomputeState(
                _uiState.value.copy(
                    allFiles = scanned,
                    hasAllFilesAccess = fullAccess,
                    authorizedFolderCount = persisted.size,
                    isScanning = false
                )
            )
            _uiState.value = standardUpdated

            // 2. Compute AI Search & Dynamic Lenses state on Default background dispatcher
            val aiUpdated = recomputeAiOutputs(_uiState.value)
            _uiState.value = aiUpdated

            if (_uiState.value.aiSettings.masterAiEnabled) {
                startBackgroundAiIndexing(paced = false)
            }
        }
    }

    fun startBackgroundAiIndexing(paced: Boolean = true) {
        indexingJob?.cancel()
        val stateSnap = _uiState.value
        if (!stateSnap.aiSettings.masterAiEnabled) return

        indexingJob = viewModelScope.launch(Dispatchers.Default) {
            val allFiles = _uiState.value.allFiles
            // Prioritize PDFs, Documents, and Images, capped at 60 files so background indexing finishes rapidly
            val filesToIndex = allFiles
                .sortedBy { file ->
                    when (file.category) {
                        FileCategory.PDF -> 0
                        FileCategory.DOCUMENT, FileCategory.CODE_DATA -> 1
                        FileCategory.IMAGE -> 2
                        else -> 3
                    }
                }
                .take(60)

            val chunkSize = _uiState.value.aiSettings.chunkSizeTokens
            val overlap = _uiState.value.aiSettings.chunkOverlapTokens
            val delayMs = _uiState.value.aiSettings.thermalPacingDelayMs

            _uiState.update {
                it.copy(
                    aiSettings = it.aiSettings.copy(
                        isIndexingPipelineRunning = true,
                        currentPipelineFile = filesToIndex.firstOrNull()?.name
                    )
                )
            }

            for ((index, file) in filesToIndex.withIndex()) {
                if (!_uiState.value.aiSettings.masterAiEnabled) break
                aiEngine.indexSingleFile(file, chunkSize, overlap)
                yield()
                if (paced && index < 6) {
                    _uiState.update {
                        it.copy(
                            aiSettings = it.aiSettings.copy(
                                indexedFileCount = aiEngine.getIndexedFileCount(),
                                totalVectorChunks = aiEngine.getTotalIndexedChunks(),
                                currentPipelineFile = file.name
                            )
                        )
                    }
                    delay((delayMs / 4).coerceIn(40L, 250L))
                }
            }

            // Flush model from RAM upon queue completion per Section 5 of Architecture Plan
            val currentSnap = _uiState.value
            val updatedSettings = currentSnap.aiSettings.copy(
                indexedFileCount = aiEngine.getIndexedFileCount(),
                totalVectorChunks = aiEngine.getTotalIndexedChunks(),
                isIndexingPipelineRunning = false,
                currentPipelineFile = null,
                gemmaModelStatus = GemmaModelStatus.FLUSHED_IDLE
            )
            val finalState = recomputeAiOutputs(currentSnap.copy(aiSettings = updatedSettings))
            _uiState.value = finalState
        }
    }

    // ================= AI SETTINGS & KILL SWITCH HANDLERS =================

    fun setMasterAiEnabled(enabled: Boolean) {
        if (!enabled) {
            indexingJob?.cancel()
            aiEngine.clearVectorStore()
            _uiState.update { current ->
                current.copy(
                    aiSettings = current.aiSettings.copy(
                        masterAiEnabled = false,
                        isIndexingPipelineRunning = false,
                        currentPipelineFile = null,
                        indexedFileCount = 0,
                        totalVectorChunks = 0,
                        gemmaModelStatus = GemmaModelStatus.FLUSHED_IDLE
                    )
                )
            }
        } else {
            _uiState.update { current ->
                current.copy(
                    aiSettings = current.aiSettings.copy(
                        masterAiEnabled = true,
                        gemmaModelStatus = GemmaModelStatus.READY_ON_DISK
                    )
                )
            }
            startBackgroundAiIndexing(paced = false)
        }
    }

    fun setRequiresBatteryNotLow(enabled: Boolean) {
        _uiState.update {
            it.copy(aiSettings = it.aiSettings.copy(requiresBatteryNotLow = enabled))
        }
    }

    fun setRequiresChargingForBacklog(enabled: Boolean) {
        _uiState.update {
            it.copy(aiSettings = it.aiSettings.copy(requiresChargingForBacklog = enabled))
        }
    }

    fun setThermalPacingDelayMs(delayMs: Long) {
        _uiState.update {
            it.copy(aiSettings = it.aiSettings.copy(thermalPacingDelayMs = delayMs))
        }
    }

    fun setChunkSizeTokens(tokens: Int) {
        _uiState.update {
            it.copy(aiSettings = it.aiSettings.copy(chunkSizeTokens = tokens))
        }
        startBackgroundAiIndexing(paced = false)
    }

    fun warmUpGemmaInBackgroundIo() {
        _uiState.update {
            it.copy(
                aiSettings = it.aiSettings.copy(
                    gemmaModelStatus = GemmaModelStatus.LOADED_IN_RAM
                )
            )
        }
    }

    fun flushModelFromRam() {
        _uiState.update {
            it.copy(
                aiSettings = it.aiSettings.copy(
                    gemmaModelStatus = GemmaModelStatus.FLUSHED_IDLE
                )
            )
        }
    }

    fun addPresetLensLabel(rawLabel: String) {
        val clean = rawLabel.trim()
        if (clean.isEmpty()) return
        _uiState.update { current ->
            val existing = current.aiSettings.presetLensLabels
            if (existing.any { it.equals(clean, ignoreCase = true) }) {
                return@update current
            }
            val updatedList = existing + clean
            savePresetLensLabels(updatedList)
            recomputeAiOutputs(
                current.copy(
                    aiSettings = current.aiSettings.copy(presetLensLabels = updatedList)
                )
            )
        }
    }

    fun removePresetLensLabel(label: String) {
        _uiState.update { current ->
            val updatedList = current.aiSettings.presetLensLabels.filterNot {
                it.equals(label, ignoreCase = true)
            }
            savePresetLensLabels(updatedList)
            recomputeAiOutputs(
                current.copy(
                    aiSettings = current.aiSettings.copy(presetLensLabels = updatedList)
                )
            )
        }
    }

    fun resetPresetLensLabels() {
        _uiState.update { current ->
            val defaults = DEFAULT_PRESET_LENS_LABELS
            savePresetLensLabels(defaults)
            recomputeAiOutputs(
                current.copy(
                    aiSettings = current.aiSettings.copy(presetLensLabels = defaults)
                )
            )
        }
    }

    private fun savePresetLensLabels(labels: List<String>) {
        prefs.edit().putString("preset_lens_labels", labels.joinToString("||")).apply()
    }

    // ================= AI FEATURE INTERACTIONS =================

    fun selectAiCategory(category: FileCategory) {
        _uiState.update { current ->
            recomputeAiOutputs(current.copy(selectedAiCategory = category))
        }
    }

    fun selectAiSortField(field: AiSortField) {
        _uiState.update { current ->
            val nextOrder = if (current.selectedAiSortField == field) {
                current.aiSortOrder.toggle()
            } else {
                when (field) {
                    AiSortField.NAME, AiSortField.EXTENSION -> SortOrder.ASCENDING
                    AiSortField.RELEVANCE,
                    AiSortField.SIZE,
                    AiSortField.CREATED_DATE,
                    AiSortField.MODIFIED_DATE -> SortOrder.DESCENDING
                }
            }
            recomputeAiOutputs(
                current.copy(
                    selectedAiSortField = field,
                    aiSortOrder = nextOrder
                )
            )
        }
    }

    fun toggleAiSortOrder() {
        _uiState.update { current ->
            recomputeAiOutputs(
                current.copy(aiSortOrder = current.aiSortOrder.toggle())
            )
        }
    }

    fun updateSemanticQuery(query: String) {
        _uiState.update { current ->
            val filteredFiles = filterFilesByAiCategory(current.allFiles, current.selectedAiCategory)
            val rawResults = aiEngine.performSemanticSearch(
                query = query,
                files = filteredFiles,
                presetLabels = current.aiSettings.presetLensLabels
            )
            val sortedResults = sortSemanticResults(
                results = rawResults,
                appliedRenames = current.appliedRenames,
                sortField = current.selectedAiSortField,
                sortOrder = current.aiSortOrder
            )
            current.copy(
                semanticQuery = query,
                semanticResults = sortedResults
            )
        }
    }

    fun toggleApplyRename(suggestion: RenameSuggestion) {
        _uiState.update { current ->
            val nextMap = current.appliedRenames.toMutableMap()
            if (nextMap.containsKey(suggestion.file.id)) {
                nextMap.remove(suggestion.file.id)
            } else {
                nextMap[suggestion.file.id] = suggestion.suggestedName
            }
            val filteredFiles = filterFilesByAiCategory(current.allFiles, current.selectedAiCategory)
            val updatedSuggestions = sortRenameSuggestions(
                suggestions = aiEngine.generateRenameSuggestions(filteredFiles, nextMap),
                sortField = current.selectedAiSortField,
                sortOrder = current.aiSortOrder
            )
            val sortedSemantic = sortSemanticResults(
                results = current.semanticResults,
                appliedRenames = nextMap,
                sortField = current.selectedAiSortField,
                sortOrder = current.aiSortOrder
            )
            current.copy(
                appliedRenames = nextMap,
                renameSuggestions = updatedSuggestions,
                semanticResults = sortedSemantic
            )
        }
    }

    fun toggleJunkReview(fileId: String) {
        _uiState.update { current ->
            val nextSet = current.markedJunkIds.toMutableSet()
            if (!nextSet.add(fileId)) {
                nextSet.remove(fileId)
            }
            val filteredFiles = filterFilesByAiCategory(current.allFiles, current.selectedAiCategory)
            val updatedJunk = sortJunkCandidates(
                candidates = aiEngine.analyzeJunkCandidates(filteredFiles, nextSet),
                sortField = current.selectedAiSortField,
                sortOrder = current.aiSortOrder
            )
            current.copy(
                markedJunkIds = nextSet,
                junkCandidates = updatedJunk
            )
        }
    }

    fun updateNlCommandInput(command: String) {
        _uiState.update { current ->
            val filteredFiles = filterFilesByAiCategory(current.allFiles, current.selectedAiCategory)
            val rawPlan = aiEngine.parseNaturalLanguageCommand(command, filteredFiles, isExecuted = false)
            val sortedPlan = rawPlan.copy(
                matchedFiles = sortPhoneFileItems(
                    files = rawPlan.matchedFiles,
                    sortField = current.selectedAiSortField,
                    sortOrder = current.aiSortOrder
                )
            )
            current.copy(
                nlCommandInput = command,
                nlCommandPlan = sortedPlan
            )
        }
    }

    fun executeNlCommandPlan() {
        _uiState.update { current ->
            val filteredFiles = filterFilesByAiCategory(current.allFiles, current.selectedAiCategory)
            val executedPlan = aiEngine.parseNaturalLanguageCommand(
                current.nlCommandInput,
                filteredFiles,
                isExecuted = true
            )
            val sortedPlan = executedPlan.copy(
                matchedFiles = sortPhoneFileItems(
                    files = executedPlan.matchedFiles,
                    sortField = current.selectedAiSortField,
                    sortOrder = current.aiSortOrder
                )
            )
            current.copy(nlCommandPlan = sortedPlan)
        }
    }

    fun selectRagDocument(file: PhoneFileItem) {
        _uiState.update { current ->
            val welcome = RagChatMessage(
                id = "init_${file.id}",
                isUser = false,
                message = "Loaded ${file.name} (${file.formattedSize()}) into local RAG context. Ask any question to retrieve answers from its sentence chunks on-device.",
                citations = emptyList()
            )
            current.copy(
                selectedRagFile = file,
                ragMessages = listOf(welcome)
            )
        }
    }

    fun sendRagQuestion(question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { current ->
            val doc = current.selectedRagFile ?: current.allFiles.firstOrNull() ?: return@update current
            val userMsg = RagChatMessage(
                id = "u_${System.currentTimeMillis()}",
                isUser = true,
                message = trimmed
            )
            val botMsg = aiEngine.answerDocumentQuestion(doc, trimmed)
            current.copy(
                selectedRagFile = doc,
                ragMessages = current.ragMessages + userMsg + botMsg
            )
        }
    }

    // ================= STANDARD SCREEN HANDLERS (UNTOUCHED) =================

    fun selectCategory(category: FileCategory) {
        _uiState.update { current ->
            recomputeState(current.copy(selectedCategory = category))
        }
    }

    fun selectStorageLocation(location: StorageLocation) {
        _uiState.update { current ->
            recomputeState(current.copy(selectedStorage = location))
        }
    }

    fun selectSortField(field: SortField) {
        _uiState.update { current ->
            val nextOrder = if (current.selectedSortField == field) {
                current.sortOrder.toggle()
            } else {
                when (field) {
                    SortField.NAME, SortField.EXTENSION -> SortOrder.ASCENDING
                    SortField.SIZE, SortField.CREATED_DATE, SortField.MODIFIED_DATE -> SortOrder.DESCENDING
                }
            }
            recomputeState(
                current.copy(
                    selectedSortField = field,
                    sortOrder = nextOrder
                )
            )
        }
    }

    fun toggleSortOrder() {
        _uiState.update { current ->
            recomputeState(current.copy(sortOrder = current.sortOrder.toggle()))
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { current ->
            recomputeState(current.copy(searchQuery = query))
        }
    }

    fun resetFilters() {
        _uiState.update { current ->
            recomputeState(
                current.copy(
                    selectedCategory = FileCategory.ALL,
                    selectedStorage = StorageLocation.ALL,
                    selectedSortField = SortField.MODIFIED_DATE,
                    sortOrder = SortOrder.DESCENDING,
                    searchQuery = ""
                )
            )
        }
    }

    fun onFileClicked(file: PhoneFileItem) {
        if (file.category == FileCategory.IMAGE || file.category == FileCategory.VIDEO) {
            _uiState.update { it.copy(activeMediaPreviewFile = file) }
        } else {
            _uiState.update { it.copy(selectedInspectorFile = file) }
        }
    }

    fun closeMediaPreview() {
        _uiState.update { it.copy(activeMediaPreviewFile = null) }
    }

    fun openFileInspector(file: PhoneFileItem) {
        _uiState.update { it.copy(selectedInspectorFile = file) }
    }

    fun closeFileInspector() {
        _uiState.update { it.copy(selectedInspectorFile = null) }
    }

    fun openPdfReader(file: PhoneFileItem) {
        _uiState.update {
            it.copy(
                selectedInspectorFile = null,
                activePdfChatFile = null,
                activePdfReaderFile = file
            )
        }
    }

    fun closePdfReader() {
        _uiState.update { it.copy(activePdfReaderFile = null) }
    }

    fun openPdfChat(file: PhoneFileItem) {
        _uiState.update {
            it.copy(
                selectedInspectorFile = null,
                activePdfReaderFile = null,
                activePdfChatFile = file
            )
        }
    }

    fun closePdfChat() {
        _uiState.update { it.copy(activePdfChatFile = null) }
    }

    private fun filterFilesByAiCategory(
        files: List<PhoneFileItem>,
        category: FileCategory
    ): List<PhoneFileItem> {
        return if (category == FileCategory.ALL) {
            files
        } else {
            files.filter { it.category == category }
        }
    }

    private fun sortPhoneFileItems(
        files: List<PhoneFileItem>,
        sortField: AiSortField,
        sortOrder: SortOrder
    ): List<PhoneFileItem> {
        val comparator: Comparator<PhoneFileItem> = when (sortField) {
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
        return if (sortOrder == SortOrder.ASCENDING) {
            files.sortedWith(comparator)
        } else {
            files.sortedWith(comparator.reversed())
        }
    }

    private fun sortSemanticResults(
        results: List<SemanticSearchResult>,
        appliedRenames: Map<String, String>,
        sortField: AiSortField,
        sortOrder: SortOrder
    ): List<SemanticSearchResult> {
        val comparator: Comparator<SemanticSearchResult> = when (sortField) {
            AiSortField.RELEVANCE ->
                compareBy<SemanticSearchResult> { it.cosineSimilarity }
                    .thenBy { it.file.modifiedAtMillis }
            AiSortField.NAME ->
                compareBy<SemanticSearchResult, String>(String.CASE_INSENSITIVE_ORDER) {
                    appliedRenames[it.file.id] ?: it.file.name
                }
            AiSortField.SIZE ->
                compareBy<SemanticSearchResult> { it.file.sizeBytes }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            AiSortField.CREATED_DATE ->
                compareBy<SemanticSearchResult> { it.file.createdAtMillis }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            AiSortField.MODIFIED_DATE ->
                compareBy<SemanticSearchResult> { it.file.modifiedAtMillis }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            AiSortField.EXTENSION ->
                compareBy<SemanticSearchResult, String>(String.CASE_INSENSITIVE_ORDER) { it.file.extension }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
        }
        return if (sortOrder == SortOrder.ASCENDING) {
            results.sortedWith(comparator)
        } else {
            results.sortedWith(comparator.reversed())
        }
    }

    private fun sortRenameSuggestions(
        suggestions: List<RenameSuggestion>,
        sortField: AiSortField,
        sortOrder: SortOrder
    ): List<RenameSuggestion> {
        val comparator: Comparator<RenameSuggestion> = when (sortField) {
            AiSortField.RELEVANCE ->
                compareBy<RenameSuggestion> { it.confidencePercent }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.suggestedName }
            AiSortField.NAME ->
                compareBy<RenameSuggestion, String>(String.CASE_INSENSITIVE_ORDER) { it.suggestedName }
            AiSortField.SIZE ->
                compareBy<RenameSuggestion> { it.file.sizeBytes }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.originalName }
            AiSortField.CREATED_DATE ->
                compareBy<RenameSuggestion> { it.file.createdAtMillis }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.originalName }
            AiSortField.MODIFIED_DATE ->
                compareBy<RenameSuggestion> { it.file.modifiedAtMillis }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.originalName }
            AiSortField.EXTENSION ->
                compareBy<RenameSuggestion, String>(String.CASE_INSENSITIVE_ORDER) { it.file.extension }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.originalName }
        }
        return if (sortOrder == SortOrder.ASCENDING) {
            suggestions.sortedWith(comparator)
        } else {
            suggestions.sortedWith(comparator.reversed())
        }
    }

    private fun sortJunkCandidates(
        candidates: List<JunkCandidate>,
        sortField: AiSortField,
        sortOrder: SortOrder
    ): List<JunkCandidate> {
        val comparator: Comparator<JunkCandidate> = when (sortField) {
            AiSortField.RELEVANCE ->
                compareBy<JunkCandidate> { 100 - it.sharpnessVariance }
                    .thenBy { it.file.sizeBytes }
            AiSortField.NAME ->
                compareBy<JunkCandidate, String>(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            AiSortField.SIZE ->
                compareBy<JunkCandidate> { it.file.sizeBytes }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            AiSortField.CREATED_DATE ->
                compareBy<JunkCandidate> { it.file.createdAtMillis }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            AiSortField.MODIFIED_DATE ->
                compareBy<JunkCandidate> { it.file.modifiedAtMillis }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
            AiSortField.EXTENSION ->
                compareBy<JunkCandidate, String>(String.CASE_INSENSITIVE_ORDER) { it.file.extension }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.file.name }
        }
        return if (sortOrder == SortOrder.ASCENDING) {
            candidates.sortedWith(comparator)
        } else {
            candidates.sortedWith(comparator.reversed())
        }
    }

    private fun recomputeAiOutputs(state: FileFinderUiState): FileFinderUiState {
        val allFiles = state.allFiles
        val aiCategoryCounts = FileCategory.entries.associateWith { cat ->
            if (cat == FileCategory.ALL) {
                allFiles.size
            } else {
                allFiles.count { it.category == cat }
            }
        }
        val filteredFiles = filterFilesByAiCategory(allFiles, state.selectedAiCategory)
        val sortField = state.selectedAiSortField
        val sortOrder = state.aiSortOrder

        val semResults = sortSemanticResults(
            results = aiEngine.performSemanticSearch(
                query = state.semanticQuery,
                files = filteredFiles,
                presetLabels = state.aiSettings.presetLensLabels
            ),
            appliedRenames = state.appliedRenames,
            sortField = sortField,
            sortOrder = sortOrder
        )

        val lenses = aiEngine.buildDynamicLenses(
            files = filteredFiles,
            presetLabels = state.aiSettings.presetLensLabels
        ).map { group ->
            group.copy(
                files = sortPhoneFileItems(group.files, sortField, sortOrder)
            )
        }

        val renames = sortRenameSuggestions(
            suggestions = aiEngine.generateRenameSuggestions(filteredFiles, state.appliedRenames),
            sortField = sortField,
            sortOrder = sortOrder
        )

        val junk = sortJunkCandidates(
            candidates = aiEngine.analyzeJunkCandidates(filteredFiles, state.markedJunkIds),
            sortField = sortField,
            sortOrder = sortOrder
        )

        val rawNlPlan = aiEngine.parseNaturalLanguageCommand(state.nlCommandInput, filteredFiles, state.nlCommandPlan.isExecuted)
        val nlPlan = rawNlPlan.copy(
            matchedFiles = sortPhoneFileItems(rawNlPlan.matchedFiles, sortField, sortOrder)
        )

        val defaultRagDoc = state.selectedRagFile?.takeIf {
            state.selectedAiCategory == FileCategory.ALL || it.category == state.selectedAiCategory
        } ?: filteredFiles.firstOrNull { it.category == FileCategory.PDF } ?: filteredFiles.firstOrNull()

        val initialRagMessages = state.ragMessages.ifEmpty {
            if (defaultRagDoc != null) {
                listOf(
                    RagChatMessage(
                        id = "welcome_rag",
                        isUser = false,
                        message = "Ready for On-Device Document Q&A (RAG) on ${defaultRagDoc.name}. Select a prompt chip above or ask a question below to query its indexed sentence chunks locally.",
                        citations = emptyList()
                    )
                )
            } else emptyList()
        }

        return state.copy(
            aiCategoryCounts = aiCategoryCounts,
            semanticResults = semResults,
            dynamicLenses = lenses,
            renameSuggestions = renames,
            junkCandidates = junk,
            nlCommandPlan = nlPlan,
            selectedRagFile = defaultRagDoc,
            ragMessages = initialRagMessages
        )
    }

    private fun recomputeState(state: FileFinderUiState): FileFinderUiState {
        val queryTrimmed = state.searchQuery.trim().lowercase(Locale.ROOT)

        val filteredByCategoryAndQuery = state.allFiles.filter { file ->
            val matchesCategory = state.selectedCategory == FileCategory.ALL || file.category == state.selectedCategory
            val matchesQuery = queryTrimmed.isEmpty() ||
                file.name.lowercase(Locale.ROOT).contains(queryTrimmed) ||
                file.fullPath.lowercase(Locale.ROOT).contains(queryTrimmed) ||
                file.extension.lowercase(Locale.ROOT).contains(queryTrimmed)
            matchesCategory && matchesQuery
        }

        val storageCounts = mapOf(
            StorageLocation.ALL to filteredByCategoryAndQuery.size,
            StorageLocation.INTERNAL to filteredByCategoryAndQuery.count { it.storageLocation == StorageLocation.INTERNAL },
            StorageLocation.SD_CARD to filteredByCategoryAndQuery.count { it.storageLocation == StorageLocation.SD_CARD }
        )

        val filteredByStorageAndQuery = state.allFiles.filter { file ->
            val matchesStorage = state.selectedStorage == StorageLocation.ALL || file.storageLocation == state.selectedStorage
            val matchesQuery = queryTrimmed.isEmpty() ||
                file.name.lowercase(Locale.ROOT).contains(queryTrimmed) ||
                file.fullPath.lowercase(Locale.ROOT).contains(queryTrimmed) ||
                file.extension.lowercase(Locale.ROOT).contains(queryTrimmed)
            matchesStorage && matchesQuery
        }

        val categoryCounts = FileCategory.entries.associateWith { cat ->
            if (cat == FileCategory.ALL) {
                filteredByStorageAndQuery.size
            } else {
                filteredByStorageAndQuery.count { it.category == cat }
            }
        }

        val matching = filteredByStorageAndQuery.filter { file ->
            state.selectedCategory == FileCategory.ALL || file.category == state.selectedCategory
        }

        val comparator: Comparator<PhoneFileItem> = when (state.selectedSortField) {
            SortField.NAME -> compareBy<PhoneFileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.SIZE -> compareBy<PhoneFileItem> { it.sizeBytes }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.CREATED_DATE -> compareBy<PhoneFileItem> { it.createdAtMillis }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.MODIFIED_DATE -> compareBy<PhoneFileItem> { it.modifiedAtMillis }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.EXTENSION -> compareBy<PhoneFileItem, String>(String.CASE_INSENSITIVE_ORDER) { it.extension }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }

        val sorted = if (state.sortOrder == SortOrder.ASCENDING) {
            matching.sortedWith(comparator)
        } else {
            matching.sortedWith(comparator.reversed())
        }

        val totalBytes = sorted.sumOf { it.sizeBytes }

        return state.copy(
            matchingFiles = sorted,
            categoryCounts = categoryCounts,
            storageCounts = storageCounts,
            totalMatchingBytes = totalBytes
        )
    }
}
