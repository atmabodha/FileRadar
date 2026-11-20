package com.example.pdfextractor

import android.net.Uri
import com.example.pdfextractor.model.ExtractedPage
import com.example.pdfextractor.model.TextChunk
import com.example.ui.components.cleanAndSanitizePdfPageText
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil

object TextNormalizer {
    private val hyphenLineBreakRegex = Regex("([A-Za-z])-\\s*\\R\\s*([A-Za-z])")
    private val multipleNewlinesRegex = Regex("\\R{3,}")
    private val horizontalWhitespaceRegex = Regex("[ \\t\\x0B\\f\\r]+")
    private val trailingNewlineSpaceRegex = Regex(" *\\n *")
    private val standalonePageNumberRegex = Regex(
        "^(?:page\\s*\\d+(?:\\s*(?:of|/)\\s*\\d+)?|-\\s*\\d+\\s*-|\\[\\s*\\d+\\s*\\]|confidential\\s*(?:document)?|continued\\s+on\\s+next\\s+page)$",
        RegexOption.IGNORE_CASE
    )
    private val sectionNumberPrefixRegex = Regex(
        "^(?:section\\s+[0-9ivxlcdm]+[:.\\-\\s]|(?:[0-9]{1,2}|[IVX]{1,4})\\.[0-9.]*\\s+[A-Z]).*",
        RegexOption.IGNORE_CASE
    )

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        val deHyphenated = raw
            // Rejoin words hyphenated across line breaks (e.g., "docu-\nment" -> "document")
            .replace(hyphenLineBreakRegex, "$1$2")
            // Collapse excessive spaces/tabs on single lines
            .replace(horizontalWhitespaceRegex, " ")
            // Trim trailing spaces around newlines
            .replace(trailingNewlineSpaceRegex, "\n")
            // Preserve paragraph breaks while collapsing 3+ newlines
            .replace(multipleNewlinesRegex, "\n\n")
            .trim()

        // Run multi-stage PDF garble/symbol sanitizer while keeping clean sentences, numbers, and tables intact
        val sanitized = cleanAndSanitizePdfPageText(deHyphenated)
        val baseText = sanitized.ifBlank { deHyphenated }

