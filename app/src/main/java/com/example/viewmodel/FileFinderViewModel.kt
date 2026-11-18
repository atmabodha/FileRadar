package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiFeatureTab
import com.example.ai.AiSettingsState
import com.example.ai.AppMainTab
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
    private val sessionTreeUris = mutableListOf<Uri>()
    private var indexingJob: Job? = null

    private val _uiState = MutableStateFlow(FileFinderUiState())
    val uiState: StateFlow<FileFinderUiState> = _uiState.asStateFlow()

    init {
        val fullAccess = repository.hasAllFilesAccess()
        val persisted = repository.getPersistedFolderUris()
        val hw = aiEngine.readHardwareTelemetry()
        _uiState.update {
            it.copy(
                hasAllFilesAccess = fullAccess,
                authorizedFolderCount = persisted.size,
                includeSampleIndex = !fullAccess && persisted.isEmpty(),
                hardwareTelemetry = hw
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
        viewModelScope.launch {
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
            _uiState.update { current ->
                val updated = recomputeState(
                    current.copy(
                        allFiles = scanned,
                        hasAllFilesAccess = fullAccess,
                        authorizedFolderCount = persisted.size,
                        isScanning = false
                    )
                )
                recomputeAiOutputs(updated)
            }
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
            val files = _uiState.value.allFiles
            val chunkSize = _uiState.value.aiSettings.chunkSizeTokens
            val overlap = _uiState.value.aiSettings.chunkOverlapTokens
            val delayMs = _uiState.value.aiSettings.thermalPacingDelayMs

            _uiState.update {
                it.copy(
                    aiSettings = it.aiSettings.copy(
                        isIndexingPipelineRunning = true,
                        currentPipelineFile = files.firstOrNull()?.name
                    )
                )
            }

            for ((index, file) in files.withIndex()) {
                if (!_uiState.value.aiSettings.masterAiEnabled) break
                aiEngine.indexSingleFile(file, chunkSize, overlap)
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
            _uiState.update { current ->
                val updatedSettings = current.aiSettings.copy(
                    indexedFileCount = aiEngine.getIndexedFileCount(),
                    totalVectorChunks = aiEngine.getTotalIndexedChunks(),
                    isIndexingPipelineRunning = false,
                    currentPipelineFile = null,
                    gemmaModelStatus = GemmaModelStatus.FLUSHED_IDLE
                )
                recomputeAiOutputs(current.copy(aiSettings = updatedSettings))
            }
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

    // ================= AI FEATURE INTERACTIONS =================

    fun updateSemanticQuery(query: String) {
        _uiState.update { current ->
            val results = aiEngine.performSemanticSearch(query, current.allFiles)
            current.copy(
                semanticQuery = query,
                semanticResults = results
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
            val updatedSuggestions = aiEngine.generateRenameSuggestions(current.allFiles, nextMap)
            current.copy(
                appliedRenames = nextMap,
                renameSuggestions = updatedSuggestions
            )
        }
    }

    fun toggleJunkReview(fileId: String) {
        _uiState.update { current ->
            val nextSet = current.markedJunkIds.toMutableSet()
            if (!nextSet.add(fileId)) {
                nextSet.remove(fileId)
            }
            val updatedJunk = aiEngine.analyzeJunkCandidates(current.allFiles, nextSet)
            current.copy(
                markedJunkIds = nextSet,
                junkCandidates = updatedJunk
            )
        }
    }

    fun updateNlCommandInput(command: String) {
        _uiState.update { current ->
            val plan = aiEngine.parseNaturalLanguageCommand(command, current.allFiles, isExecuted = false)
            current.copy(
                nlCommandInput = command,
                nlCommandPlan = plan
            )
        }
    }

    fun executeNlCommandPlan() {
        _uiState.update { current ->
            val executedPlan = aiEngine.parseNaturalLanguageCommand(
                current.nlCommandInput,
                current.allFiles,
                isExecuted = true
            )
            current.copy(nlCommandPlan = executedPlan)
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

    private fun recomputeAiOutputs(state: FileFinderUiState): FileFinderUiState {
        val files = state.allFiles
        val semResults = aiEngine.performSemanticSearch(state.semanticQuery, files)
        val lenses = aiEngine.buildDynamicLenses(files)
        val renames = aiEngine.generateRenameSuggestions(files, state.appliedRenames)
        val junk = aiEngine.analyzeJunkCandidates(files, state.markedJunkIds)
        val nlPlan = aiEngine.parseNaturalLanguageCommand(state.nlCommandInput, files, state.nlCommandPlan.isExecuted)
        val defaultRagDoc = state.selectedRagFile ?: files.firstOrNull { it.category == FileCategory.PDF } ?: files.firstOrNull()
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
