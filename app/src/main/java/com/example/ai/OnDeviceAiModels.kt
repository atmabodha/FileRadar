package com.example.ai

import com.example.model.PhoneFileItem

enum class AppMainTab(val label: String) {
    STANDARD("Standard"),
    AI_SEARCH("AI Search"),
    SETTINGS("Settings")
}

enum class AiFeatureTab(val label: String, val subtitle: String) {
    SEMANTIC_SEARCH("Semantic", "Natural language vector search across file contents"),
    DYNAMIC_LENSES("Lenses", "Smart context collections without duplicating files"),
    AUTO_RENAME("Auto-Rename", "Content-aware structured filenames for generic files"),
    JUNK_CLEANUP("Cleanup", "Detect blurry photos, burst shots & near-duplicates"),
    NL_COMMANDS("NL Ops", "Plain-English file organization & intent mapping"),
    DOCUMENT_QA("Doc Q&A", "On-device RAG chat with local PDFs & documents")
}

enum class AiSortField(
    val displayName: String,
    val shortLabel: String,
    val description: String
) {
    RELEVANCE("AI Relevance (Cosine)", "Relevance", "Order by vector cosine similarity or AI confidence score"),
    NAME("File Name", "Name", "Alphabetical order by file name"),
    SIZE("File Size", "Size", "Order by file size in bytes"),
    CREATED_DATE("Creation Date", "Created", "Order by date the file was created"),
    MODIFIED_DATE("Modification Date", "Modified", "Order by date the file was last modified"),
    EXTENSION("File Type / Extension", "Ext", "Order by file format extension (.pdf, .jpg, etc.)")
}

enum class GemmaModelStatus(val displayTitle: String, val detailText: String) {
    NOT_DOWNLOADED(
        "Not Downloaded (Tier-1 Neural Vector Engine Active)",
        "Gemma E2B 4-bit (.bin, 1.32 GB) can be downloaded over Wi-Fi."
    ),
    DOWNLOADING(
        "Downloading Gemma E2B (4-bit Quantized) Over Wi-Fi…",
        "Storing model weights in internal storage."
    ),
    READY_ON_DISK(
        "Downloaded on Internal Storage (Unloaded from RAM)",
        "1.32 GB 4-bit model ready. Loads on background IO thread only during inference."
    ),
    LOADED_IN_RAM(
        "Loaded in Background IO Memory",
        "MediaPipe LlmInference active. Auto-flushes when queue completes."
    ),
    FLUSHED_IDLE(
        "Flushed from RAM (Idle Zero-Memory State)",
        "Engine explicitly closed and flushed to prevent OS resource termination."
    )
}

data class HardwareTelemetry(
    val totalRamGb: Double = 8.0,
    val availableRamGb: Double = 4.2,
    val meets8GbTarget: Boolean = true,
    val batteryPercent: Int = 85,
    val isCharging: Boolean = true,
    val isBatteryNotLow: Boolean = true
)

val DEFAULT_PRESET_LENS_LABELS: List<String> = listOf(
    "Blood Report",
    "Bank Records",
    "Tax & Invoices",
    "Travel & Boarding Passes"
)

data class AiSettingsState(
    val masterAiEnabled: Boolean = true,
    val requiresBatteryNotLow: Boolean = true,
    val requiresChargingForBacklog: Boolean = false,
    val thermalPacingDelayMs: Long = 500L,
    val chunkSizeTokens: Int = 256,
    val chunkOverlapTokens: Int = 30,
    val gemmaModelStatus: GemmaModelStatus = GemmaModelStatus.READY_ON_DISK,
    val downloadProgress: Float = 1.0f,
    val indexedFileCount: Int = 0,
    val totalVectorChunks: Int = 0,
    val isIndexingPipelineRunning: Boolean = false,
    val currentPipelineFile: String? = null,
    val presetLensLabels: List<String> = DEFAULT_PRESET_LENS_LABELS
)

data class DocumentChunk(
    val fileId: String,
    val fileName: String,
    val chunkIndex: Int,
    val text: String,
    val estimatedTokens: Int,
    val embedding: FloatArray
)

data class SemanticSearchResult(
    val file: PhoneFileItem,
    val cosineSimilarity: Float,
    val matchedSnippet: String,
    val matchedChunkIndex: Int,
    val totalFileChunks: Int,
    val lensTag: String
)

data class DynamicLensGroup(
    val id: String,
    val title: String,
    val description: String,
    val files: List<PhoneFileItem>,
    val totalBytes: Long,
    val isUserPreset: Boolean = false
)

data class RenameSuggestion(
    val file: PhoneFileItem,
    val originalName: String,
    val suggestedName: String,
    val reason: String,
    val confidencePercent: Int,
    val isApplied: Boolean = false
)

data class JunkCandidate(
    val file: PhoneFileItem,
    val issueCategory: String,
    val sharpnessVariance: Int,
    val keptCounterpartName: String,
    val explanation: String,
    val isMarkedForReview: Boolean = false
)

data class NlCommandPlan(
    val rawCommand: String,
    val intentType: String,
    val extractedCriteria: String,
    val targetFolder: String,
    val matchedFiles: List<PhoneFileItem>,
    val executionSummary: String,
    val isExecuted: Boolean = false
)

data class RagChatMessage(
    val id: String,
    val isUser: Boolean,
    val message: String,
    val citations: List<String> = emptyList()
)
