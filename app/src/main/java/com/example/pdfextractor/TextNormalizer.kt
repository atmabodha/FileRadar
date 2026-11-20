package com.example.pdfextractor

import android.net.Uri
import com.example.pdfextractor.model.ExtractedPage
import com.example.pdfextractor.model.TextChunk
import com.example.ui.components.cleanAndSanitizePdfPageText
import java.text.BreakIterator
import java.util.Locale

object TextNormalizer {
    private val hyphenLineBreakRegex = Regex("([A-Za-z])-\\s*\\R\\s*([A-Za-z])")
    private val multipleNewlinesRegex = Regex("\\R{3,}")
    private val horizontalWhitespaceRegex = Regex("[ \\t\\x0B\\f\\r]+")
    private val trailingNewlineSpaceRegex = Regex(" *\\n *")

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
        return sanitized.ifBlank { deHyphenated }
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

            val sentences = splitIntoSentences(page.normalizedText)
            var currentBuffer = StringBuilder()
            var chunkIndex = 0

            for (sentence in sentences) {
                if (sentence.length > maxChunkChars) {
                    // Flush existing buffer first
                    if (currentBuffer.isNotEmpty()) {
                        allChunks.add(
                            TextChunk(
                                documentUri = documentUri,
                                pageNumber = page.pageNumber,
                                chunkIndex = chunkIndex++,
                                text = currentBuffer.toString().trim(),
                                source = page.source
                            )
                        )
                        currentBuffer = StringBuilder()
                    }
                    // Hard-split unusually long single sentence/table block
                    sentence.chunked(maxChunkChars).forEach { slice ->
                        allChunks.add(
                            TextChunk(
                                documentUri = documentUri,
                                pageNumber = page.pageNumber,
                                chunkIndex = chunkIndex++,
                                text = slice.trim(),
                                source = page.source
                            )
                        )
                    }
                    continue
                }

                if (currentBuffer.length + sentence.length + 1 > maxChunkChars && currentBuffer.isNotEmpty()) {
                    val finalized = currentBuffer.toString().trim()
                    allChunks.add(
                        TextChunk(
                            documentUri = documentUri,
                            pageNumber = page.pageNumber,
                            chunkIndex = chunkIndex++,
                            text = finalized,
                            source = page.source
                        )
                    )

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
                }

                if (currentBuffer.isNotEmpty()) currentBuffer.append(" ")
                currentBuffer.append(sentence)
            }

            if (currentBuffer.isNotBlank()) {
                allChunks.add(
                    TextChunk(
                        documentUri = documentUri,
                        pageNumber = page.pageNumber,
                        chunkIndex = chunkIndex,
                        text = currentBuffer.toString().trim(),
                        source = page.source
                    )
                )
            }
        }

        return allChunks
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
