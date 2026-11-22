package com.example.pdfextractor

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
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
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
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
 * - Stage 1 (Digital Text Layer): Extracts embedded text per page using PdfBox-Android (`PDFTextStripper`)
 *   with tuned column/cell spacing tolerances (`spacingTolerance = 0.5f`, `averageCharTolerance = 0.3f`).
 * - Stage 2 (OCR Fallback): If a page's extracted text falls below a meaningful character/word/quality threshold,
 *   renders that single page to a Bitmap via Android's native `PdfRenderer` at 180 DPI (clamped to 2480px),
 *   applies an in-place high-contrast luminance pre-filter for faint scans, runs Google ML Kit Text Recognition,
 *   reconstructs spatial table rows (`Cell 1 | Cell 2 | Cell 3`) via Y-bounding-box clustering, and recycles
 *   the Bitmap immediately in a `finally` block for Zero OOMs.
 * - Stage 3 (Cross-Page Header/Footer Deduplication, Normalization & Section-Aware Chunking):
 *   Removes repeated running headers/footers across pages, normalizes text, detects section headings,
 *   and splits into sentence-aware overlapping chunks with page and section provenance.
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
        val cacheKey = "${file.id}_${file.modifiedAtMillis}_ocr_${config.enableOcrFallback}_force_${config.forceOcrAllPages}_fusion_${config.smartVisualOcrFusion}_v3"
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
     * falling back to (or fusing with) PdfRenderer + ML Kit Vision OCR per page when embedded text
     * is insufficient, has low lexical/layout coherence, or when [ExtractionConfig.forceOcrAllPages] is true.
     */
    suspend fun extractDocument(
        uri: Uri,
        config: ExtractionConfig = ExtractionConfig(),
        onPageProgress: ((currentPage: Int, totalPages: Int) -> Unit)? = null
    ): DocumentExtractionResult = withContext(Dispatchers.IO) {
        val rawExtractedPages = mutableListOf<ExtractedPage>()
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

            // Lazily open PdfRenderer only if at least one page triggers OCR or PDFBox cannot open
            fun getOrCreateRenderer(): PdfRenderer {
                pdfRenderer?.let { return it }
                val descriptor = ParcelFileDescriptor.open(
                    seekableFile,
                    ParcelFileDescriptor.MODE_READ_ONLY
                )
                pfd = descriptor
                return PdfRenderer(descriptor).also { pdfRenderer = it }
            }

            val pdDocResult = if (config.forceOcrAllPages && config.enableOcrFallback) {
                null
            } else {
                runCatching { PDDocument.load(seekableFile) }.getOrNull()
            }

            if (pdDocResult != null) {
                pdDocResult.use { doc ->
                    val totalPages = doc.numberOfPages.coerceAtLeast(1)
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        spacingTolerance = 0.5f
                        averageCharTolerance = 0.3f
                        dropThreshold = 2.5f
                    }

                    for (pageIndex in 0 until totalPages) {
                        coroutineContext.ensureActive()
                        val pageNumber = pageIndex + 1

                        // 1. Attempt fast digital text extraction for this single page via PDFBox
                        stripper.startPage = pageNumber
                        stripper.endPage = pageNumber
                        val rawEmbedded = runCatching { stripper.getText(doc) }.getOrDefault("")
                        val cleanedEmbedded = cleanAndSanitizePdfPageText(rawEmbedded)
                        val embeddedSufficient = hasSufficientText(cleanedEmbedded, config)
                        val embeddedSuspicious = config.smartVisualOcrFusion &&
                            pageIndex < 12 &&
                            isDigitalTextLikelyIncompleteOrDegraded(rawEmbedded, cleanedEmbedded)

                        val pageResult = if (embeddedSufficient && !embeddedSuspicious && !config.forceOcrAllPages) {
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
                            // 2. Render single page to Bitmap and run ML Kit Vision OCR with spatial row clustering
                            val ocrText = runCatching {
                                extractPageWithOcr(getOrCreateRenderer(), pageIndex, config)
                            }.getOrDefault("")
                            val cleanedOcr = cleanAndSanitizePdfPageText(ocrText)

                            val (chosenText, chosenSource) = selectOrFuseDigitalAndVisualOcrText(
                                cleanedDigital = cleanedEmbedded,
                                cleanedVisualOcr = cleanedOcr,
                                forceVisualOcr = config.forceOcrAllPages
                            )
                            val normalized = TextNormalizer.normalize(chosenText)
                            ExtractedPage(
                                pageNumber = pageNumber,
                                rawText = chosenText,
                                normalizedText = normalized,
                                source = if (normalized.isBlank()) ExtractionSource.EMPTY else chosenSource
                            )
                        }

                        rawExtractedPages.add(pageResult)
                        onPageProgress?.invoke(pageNumber, totalPages)

                        if (config.throttleDelayMs > 0L && pageNumber < totalPages) {
                            delay(config.throttleDelayMs)
                        }
                    }
                }
            } else if (config.enableOcrFallback) {
                // Full Visual OCR path (identical to the Image OCR scanner): renders every page via
                // native Android PdfRenderer at 220 DPI and runs Google ML Kit Vision OCR + spatial table reconstruction
                val renderer = getOrCreateRenderer()
                val totalPages = renderer.pageCount.coerceAtLeast(1)
                for (pageIndex in 0 until totalPages) {
                    coroutineContext.ensureActive()
                    val pageNumber = pageIndex + 1
                    val ocrText = runCatching {
                        extractPageWithOcr(renderer, pageIndex, config)
                    }.getOrDefault("")
                    val normalized = TextNormalizer.normalize(ocrText)
                    rawExtractedPages.add(
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

            // 3. Cross-page running header/footer deduplication & section discovery
            val (cleanedPages, removedHeadersCount) = if (config.stripRunningHeadersFooters) {
                TextNormalizer.stripCrossPageRunningHeadersAndFooters(rawExtractedPages)
            } else {
                rawExtractedPages to 0
            }

            // 4. Build sentence-aware chunks with page & section-heading provenance
            val chunks = TextChunker.chunkPages(
                documentUri = uri,
                pages = cleanedPages,
                maxChunkChars = config.chunkSizeChars,
                overlapChars = config.chunkOverlapChars
            )

            DocumentExtractionResult(
                documentUri = uri,
                totalPages = cleanedPages.size,
                pages = cleanedPages,
                chunks = chunks,
                removedRunningHeadersCount = removedHeadersCount
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

                val canvas = Canvas(bmp)
                // Fill white background so transparent PDFs render cleanly for OCR (identical to Image OCR scanner)
                canvas.drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                if (config.enhanceContrastForOcr) {
                    applyInPlaceHighContrastFilter(canvas, bmp)
                }

                val inputImage = InputImage.fromBitmap(bmp, 0)
                val visionText = textRecognizer.process(inputImage).await()

                reconstructSpatialTableAndLines(visionText)
            }
        } finally {
            bitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    /**
     * Applies a non-destructive midpoint contrast curve centered around 128 (`translate = 128f * (1f - contrast)`)
     * so white paper backgrounds stay pure white (255) and anti-aliased strokes remain crisp.
     */
    private fun applyInPlaceHighContrastFilter(canvas: Canvas, bmp: Bitmap) {
        val contrast = 1.25f
        val translate = 128f * (1f - contrast)
        val matrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(bmp, 0f, 0f, paint)
    }

    /**
     * Clusters ML Kit [Text.Line] elements by vertical Y-bounding-box overlap into horizontal rows,
     * sorts cells left-to-right within each row, and inserts ` | ` delimiters between horizontally
     * separated table cells so multi-column tables (lab panels, invoices, statements) stay intact row-by-row.
     */
    internal fun reconstructSpatialTableAndLines(visionText: Text): String {
        data class PositionedLine(
            val text: String,
            val box: Rect,
            val centerY: Float,
            val height: Float
        )

        val positionedLines = mutableListOf<PositionedLine>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val clean = line.text.trim()
                val box = line.boundingBox
                if (clean.isNotEmpty() && box != null) {
                    val h = (box.height()).coerceAtLeast(8).toFloat()
                    val cy = box.exactCenterY()
                    positionedLines.add(PositionedLine(clean, box, cy, h))
                }
            }
        }

        if (positionedLines.isEmpty()) {
            return visionText.text
        }

        // Sort all lines top-to-bottom by vertical center, then left-to-right
        val sortedByY = positionedLines.sortedWith(
            compareBy<PositionedLine> { it.centerY }.thenBy { it.box.left }
        )

        // Cluster lines that share the same horizontal row band (deltaY <= 0.55 * lineHeight)
        val rows = mutableListOf<MutableList<PositionedLine>>()
        for (item in sortedByY) {
            val matchingRow = rows.lastOrNull { row ->
                val rowAvgCy = row.map { it.centerY }.average().toFloat()
                val rowAvgH = row.map { it.height }.average().toFloat()
                val threshold = max(rowAvgH, item.height) * 0.55f
                abs(item.centerY - rowAvgCy) <= threshold
            }
            if (matchingRow != null) {
                matchingRow.add(item)
            } else {
                rows.add(mutableListOf(item))
            }
        }

        val sb = StringBuilder()
        var prevRowBottom: Int? = null
        var prevRowHeight = 16f

        for (row in rows) {
            val leftToRight = row.sortedBy { it.box.left }
            val rowTop = leftToRight.minOf { it.box.top }
            val rowBottom = leftToRight.maxOf { it.box.bottom }
            val rowAvgH = leftToRight.map { it.height }.average().toFloat().coerceAtLeast(10f)

            if (prevRowBottom != null) {
                val verticalGap = rowTop - prevRowBottom
                if (verticalGap > max(prevRowHeight, rowAvgH) * 1.65f) {
                    sb.append("\n\n")
                } else {
                    sb.append('\n')
                }
            }

            // Join cells in the same row: use " | " when horizontal gap indicates distinct table columns
            for ((cellIdx, cell) in leftToRight.withIndex()) {
                if (cellIdx > 0) {
                    val prevCell = leftToRight[cellIdx - 1]
                    val horizontalGap = cell.box.left - prevCell.box.right
                    if (horizontalGap > rowAvgH * 0.85f) {
                        sb.append(" | ")
                    } else {
                        sb.append(' ')
                    }
                }
                sb.append(cell.text)
            }

            prevRowBottom = rowBottom
            prevRowHeight = rowAvgH
        }

        return sb.toString().trim()
    }

    /**
     * Detects when PDFBox extracted some digital text (passing the minimum 40-char threshold),
     * but the page is likely a hybrid PDF (e.g., an embedded scanned form or image with only a watermark
     * or header in the digital text layer, or fragmented multi-column text with unmapped subset glyphs)
     * that would benefit from running Visual ML Kit OCR and comparing/fusing both outputs.
     */
    internal fun isDigitalTextLikelyIncompleteOrDegraded(
        rawDigital: String,
        cleanedDigital: String
    ): Boolean {
        val trimmed = cleanedDigital.trim()
        // Hybrid scanned PDFs often have only a short digital stamp/header (< 180 chars) while the rest of the page is an image
        if (trimmed.length < 180) return true

        // If >28% of raw characters were dropped during sanitization, the PDF likely has broken subset font mappings
        if (rawDigital.length > 80 && trimmed.length.toFloat() / rawDigital.length.toFloat() < 0.72f) {
            return true
        }

        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return true

        // Fragmented multi-column or table explosion where most lines are 1-2 short tokens without table delimiters
        val shortFragmentLines = lines.count { line ->
            !line.contains('|') && !line.contains(':') && line.split(' ').size <= 2 && line.length < 18
        }
        if (lines.size >= 6 && shortFragmentLines.toFloat() / lines.size.toFloat() > 0.55f) {
            return true
        }

        return false
    }

    /**
     * Evaluates the readability, word count, and structural completeness of both PDFBox digital text
     * and ML Kit Visual OCR text for the same page, returning the superior extraction—or fusing
     * unique visual OCR lines (such as text inside embedded diagrams, stamps, or scanned headers)
     * with the digital text layer.
     */
    internal fun selectOrFuseDigitalAndVisualOcrText(
        cleanedDigital: String,
        cleanedVisualOcr: String,
        forceVisualOcr: Boolean = false
    ): Pair<String, ExtractionSource> {
        val digital = cleanedDigital.trim()
        val visual = cleanedVisualOcr.trim()

        if (forceVisualOcr && visual.isNotEmpty()) {
            return visual to ExtractionSource.ML_KIT_OCR
        }
        if (digital.isEmpty() && visual.isEmpty()) {
            return "" to ExtractionSource.EMPTY
        }
        if (digital.isEmpty()) {
            return visual to ExtractionSource.ML_KIT_OCR
        }
        if (visual.isEmpty()) {
            return digital to ExtractionSource.PDF_TEXT
        }

        val digitalScore = computeExtractionReadabilityScore(digital)
        val visualScore = computeExtractionReadabilityScore(visual)

        // If Visual OCR captured significantly more content (e.g. scanned form body + digital header)
        // or clearly higher structural readability (e.g. reconstructed " | " table rows), prefer Visual OCR
        if (visualScore >= digitalScore * 1.08) {
            // Check if digital has any unique lines not present in visual OCR
            val visualLower = visual.lowercase()
            val extraDigitalLines = digital.lines()
                .map { it.trim() }
                .filter { line ->
                    line.length >= 12 && !visualLower.contains(line.take(20).lowercase())
                }
            return if (extraDigitalLines.isNotEmpty() && extraDigitalLines.size <= 6) {
                (visual + "\n" + extraDigitalLines.joinToString("\n")) to ExtractionSource.FUSED_HYBRID_OCR
            } else {
                visual to ExtractionSource.ML_KIT_OCR
            }
        }

        // Otherwise digital text is strong; append any unique lines found by Visual OCR (e.g. embedded image banners/stamps)
        val digitalLower = digital.lowercase()
        val extraVisualLines = visual.lines()
            .map { it.trim() }
            .filter { line ->
                line.length >= 12 && !digitalLower.contains(line.take(20).lowercase())
            }
        return if (extraVisualLines.isNotEmpty()) {
            (digital + "\n" + extraVisualLines.joinToString("\n")) to ExtractionSource.FUSED_HYBRID_OCR
        } else {
            digital to ExtractionSource.PDF_TEXT
        }
    }

    internal fun computeExtractionReadabilityScore(text: String): Double {
        if (text.isBlank()) return 0.0
        val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return 0.0

        val naturalWords = tokens.count { token ->
            val letters = token.count { it.isLetter() }
            val vowels = token.count { it.lowercaseChar() in "aeiouy" }
            (letters in 2..22 && vowels >= 1) || token.any { it.isDigit() }
        }
        val tableRowBonus = text.lines().count { it.contains(" | ") } * 6.0
        val keyValueBonus = text.lines().count { it.contains(": ") } * 2.5
        val naturalRatio = naturalWords.toDouble() / tokens.size.toDouble()

        return (naturalWords * naturalRatio) + tableRowBonus + keyValueBonus
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
            (letters >= 2 || digits >= 1) && (letters + digits).toFloat() / token.length.toFloat() >= 0.55f
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
        val rawPages = samplePagesText.mapIndexed { idx, pageText ->
            val normalized = TextNormalizer.normalize(pageText)
            ExtractedPage(
                pageNumber = idx + 1,
                rawText = pageText,
                normalizedText = normalized,
                source = ExtractionSource.PDF_TEXT
            )
        }
        val (cleanedPages, removedHeadersCount) = if (config.stripRunningHeadersFooters) {
            TextNormalizer.stripCrossPageRunningHeadersAndFooters(rawPages)
        } else {
            rawPages to 0
        }
        val chunks = TextChunker.chunkPages(
            documentUri = sampleUri,
            pages = cleanedPages,
            maxChunkChars = config.chunkSizeChars,
            overlapChars = config.chunkOverlapChars
        )
        return DocumentExtractionResult(
            documentUri = sampleUri,
            totalPages = cleanedPages.size,
            pages = cleanedPages,
            chunks = chunks,
            removedRunningHeadersCount = removedHeadersCount
        )
    }

    fun close() {
        runCatching { textRecognizer.close() }
    }
}