        // Strip standalone single-line page number footers ("Page 1 of 5", "- 2 -")
        return baseText
            .lines()
            .filterNot { line -> standalonePageNumberRegex.matches(line.trim()) }
            .joinToString("\n")
            .trim()
    }

    /**
     * Detects and removes running headers, footers, and watermarks repeated across >= 40% of pages
     * (when the document has at least 3 pages), while populating [ExtractedPage.detectedSections].
     * Returns the cleaned pages and the total count of removed running header/footer instances.
     */
    fun stripCrossPageRunningHeadersAndFooters(
        pages: List<ExtractedPage>
    ): Pair<List<ExtractedPage>, Int> {
        if (pages.isEmpty()) return emptyList<ExtractedPage>() to 0

        // Build template frequency map from top 3 and bottom 3 non-empty lines of each page
        val repeatedTemplates = mutableSetOf<String>()
        if (pages.size >= 3) {
            val minPageFrequency = ceil(pages.size * 0.40).toInt().coerceAtLeast(2)
            val templatePageCounts = mutableMapOf<String, Int>()

            for (page in pages) {
                val nonEmptyLines = page.normalizedText
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (nonEmptyLines.size <= 3) continue

                val edgeCandidates = (nonEmptyLines.take(3) + nonEmptyLines.takeLast(3))
                    .map { toRunningHeaderTemplate(it) }
                    .filter { it.length in 4..95 }
                    .toSet()

                for (template in edgeCandidates) {
                    templatePageCounts[template] = (templatePageCounts[template] ?: 0) + 1
                }
            }

            for ((template, count) in templatePageCounts) {
                if (count >= minPageFrequency) {
                    repeatedTemplates.add(template)
                }
            }
        }

        var removedCount = 0
        val cleanedPages = pages.map { page ->
            val rawLines = page.normalizedText.lines()
            val nonEmptyIndices = rawLines.indices.filter { rawLines[it].trim().isNotEmpty() }
            val topEdgeSet = nonEmptyIndices.take(3).toSet()
            val bottomEdgeSet = nonEmptyIndices.takeLast(3).toSet()

            val keptLines = mutableListOf<String>()
            for ((idx, line) in rawLines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) {
                    keptLines.add("")
                    continue
                }
                val isEdgeLine = idx in topEdgeSet || idx in bottomEdgeSet
                val template = toRunningHeaderTemplate(trimmed)
                if (isEdgeLine && template in repeatedTemplates && nonEmptyIndices.size > 3) {
                    removedCount++
                    continue
                }
                if (standalonePageNumberRegex.matches(trimmed)) {
                    removedCount++
                    continue
                }
                keptLines.add(trimmed)
            }

            val finalNormalized = keptLines
                .joinToString("\n")
                .replace(multipleNewlinesRegex, "\n\n")
                .trim()
                .ifBlank { page.normalizedText }

            val sections = finalNormalized
                .lines()
                .mapNotNull { detectSectionHeading(it) }
                .distinct()

            page.copy(
                normalizedText = finalNormalized,
                detectedSections = sections
            )
        }

        return cleanedPages to removedCount
    }

    private fun toRunningHeaderTemplate(line: String): String {
        return line.lowercase(Locale.ROOT)
            .replace(Regex("\\d+"), "#")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Identifies section headings in PDF pages (e.g., "1. HEMATOLOGY PANEL", "BILLING SUMMARY",
     * "Metabolic & Glycemic Follow-up:") so child sentences and table rows inherit section context.
     */
    fun detectSectionHeading(line: String): String? {
        val trimmed = line.trim().trimStart('•', '-', '*').trim()
        if (trimmed.length !in 4..78) return null
        if (trimmed.contains('|')) return null // Table row with pipe delimiters is data, not a heading
        if (trimmed.endsWith('.') || trimmed.endsWith('?') || trimmed.endsWith('!')) return null
        if (standalonePageNumberRegex.matches(trimmed)) return null

        val letters = trimmed.count { it.isLetter() }
        if (letters < 4) return null
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size > 10) return null

        // Case 1: Explicit numbered section ("1. Executive Summary", "Section II: Lab Results")
        if (sectionNumberPrefixRegex.matches(trimmed)) {
            return trimmed.trimEnd(':')
        }

        // Case 2: Predominantly UPPERCASE heading ("HEMATOLOGY PANEL", "INVOICE SUMMARY")
        val upperCount = trimmed.count { it.isUpperCase() }
        val upperRatio = upperCount.toFloat() / letters.toFloat()
        if (upperRatio >= 0.78f && words.size in 1..8 && !trimmed.any { it == '$' || it == '%' }) {
            return trimmed.trimEnd(':')
        }

        // Case 3: Short Title-Case heading ending with a colon ("Key Recommendations:", "Patient Details:")
        if (trimmed.endsWith(':') && words.size in 1..6) {
            val valueAfterColon = trimmed.substringAfter(':', "").trim()
            if (valueAfterColon.isEmpty()) {
                return trimmed.trimEnd(':')
            }
        }

        return null
    }
}

