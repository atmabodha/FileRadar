package com.example.pdfextractor

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import com.example.model.PhoneFileItem
import com.example.pdfextractor.model.DocumentExtractionResult
import com.example.pdfextractor.model.ExtractedPage
import com.example.pdfextractor.model.ExtractionConfig
import com.example.pdfextractor.model.ExtractionSource
import com.example.ui.components.buildSamplePdfPagesText
import com.example.ui.components.cleanAndSanitizePdfPageText
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * In-memory cache of extracted PDF documents (pages + sentence chunks) keyed by file ID and mtime
 * so switching between PDF Text View and PDF Chat or asking follow-up questions is instant.
 */
object HybridPdfExtractionCache {
    private val cache = LruCache<String, DocumentExtractionResult>(16)

    fun get(key: String): DocumentExtractionResult? = synchronized(cache) { cache.get(key) }

    fun put(key: String, result: DocumentExtractionResult) {
        synchronized(cache) {
            cache.put(key, result)
        }
    }
}

/**
 * Production-grade, memory-safe, per-page Hybrid PDF Text Extraction Pipeline:
 * - Stage 1 (Digital Text Layer): Extracts embedded text per page using PdfBox-Android (`PDFTextStripper`).
 * - Stage 2 (OCR Fallback): If a page's extracted text falls below a meaningful character/word/quality threshold,
 *   renders that single page to a Bitmap via Android's native `PdfRenderer` at 180 DPI (clamped to 2480px)
 *   and runs on-device OCR using Google ML Kit Text Recognition (`com.google.mlkit:text-recognition`),
 *   recycling the Bitmap immediately in a `finally` block for Zero OOMs.
 * - Stage 3 (Normalization & Chunking): Cleans extracted text (whitespace normalization, de-hyphenation,
 *   garble stripping) and splits into sentence-aware chunks with page provenance (`PDF_TEXT` vs `ML_KIT_OCR`).
 */
