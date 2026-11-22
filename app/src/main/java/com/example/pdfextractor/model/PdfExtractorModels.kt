package com.example.pdfextractor.model

import android.net.Uri

enum class ExtractionSource(val badgeLabel: String) {
    PDF_TEXT("Digital Text (PDFBox)"),
    ML_KIT_OCR("Visual OCR (ML Kit)"),
    FUSED_HYBRID_OCR("Hybrid Fused (PDFBox + Visual OCR)"),
    SKIPPED_OCR_DISABLED("OCR Disabled"),
    EMPTY("No Text Detected")
}

data class ExtractionConfig(
    val enableOcrFallback: Boolean = true,
    val forceOcrAllPages: Boolean = false,
    val smartVisualOcrFusion: Boolean = true,
    val minMeaningfulChars: Int = 40,
    val minMeaningfulWords: Int = 6,
    val renderDpi: Int = 220,
    val maxBitmapDimensionPx: Int = 2480,
    val throttleDelayMs: Long = 0L,
    val chunkSizeChars: Int = 800,
    val chunkOverlapChars: Int = 100,
    val enhanceContrastForOcr: Boolean = false,
    val stripRunningHeadersFooters: Boolean = true
)

data class ExtractedPage(
    val pageNumber: Int, // 1-indexed
    val rawText: String,
    val normalizedText: String,
    val source: ExtractionSource,
    val detectedSections: List<String> = emptyList()
)

data class TextChunk(
    val documentUri: Uri,
    val pageNumber: Int, // 1-indexed
    val chunkIndex: Int,
    val text: String,
    val source: ExtractionSource,
    val sectionHeading: String? = null
)

data class DocumentExtractionResult(
    val documentUri: Uri,
    val totalPages: Int,
    val pages: List<ExtractedPage>,
    val chunks: List<TextChunk>,
    val removedRunningHeadersCount: Int = 0
)