object TextChunker {
    fun chunkPages(
        documentUri: Uri,
        pages: List<ExtractedPage>,
        maxChunkChars: Int = 800,
        overlapChars: Int = 100
    ): List<TextChunk> {
        val allChunks = mutableListOf<TextChunk>()

        for (page in pages) {
            if (page.normalizedText.isBlank()) continue

            // Parse page lines to associate each sentence/row with its active section heading
            val annotatedSentences = extractSectionAnnotatedSentences(page.normalizedText)
            var currentBuffer = StringBuilder()
            var currentChunkSection: String? = null
            var activeSection: String? = null
            var chunkIndex = 0

            fun flushCurrentBuffer() {
                val finalized = currentBuffer.toString().trim()
                if (finalized.isNotEmpty()) {
                    val textWithSection = if (
                        currentChunkSection != null &&
                        !finalized.contains(currentChunkSection!!, ignoreCase = true)
                    ) {
                        "[Section: $currentChunkSection] $finalized"
                    } else {
                        finalized
                    }
                    allChunks.add(
                        TextChunk(
                            documentUri = documentUri,
                            pageNumber = page.pageNumber,
                            chunkIndex = chunkIndex++,
                            text = textWithSection,
                            source = page.source,
                            sectionHeading = currentChunkSection
                        )
                    )
                }
            }

            for ((sentence, sectionHeading) in annotatedSentences) {
                if (sectionHeading != null) {
                    activeSection = sectionHeading
                }
                if (currentChunkSection == null) {
                    currentChunkSection = activeSection
                }

                if (sentence.length > maxChunkChars) {
                    if (currentBuffer.isNotEmpty()) {
                        flushCurrentBuffer()
                        currentBuffer = StringBuilder()
                    }
                    sentence.chunked(maxChunkChars).forEach { slice ->
                        val sliceText = if (
                            activeSection != null &&
                            !slice.contains(activeSection!!, ignoreCase = true)
                        ) {
                            "[Section: $activeSection] ${slice.trim()}"
                        } else {
                            slice.trim()
                        }
                        allChunks.add(
                            TextChunk(
                                documentUri = documentUri,
                                pageNumber = page.pageNumber,
                                chunkIndex = chunkIndex++,
                                text = sliceText,
                                source = page.source,
                                sectionHeading = activeSection
                            )
                        )
                    }
                    currentChunkSection = activeSection
                    continue
                }

                if (currentBuffer.length + sentence.length + 1 > maxChunkChars && currentBuffer.isNotEmpty()) {
                    val finalized = currentBuffer.toString().trim()
                    flushCurrentBuffer()

                    // Seed next chunk with trailing overlap context (snapped to word boundary)
                    val rawOverlap = finalized.takeLast(overlapChars)
                    val cleanOverlap = if (finalized.length > overlapChars) {
                        val firstSpace = rawOverlap.indexOf(' ')
                        if (firstSpace in 1 until rawOverlap.length - 1) {
                            rawOverlap.substring(firstSpace + 1).trim()
                        } else {
                            rawOverlap.trim()
                        }
                    } else {
                        rawOverlap.trim()
                    }
                    currentBuffer = StringBuilder(cleanOverlap)
                    currentChunkSection = activeSection
                }

                if (currentBuffer.isNotEmpty()) currentBuffer.append(" ")
                currentBuffer.append(sentence)
            }

            if (currentBuffer.isNotBlank()) {
                flushCurrentBuffer()
            }
        }

        return allChunks
    }

    /**
     * Splits page text into sentences while tracking the active section heading for each sentence.
     * Table rows separated by ` | ` are preserved as atomic sentence units with their parent section.
     */
    internal fun extractSectionAnnotatedSentences(pageText: String): List<Pair<String, String?>> {
        val result = mutableListOf<Pair<String, String?>>()
        var activeSection: String? = null

        for (rawLine in pageText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val maybeHeading = TextNormalizer.detectSectionHeading(line)
            if (maybeHeading != null) {
                activeSection = maybeHeading
                result.add(line to activeSection)
                continue
            }

            // Preserve reconstructed table rows ("Test | Value | Reference") as intact units
            if (line.contains(" | ")) {
                result.add(line to activeSection)
            } else {
                val sentences = splitIntoSentences(line)
                for (s in sentences) {
                    result.add(s to activeSection)
                }
            }
        }

        return if (result.isNotEmpty()) {
            result
        } else {
            splitIntoSentences(pageText).map { it to null }
        }
    }

    internal fun splitIntoSentences(text: String): List<String> {
        val iterator = BreakIterator.getSentenceInstance(Locale.getDefault())
        iterator.setText(text)
        val sentences = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trim()
            if (sentence.isNotEmpty()) {
                sentences.add(sentence)
            }
            start = end
            end = iterator.next()
        }
        return if (sentences.isNotEmpty()) {
            sentences
        } else {
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