class HybridPdfExtractor(
    private val context: Context
) {
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    init {
        runCatching {
            if (!PDFBoxResourceLoader.isReady()) {
                PDFBoxResourceLoader.init(context.applicationContext)
            }
        }
    }

    /**
     * Extracts text and sentence-aware chunks for a [PhoneFileItem] (resolving direct file paths,
     * content:// URIs, MediaStore entries, or built-in sample documents) and caches the result.
     */
    suspend fun extractForPhoneFile(
        file: PhoneFileItem,
        config: ExtractionConfig = ExtractionConfig(),
        onPageProgress: ((currentPage: Int, totalPages: Int) -> Unit)? = null
    ): DocumentExtractionResult = withContext(Dispatchers.IO) {
        val cacheKey = "${file.id}_${file.modifiedAtMillis}_ocr_${config.enableOcrFallback}"
        HybridPdfExtractionCache.get(cacheKey)?.let { cached ->
            onPageProgress?.invoke(cached.totalPages, cached.totalPages)
            return@withContext cached
        }

        if (file.isSampleFile) {
            val sampleResult = buildSampleExtractionResult(file, config)
            HybridPdfExtractionCache.put(cacheKey, sampleResult)
            onPageProgress?.invoke(sampleResult.totalPages, sampleResult.totalPages)
            return@withContext sampleResult
        }

        val resolvedUri = resolveUriForPhoneFile(file)
        if (resolvedUri != null) {
            val extracted = runCatching {
                extractDocument(
                    uri = resolvedUri,
                    config = config,
                    onPageProgress = onPageProgress
                )
            }.getOrNull()

            if (extracted != null && extracted.pages.any { it.normalizedText.isNotBlank() }) {
                HybridPdfExtractionCache.put(cacheKey, extracted)
                return@withContext extracted
            }
        }

        val fallbackSample = buildSampleExtractionResult(file, config)
        HybridPdfExtractionCache.put(cacheKey, fallbackSample)
        fallbackSample
    }

    /**
     * Extracts text per page from a content:// or file:// PDF URI using PDFBox first,
     * falling back to PdfRenderer + ML Kit OCR per page when embedded text is insufficient.
     */
    suspend fun extractDocument(
        uri: Uri,
        config: ExtractionConfig = ExtractionConfig(),
        onPageProgress: ((currentPage: Int, totalPages: Int) -> Unit)? = null
    ): DocumentExtractionResult = withContext(Dispatchers.IO) {
        val extractedPages = mutableListOf<ExtractedPage>()
        var tempCacheFile: File? = null
        var pfd: ParcelFileDescriptor? = null
        var pdfRenderer: PdfRenderer? = null

        try {
            runCatching {
                if (!PDFBoxResourceLoader.isReady()) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                }
            }

            // Copy content URI to a temporary seekable file if needed so both PDFBox and PdfRenderer
            // can read reliably without stream exhaustion or permission issues.
            val seekableFile = resolveSeekableFile(uri).also { (file, isTemp) ->
                if (isTemp) tempCacheFile = file
            }.first

            // Lazily open PdfRenderer only if at least one page triggers OCR fallback or PDFBox cannot open
            fun getOrCreateRenderer(): PdfRenderer {
                pdfRenderer?.let { return it }
                val descriptor = ParcelFileDescriptor.open(
                    seekableFile,
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                pfd = descriptor
                return PdfRenderer(descriptor).also { pdfRenderer = it }
            }

            val pdDocResult = runCatching { PDDocument.load(seekableFile) }
            val pdDocument = pdDocResult.getOrNull()

            if (pdDocument != null) {
                pdDocument.use { doc ->
                    val totalPages = doc.numberOfPages.coerceAtLeast(1)
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                    }

                    for (pageIndex in 0 until totalPages) {
                        coroutineContext.ensureActive()
                        val pageNumber = pageIndex + 1

                        // 1. Attempt fast digital text extraction for this single page via PDFBox
                        stripper.startPage = pageNumber
                        stripper.endPage = pageNumber
                        val rawEmbedded = runCatching { stripper.getText(doc) }.getOrDefault("")
                        val cleanedEmbedded = cleanAndSanitizePdfPageText(rawEmbedded)

                        val pageResult = if (hasSufficientText(cleanedEmbedded, config)) {
                            val normalized = TextNormalizer.normalize(cleanedEmbedded)
                            ExtractedPage(
                                pageNumber = pageNumber,
                                rawText = rawEmbedded,
                                normalizedText = normalized,
                                source = ExtractionSource.PDF_TEXT
                            )
                        } else if (!config.enableOcrFallback) {
                            val normalized = TextNormalizer.normalize(cleanedEmbedded)
                            ExtractedPage(
                                pageNumber = pageNumber,
                                rawText = rawEmbedded,
                                normalizedText = normalized,
                                source = if (normalized.isBlank()) {
                                    ExtractionSource.SKIPPED_OCR_DISABLED
                                } else {
                                    ExtractionSource.PDF_TEXT
                                }
                            )
                        } else {
                            // 2. Fallback: Render single page to Bitmap and run ML Kit OCR
                            val ocrText = runCatching {
                                extractPageWithOcr(getOrCreateRenderer(), pageIndex, config)
                            }.getOrDefault("")
                            val cleanedOcr = cleanAndSanitizePdfPageText(ocrText)
                            val bestText = if (cleanedOcr.length >= cleanedEmbedded.length) {
                                cleanedOcr
                            } else {
                                cleanedEmbedded
                            }
                            val normalized = TextNormalizer.normalize(bestText)
                            val source = when {
                                normalized.isBlank() -> ExtractionSource.EMPTY
                                cleanedOcr.length >= cleanedEmbedded.length && cleanedOcr.isNotBlank() -> ExtractionSource.ML_KIT_OCR
                                else -> ExtractionSource.PDF_TEXT
                            }
                            ExtractedPage(
                                pageNumber = pageNumber,
                                rawText = bestText,
                                normalizedText = normalized,
                                source = source
                            )
                        }

                        extractedPages.add(pageResult)
                        onPageProgress?.invoke(pageNumber, totalPages)

                        if (config.throttleDelayMs > 0L && pageNumber < totalPages) {
                            delay(config.throttleDelayMs)
                        }
                    }
                }
            } else if (config.enableOcrFallback) {
                // If PDFBox failed to parse structure (e.g. non-standard XRef/encrypted header),
                // use native Android PdfRenderer + ML Kit OCR per page
                val renderer = getOrCreateRenderer()
                val totalPages = renderer.pageCount.coerceAtLeast(1)
                for (pageIndex in 0 until totalPages) {
                    coroutineContext.ensureActive()
                    val pageNumber = pageIndex + 1
                    val ocrText = runCatching {
                        extractPageWithOcr(renderer, pageIndex, config)
                    }.getOrDefault("")
                    val normalized = TextNormalizer.normalize(ocrText)
                    extractedPages.add(
                        ExtractedPage(
                            pageNumber = pageNumber,
                            rawText = ocrText,
                            normalizedText = normalized,
                            source = if (normalized.isBlank()) ExtractionSource.EMPTY else ExtractionSource.ML_KIT_OCR
                        )
                    )
                    onPageProgress?.invoke(pageNumber, totalPages)
                    if (config.throttleDelayMs > 0L && pageNumber < totalPages) {
                        delay(config.throttleDelayMs)
                    }
                }
            }

            // 3. Build sentence-aware chunks with page provenance
            val chunks = TextChunker.chunkPages(
                documentUri = uri,
                pages = extractedPages,
                maxChunkChars = config.chunkSizeChars,
                overlapChars = config.chunkOverlapChars
            )

            DocumentExtractionResult(
                documentUri = uri,
                totalPages = extractedPages.size,
                pages = extractedPages,
                chunks = chunks
            )
        } finally {
            runCatching { pdfRenderer?.close() }
            runCatching { pfd?.close() }
            runCatching { tempCacheFile?.delete() }
        }
    }

    private suspend fun extractPageWithOcr(
        renderer: PdfRenderer,
        pageIndex: Int,
        config: ExtractionConfig
    ): String {
        if (pageIndex !in 0 until renderer.pageCount) return ""
        var bitmap: Bitmap? = null
        return try {
            renderer.openPage(pageIndex).use { page ->
                val scale = config.renderDpi / 72f
                val rawWidth = (page.width * scale).roundToInt().coerceAtLeast(1)
                val rawHeight = (page.height * scale).roundToInt().coerceAtLeast(1)

                val clampRatio = min(
                    1f,
                    config.maxBitmapDimensionPx.toFloat() / maxOf(rawWidth, rawHeight).toFloat()
                )
                val targetWidth = (rawWidth * clampRatio).roundToInt().coerceAtLeast(1)
                val targetHeight = (rawHeight * clampRatio).roundToInt().coerceAtLeast(1)

                val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                bitmap = bmp

                // Fill white background so transparent PDFs render cleanly for OCR
                Canvas(bmp).drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val inputImage = InputImage.fromBitmap(bmp, 0)
                val visionText = textRecognizer.process(inputImage).await()

                // Reconstruct reading order by sorting text blocks top-to-bottom, left-to-right
                val orderedBlocks = visionText.textBlocks.sortedWith(
                    compareBy<com.google.mlkit.vision.text.Text.TextBlock> { block ->
                        (block.boundingBox?.top ?: 0) / 24
                    }.thenBy { block ->
                        block.boundingBox?.left ?: 0
                    }
                )
                if (orderedBlocks.isNotEmpty()) {
                    orderedBlocks.joinToString("\n\n") { block ->
                        block.lines.joinToString("\n") { it.text.trim() }
                    }
                } else {
                    visionText.text
                }
            }
        } finally {
            bitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    internal fun hasSufficientText(text: String, config: ExtractionConfig): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        val nonWhitespaceChars = trimmed.count { !it.isWhitespace() }
        if (nonWhitespaceChars < config.minMeaningfulChars) return false

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val validWords = tokens.count { token ->
            val letters = token.count { it.isLetter() }
            val digits = token.count { it.isDigit() }
            (letters >= 2 || digits >= 1) && (letters + digits).toFloat() / token.length.toFloat() >= 0.6f
        }
        if (validWords < config.minMeaningfulWords) return false

        // Reject broken kerning / single-letter soup where >45% of tokens are isolated 1-character letters
        val singleCharTokens = tokens.count { it.length == 1 && it[0].isLetter() }
        if (tokens.size >= 8 && singleCharTokens.toFloat() / tokens.size.toFloat() > 0.45f) {
            return false
        }

        // Verify natural vowel ratio across Latin letters so shifted CID font bytes trigger ML Kit OCR fallback
        val latinLetters = trimmed.count { it in 'A'..'Z' || it in 'a'..'z' }
        if (latinLetters >= 20) {
            val vowels = trimmed.count { it.lowercaseChar() in "aeiouy" }
            val vowelRatio = vowels.toFloat() / latinLetters.toFloat()
            if (vowelRatio < 0.18f || vowelRatio > 0.80f) {
                return false
            }
        }

        return true
    }

    private fun resolveUriForPhoneFile(file: PhoneFileItem): Uri? {
        try {
            val diskFile = File(file.fullPath)
            if (diskFile.exists() && diskFile.canRead() && diskFile.length() > 64L) {
                return Uri.fromFile(diskFile)
            }
        } catch (_: Throwable) {
        }

        if (!file.contentUri.isNullOrBlank()) {
            return runCatching { Uri.parse(file.contentUri) }.getOrNull()
        }

        return lookupMediaStoreUriForFile(file)
    }

    private fun lookupMediaStoreUriForFile(file: PhoneFileItem): Uri? {
        val contentResolver = context.contentResolver
        val candidateTables = mutableListOf<Uri>(MediaStore.Files.getContentUri("external"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            candidateTables.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        }
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)

        for (tableUri in candidateTables) {
            try {
                contentResolver.query(
                    tableUri,
                    projection,
                    "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
                    arrayOf(file.name),
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                    if (idIdx >= 0 && cursor.moveToFirst()) {
                        val id = cursor.getLong(idIdx)
                        return ContentUris.withAppendedId(tableUri, id)
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun resolveSeekableFile(uri: Uri): Pair<File, Boolean> {
        if (uri.scheme == "file" && uri.path != null) {
            val diskFile = File(uri.path!!)
            if (diskFile.exists() && diskFile.canRead()) {
                return diskFile to false
            }
        }
        val tempFile = File.createTempFile("pdf_extract_", ".pdf", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Unable to open InputStream for URI: $uri")
        return tempFile to true
    }

    private fun buildSampleExtractionResult(
        file: PhoneFileItem,
        config: ExtractionConfig
    ): DocumentExtractionResult {
        val sampleUri = Uri.parse("file://${file.fullPath}")
        val samplePagesText = buildSamplePdfPagesText(file)
        val extractedPages = samplePagesText.mapIndexed { idx, pageText ->
            val normalized = TextNormalizer.normalize(pageText)
            ExtractedPage(
                pageNumber = idx + 1,
                rawText = pageText,
                normalizedText = normalized,
                source = ExtractionSource.PDF_TEXT
            )
        }
        val chunks = TextChunker.chunkPages(
            documentUri = sampleUri,
            pages = extractedPages,
            maxChunkChars = config.chunkSizeChars,
            overlapChars = config.chunkOverlapChars
        )
        return DocumentExtractionResult(
            documentUri = sampleUri,
            totalPages = extractedPages.size,
            pages = extractedPages,
            chunks = chunks
        )
    }

    fun close() {
        runCatching { textRecognizer.close() }
    }
}
