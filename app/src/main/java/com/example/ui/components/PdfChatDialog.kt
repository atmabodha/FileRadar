package com.example.ui.components

import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.model.PhoneFileItem
import com.example.pdfextractor.HybridPdfExtractor
import com.example.pdfextractor.TextChunker
import com.example.pdfextractor.TextNormalizer
import com.example.pdfextractor.model.DocumentExtractionResult
import com.example.pdfextractor.model.ExtractedPage
import com.example.pdfextractor.model.ExtractionConfig
import com.example.pdfextractor.model.ExtractionSource
import com.example.pdfextractor.model.TextChunk
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "PdfChatAi"

/**
 * Two selectable engines for chatting with a PDF document:
 * 1. [LOCAL_GEMMA_E2B]: 100% offline on-device Gemma E2B 4-bit RAG over hybrid-extracted PDF chunks (PDFBox + ML Kit OCR).
 * 2. [GEMINI_SUBSCRIPTION]: Keyless Firebase AI Logic (`gemini-3.8-flash` free tier — no API key or paid billing).
 */
enum class PdfChatEngineOption(
    val displayName: String,
    val subtitle: String,
    val badgeLabel: String
) {
    LOCAL_GEMMA_E2B(
        displayName = "Local Gemma E2B",
        subtitle = "On-Device Hybrid RAG • PDFBox + OCR",
        badgeLabel = "Gemma E2B (On-Device RAG)"
    ),
    GEMINI_SUBSCRIPTION(
        displayName = "Gemini (Keyless Free Tier)",
        subtitle = "Firebase AI Logic • gemini-3.8-flash",
        badgeLabel = "Gemini 3.8 Flash (Keyless)"
    )
}

data class PdfChatMessage(
    val id: String,
    val isUser: Boolean,
    val engine: PdfChatEngineOption,
    val text: String,
    val pageCitations: List<String> = emptyList(),
    val isSamplePlaceholder: Boolean = false,
    val isError: Boolean = false,
    val isStreaming: Boolean = false
)

/**
 * AI Repository interface for Cloud Gemini PDF Q&A.
 */
interface PdfChatAiRepository {
    suspend fun generateGeminiSubscriptionAnswer(
        file: PhoneFileItem,
        pagesText: List<String>,
        question: String,
        onStreamUpdate: ((String, List<String>) -> Unit)? = null
    ): PdfChatMessage
}

/**
 * Production implementation using keyless Firebase AI Logic (`Firebase.ai`) with the free-tier
 * `gemini-3.8-flash` model (no API key required and no paid Blaze billing required).
 */
class FirebaseAiLogicPdfChatRepository : PdfChatAiRepository {

    // Lazy initialization per Firebase AI Logic best practices
    private val model by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    override suspend fun generateGeminiSubscriptionAnswer(
        file: PhoneFileItem,
        pagesText: List<String>,
        question: String,
        onStreamUpdate: ((String, List<String>) -> Unit)?
    ): PdfChatMessage {
        val rankedPassages = withContext(Dispatchers.Default) {
            retrieveRelevantPdfPassages(pagesText, question)
        }
        val citations = if (rankedPassages.isNotEmpty()) {
            rankedPassages.map { "Page ${it.pageNumber}" }.distinct()
        } else {
            pagesText.indices.take(4).map { "Page ${it + 1}" }
        }

        // Build concise, grounded context from the PDF's cleaned pages and top sentence chunks
        val pdfContext = buildString {
            append("PDF File Name: ").append(file.name).append('\n')
            append("Total Pages: ").append(pagesText.size).append("\n\n")
            if (rankedPassages.isNotEmpty()) {
                append("Top Matching Sentence Chunks:\n")
                rankedPassages.forEach { passage ->
                    append("- [Page ").append(passage.pageNumber).append("] ").append(passage.snippet).append('\n')
                }
                append('\n')
            }
            append("Cleaned Page Contents:\n")
            pagesText.take(6).forEachIndexed { idx, pageText ->
                append("--- PAGE ").append(idx + 1).append(" ---\n")
                append(pageText.take(1400)).append("\n\n")
            }
        }

        val prompt = """
            You are a helpful document assistant inside File Radar. Answer the user's question clearly and concisely using ONLY the provided PDF document content below.
            Cite specific page numbers (e.g., [Page 1], [Page 2]) when referencing facts, numbers, or dates.
            Use clean bullet points for readability when summarizing.

            $pdfContext

            User Question: $question
        """.trimIndent()

        val messageId = "gemini_${System.currentTimeMillis()}"
        val accumulatedText = StringBuilder()

        return try {
            withTimeout(45_000L) {
                model.generateContentStream(prompt).collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        accumulatedText.append(chunkText)
                        onStreamUpdate?.invoke(accumulatedText.toString(), citations)
                    }
                }
            }
            val finalText = accumulatedText.toString().trim()
            if (finalText.isNotEmpty()) {
                PdfChatMessage(
                    id = messageId,
                    isUser = false,
                    engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                    text = finalText,
                    pageCitations = citations,
                    isSamplePlaceholder = false,
                    isError = false
                )
            } else {
                val unaryResponse = withTimeout(30_000L) {
                    model.generateContent(prompt)
                }
                val unaryText = unaryResponse.text?.trim().orEmpty()
                if (unaryText.isNotEmpty()) {
                    PdfChatMessage(
                        id = messageId,
                        isUser = false,
                        engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                        text = unaryText,
                        pageCitations = citations,
                        isSamplePlaceholder = false,
                        isError = false
                    )
                } else {
                    PdfChatMessage(
                        id = messageId,
                        isUser = false,
                        engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                        text = "Model returned an empty response. Please try asking your question again.",
                        pageCitations = citations,
                        isError = true
                    )
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Firebase AI PDF chat streaming timed out", e)
            val partial = accumulatedText.toString().trim()
            if (partial.isNotEmpty()) {
                PdfChatMessage(
                    id = messageId,
                    isUser = false,
                    engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                    text = partial,
                    pageCitations = citations,
                    isSamplePlaceholder = false,
                    isError = false
                )
            } else {
                try {
                    val fallbackResponse = withTimeout(25_000L) {
                        model.generateContent(prompt)
                    }
                    val fallbackText = fallbackResponse.text?.trim().orEmpty()
                    if (fallbackText.isNotEmpty()) {
                        PdfChatMessage(
                            id = messageId,
                            isUser = false,
                            engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                            text = fallbackText,
                            pageCitations = citations,
                            isSamplePlaceholder = false,
                            isError = false
                        )
                    } else {
                        PdfChatMessage(
                            id = messageId,
                            isUser = false,
                            engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                            text = "Generation timed out. Please try again or switch to Local Gemma E2B.",
                            pageCitations = citations,
                            isError = true
                        )
                    }
                } catch (ce: CancellationException) {
                    if (ce !is TimeoutCancellationException) throw ce
                    PdfChatMessage(
                        id = messageId,
                        isUser = false,
                        engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                        text = "Generation timed out after 60 seconds. Please try again or switch to Local Gemma E2B.",
                        pageCitations = citations,
                        isError = true
                    )
                } catch (inner: Exception) {
                    Log.e(TAG, "Firebase AI unary fallback failed after timeout", inner)
                    PdfChatMessage(
                        id = messageId,
                        isUser = false,
                        engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                        text = inner.localizedMessage ?: "Generation timed out. Please try again or switch to Local Gemma E2B.",
                        pageCitations = citations,
                        isError = true
                    )
                }
            }
        } catch (e: CancellationException) {
            // Always rethrow CancellationException to preserve cooperative coroutine cancellation
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI PDF chat generation failed", e)
            val partial = accumulatedText.toString().trim()
            if (partial.isNotEmpty()) {
                PdfChatMessage(
                    id = messageId,
                    isUser = false,
                    engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                    text = partial,
                    pageCitations = citations,
                    isSamplePlaceholder = false,
                    isError = false
                )
            } else {
                PdfChatMessage(
                    id = messageId,
                    isUser = false,
                    engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                    text = e.localizedMessage ?: "Firebase AI generation failed.",
                    pageCitations = citations,
                    isError = true
                )
            }
        }
    }
}

/**
 * Deterministic test/offline repository implementation for JVM unit testing.
 */
class PlaceholderGeminiSubscriptionRepository : PdfChatAiRepository {
    override suspend fun generateGeminiSubscriptionAnswer(
        file: PhoneFileItem,
        pagesText: List<String>,
        question: String,
        onStreamUpdate: ((String, List<String>) -> Unit)?
    ): PdfChatMessage = withContext(Dispatchers.Default) {
        val extraction = buildExtractionResultFromRawPages(file, pagesText)
        val rankedPassages = retrieveRelevantPdfPassages(pagesText, question)
        val citations = rankedPassages.map { "Page ${it.pageNumber}" }.distinct()
        val topExcerpt = rankedPassages.joinToString("\n• ") {
            "[Page ${it.pageNumber}] ${it.snippet}"
        }

        val responseText = buildString {
            append("Based on ${file.name} (${pagesText.size} ${if (pagesText.size == 1) "page" else "pages"}):\n")
            if (topExcerpt.isNotBlank()) {
                append("• ").append(topExcerpt).append("\n\n")
            }
            append(synthesizeLocalGemmaResponse(file, extraction, rankedPassages, question))
        }

        PdfChatMessage(
            id = "gemini_${System.currentTimeMillis()}",
            isUser = false,
            engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
            text = responseText,
            pageCitations = citations,
            isSamplePlaceholder = true
        )
    }
}

internal data class PdfPassageMatch(
    val pageNumber: Int,
    val snippet: String,
    val score: Double,
    val source: ExtractionSource = ExtractionSource.PDF_TEXT,
    val sectionHeading: String? = null
)

internal data class ResolvedConversationalQuery(
    val originalQuestion: String,
    val effectiveQueryText: String,
    val inheritedTopicTokens: List<String>,
    val inheritedPageNumbers: Set<Int>,
    val isFollowUpTurn: Boolean
)

/**
 * Generates an on-device Local Gemma E2B answer using:
 * 1. Multi-Turn Conversational Memory & Coreference Resolution ([conversationHistory]),
 * 2. Section-Heading Context Inheritance (parent-child chunking),
 * 3. BM25/IDF + Character 3-Gram Fuzzy Matching + 5-Word Sliding-Window Phrase Proximity,
 * 4. Maximal Marginal Relevance (MMR) Diversity Re-Ranking, and
 * 5. Extractive TextRank / Centroid + Intent-Aware Synthesis.
 */
internal fun generateLocalGemmaE2bPdfAnswer(
    file: PhoneFileItem,
    pagesText: List<String>,
    question: String,
    extractionResult: DocumentExtractionResult? = null,
    conversationHistory: List<PdfChatMessage> = emptyList()
): PdfChatMessage {
    val resolvedExtraction = extractionResult ?: buildExtractionResultFromRawPages(file, pagesText)
    val conversationalQuery = resolveFollowUpQueryWithMemory(question, conversationHistory)
    val rankedPassages = retrieveRelevantChunksAndPassages(
        extraction = resolvedExtraction,
        question = conversationalQuery.effectiveQueryText,
        preferredPages = conversationalQuery.inheritedPageNumbers
    )
    val citations = if (rankedPassages.isNotEmpty()) {
        rankedPassages.map { match ->
            if (match.source == ExtractionSource.ML_KIT_OCR) {
                "Page ${match.pageNumber} (OCR)"
            } else {
                "Page ${match.pageNumber}"
            }
        }.distinct()
    } else {
        resolvedExtraction.pages.take(4).map { "Page ${it.pageNumber}" }
    }

    val synthesized = synthesizeLocalGemmaResponse(
        file = file,
        extraction = resolvedExtraction,
        topPassages = rankedPassages,
        question = question,
        conversationalQuery = conversationalQuery
    )

    return PdfChatMessage(
        id = "gemma_${System.currentTimeMillis()}",
        isUser = false,
        engine = PdfChatEngineOption.LOCAL_GEMMA_E2B,
        text = synthesized,
        pageCitations = citations,
        isSamplePlaceholder = false
    )
}

/**
 * Resolves pronouns ("it", "that", "this", "they") and conversational follow-ups
 * ("is it normal?", "what about on page 2?", "explain more") using the recent chat history.
 */
internal fun resolveFollowUpQueryWithMemory(
    question: String,
    history: List<PdfChatMessage>
): ResolvedConversationalQuery {
    val trimmed = question.trim()
    val lower = trimmed.lowercase(Locale.ROOT)
    val currentWords = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val substantiveCurrentTokens = currentWords.filter { it.length >= 3 && it !in ragStopWords && it !in followUpPronouns }

    val hasPronounReference = currentWords.any { it in followUpPronouns }
    val hasEllipsisStarter = lower.startsWith("what about") ||
        lower.startsWith("how about") ||
        lower.startsWith("and ") ||
        lower.startsWith("also ") ||
        lower.startsWith("is it") ||
        lower.startsWith("are they") ||
        lower.startsWith("was it") ||
        lower.startsWith("why") ||
        lower.startsWith("explain") ||
        lower.contains("more detail") ||
        lower.contains("that page") ||
        lower.contains("same page") ||
        lower.contains("normal range") ||
        lower.contains("within range")

    val isFollowUpCandidate = history.isNotEmpty() &&
        (hasPronounReference || hasEllipsisStarter || substantiveCurrentTokens.size <= 1)

    if (!isFollowUpCandidate) {
        return ResolvedConversationalQuery(
            originalQuestion = trimmed,
            effectiveQueryText = trimmed,
            inheritedTopicTokens = emptyList(),
            inheritedPageNumbers = emptySet(),
            isFollowUpTurn = false
        )
    }

    // Look back at the most recent non-welcome user question and assistant reply
    val previousUserQuestions = history
        .filter { it.isUser && !it.text.equals(trimmed, ignoreCase = true) }
        .takeLast(2)
    val previousAssistantReplies = history
        .filter { !it.isUser && !it.id.startsWith("welcome_") && !it.isStreaming && !it.isError }
        .takeLast(2)

    val inheritedTokens = mutableListOf<String>()
    for (prevUser in previousUserQuestions.asReversed()) {
        val tokens = prevUser.text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9.%$]+"))
            .map { it.trim('.', ',', '$', '%') }
            .filter { it.length >= 3 && it !in ragStopWords && it !in followUpPronouns }
        for (t in tokens) {
            if (t !in inheritedTokens && t !in substantiveCurrentTokens) {
                inheritedTokens.add(t)
            }
        }
    }

    // If previous user query was generic, also pull top capitalized/domain entities from previous assistant answer
    if (inheritedTokens.size < 3) {
        for (prevAssistant in previousAssistantReplies.asReversed()) {
            val firstLines = prevAssistant.text.lines().take(3).joinToString(" ")
            val tokens = firstLines.lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 4 && it !in ragStopWords && it !in followUpPronouns }
            for (t in tokens.take(5)) {
                if (t !in inheritedTokens && t !in substantiveCurrentTokens) {
                    inheritedTokens.add(t)
                }
            }
        }
    }

    val inheritedPages = previousAssistantReplies
        .flatMap { it.pageCitations }
        .mapNotNull { citation ->
            Regex("Page\\s+(\\d+)", RegexOption.IGNORE_CASE).find(citation)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        .toSet()

    val topInherited = inheritedTokens.take(5)
    val effectiveQuery = if (topInherited.isNotEmpty()) {
        "$trimmed ${topInherited.joinToString(" ")}"
    } else {
        trimmed
    }

    return ResolvedConversationalQuery(
        originalQuestion = trimmed,
        effectiveQueryText = effectiveQuery,
        inheritedTopicTokens = topInherited,
        inheritedPageNumbers = inheritedPages,
        isFollowUpTurn = topInherited.isNotEmpty() || inheritedPages.isNotEmpty()
    )
}

private val followUpPronouns = setOf(
    "it", "its", "that", "this", "they", "them", "their", "these", "those", "there", "same"
)

private fun buildExtractionResultFromRawPages(
    file: PhoneFileItem,
    pagesText: List<String>
): DocumentExtractionResult {
    val docUri = runCatching { Uri.parse("file://${file.fullPath}") }.getOrElse { Uri.EMPTY }
    val rawPages = pagesText.mapIndexed { idx, rawPage ->
        val normalized = TextNormalizer.normalize(rawPage)
        ExtractedPage(
            pageNumber = idx + 1,
            rawText = rawPage,
            normalizedText = normalized,
            source = ExtractionSource.PDF_TEXT
        )
    }
    val (cleanedPages, removedHeadersCount) = TextNormalizer.stripCrossPageRunningHeadersAndFooters(rawPages)
    val chunks = TextChunker.chunkPages(
        documentUri = docUri,
        pages = cleanedPages,
        maxChunkChars = 800,
        overlapChars = 100
    )
    return DocumentExtractionResult(
        documentUri = docUri,
        totalPages = cleanedPages.size,
        pages = cleanedPages,
        chunks = chunks,
        removedRunningHeadersCount = removedHeadersCount
    )
}

private val ragStopWords = setOf(
    "the", "and", "for", "with", "that", "this", "from", "what", "when", "where",
    "which", "who", "how", "are", "was", "were", "does", "did", "can", "you",
    "tell", "about", "into", "pdf", "document", "file", "page", "pages", "please",
    "give", "show", "find", "list", "all", "any", "have", "has", "had", "been",
    "would", "could", "should", "there", "their", "they", "them", "these", "those"
)

private val domainSynonymGroups: List<Set<String>> = listOf(
    setOf("total", "amount", "balance", "cost", "price", "fee", "sum", "paid", "payment", "invoice", "charge", "expense", "credit", "debit", "due", "subtotal"),
    setOf("date", "when", "time", "day", "month", "year", "period", "schedule", "deadline", "timestamp", "issued"),
    setOf("summary", "summarize", "overview", "abstract", "synopsis", "main", "purpose", "topic", "subject", "about"),
    setOf("finding", "findings", "result", "results", "conclusion", "conclusions", "outcome", "value", "values", "impression", "diagnosis", "status", "normal", "range", "reference"),
    setOf("number", "numbers", "figure", "figures", "metric", "metrics", "statistic", "statistics", "count", "rate", "percentage", "level", "range", "measurement"),
    setOf("sugar", "glucose", "glycemic", "hba1c", "fasting", "metabolic", "diabetes"),
    setOf("blood", "hemoglobin", "hematology", "cbc", "cholesterol", "lipid", "hdl", "ldl", "wbc", "rbc", "platelet"),
    setOf("author", "who", "person", "patient", "client", "customer", "company", "organization", "vendor", "provider", "name")
)

private fun expandQueryTerms(queryTokens: List<String>): Set<String> {
    val expanded = mutableSetOf<String>()
    for (token in queryTokens) {
        expanded.add(token)
        if (token.length >= 5) {
            expanded.add(token.take(token.length - 1))
        }
        for (group in domainSynonymGroups) {
            if (token in group) {
                expanded.addAll(group)
            }
        }
    }
    return expanded
}

private fun isBoilerplateMetadataLine(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.startsWith("DOCUMENT METADATA", ignoreCase = true) ||
        trimmed.startsWith("• File Name:", ignoreCase = true) ||
        trimmed.startsWith("• Storage Path:", ignoreCase = true) ||
        trimmed.startsWith("• Volume:", ignoreCase = true) ||
        trimmed.startsWith("• File Size:", ignoreCase = true) ||
        trimmed.startsWith("• Last Modified:", ignoreCase = true) ||
        trimmed.matches(Regex("^Page\\s+\\d+(\\s+of\\s+\\d+)?$", RegexOption.IGNORE_CASE))
}

internal fun retrieveRelevantPdfPassages(
    pagesText: List<String>,
    question: String
): List<PdfPassageMatch> {
    if (pagesText.isEmpty()) return emptyList()
    val dummyUri = Uri.EMPTY
    val rawPages = pagesText.mapIndexed { index, text ->
        ExtractedPage(
            pageNumber = index + 1,
            rawText = text,
            normalizedText = TextNormalizer.normalize(text),
            source = ExtractionSource.PDF_TEXT
        )
    }
    val (pages, removedHeaders) = TextNormalizer.stripCrossPageRunningHeadersAndFooters(rawPages)
    val chunks = TextChunker.chunkPages(dummyUri, pages, maxChunkChars = 800, overlapChars = 100)
    val extraction = DocumentExtractionResult(
        documentUri = dummyUri,
        totalPages = pages.size,
        pages = pages,
        chunks = chunks,
        removedRunningHeadersCount = removedHeaders
    )
    return retrieveRelevantChunksAndPassages(extraction, question)
}

/**
 * Multi-signal BM25/IDF + Character 3-Gram Fuzzy Similarity + 5-Word Phrase Proximity Window +
 * Section-Heading Inheritance + Maximal Marginal Relevance (MMR) Diversity Re-Ranking.
 */
internal fun retrieveRelevantChunksAndPassages(
    extraction: DocumentExtractionResult,
    question: String,
    preferredPages: Set<Int> = emptySet()
): List<PdfPassageMatch> {
    if (extraction.pages.isEmpty()) return emptyList()

    val lowerQ = question.lowercase(Locale.ROOT)
    val queryTokens = lowerQ
        .split(Regex("[^a-z0-9.%$]+"))
        .map { it.trim('.', ',', '$', '%') }
        .filter { it.length >= 2 && it !in ragStopWords }
        .distinct()

    val queryTrigrams = extractCharTrigrams(queryTokens.joinToString(" "))
    val expandedTerms = expandQueryTerms(queryTokens)

    // Detect explicit page number mention in query (e.g., "on page 2")
    val explicitPageTarget = Regex("\\bpage\\s+(\\d+)\\b", RegexOption.IGNORE_CASE)
        .find(lowerQ)?.groupValues?.getOrNull(1)?.toIntOrNull()

    val wantsNumbers = lowerQ.contains("number") || lowerQ.contains("amount") ||
        lowerQ.contains("total") || lowerQ.contains("balance") ||
        lowerQ.contains("price") || lowerQ.contains("cost") ||
        lowerQ.contains("value") || lowerQ.contains("count") ||
        lowerQ.contains("how much") || lowerQ.contains("how many") ||
        lowerQ.contains("rate") || lowerQ.contains("level") || lowerQ.contains("score")

    val wantsDates = lowerQ.contains("date") || lowerQ.contains("when") ||
        lowerQ.contains("year") || lowerQ.contains("month") || lowerQ.contains("day") ||
        lowerQ.contains("time")

    val wantsFindings = lowerQ.contains("finding") || lowerQ.contains("result") ||
        lowerQ.contains("conclusion") || lowerQ.contains("main") ||
        lowerQ.contains("key") || lowerQ.contains("important") || lowerQ.contains("summary") ||
        lowerQ.contains("normal") || lowerQ.contains("range")

    // Collect candidate sentences annotated with their inherited active section heading
    data class CandidateSentence(
        val pageNumber: Int,
        val text: String,
        val tokens: List<String>,
        val sectionHeading: String?,
        val sectionTokens: Set<String>,
        val trigrams: Set<String>,
        val source: ExtractionSource
    )

    val allCandidates = mutableListOf<CandidateSentence>()
    for (page in extraction.pages) {
        val cleanPageLines = page.normalizedText
            .lines()
            .map { it.trim() }
            .filter { it.length >= 6 && !isBoilerplateMetadataLine(it) }

        val cleanedPageBody = cleanPageLines.joinToString("\n")
        val annotatedUnits = TextChunker.extractSectionAnnotatedSentences(cleanedPageBody)

        for ((rawUnit, sectionHeading) in annotatedUnits) {
            val subUnits = if (rawUnit.contains('\n') && rawUnit.length > 180) {
                rawUnit.lines().map { it.trim() }.filter { it.length >= 10 }
            } else {
                listOf(rawUnit.replace('\n', ' ').trim())
            }

            val secTokens = sectionHeading
                ?.lowercase(Locale.ROOT)
                ?.split(Regex("[^a-z0-9]+"))
                ?.filter { it.length >= 2 && it !in ragStopWords }
                ?.toSet()
                ?: emptySet()

            for (sent in subUnits) {
                if (sent.length < 10 || isBoilerplateMetadataLine(sent)) continue
                val sentTokens = sent.lowercase(Locale.ROOT)
                    .split(Regex("[^a-z0-9]+"))
                    .filter { it.length >= 2 }
                if (sentTokens.isNotEmpty()) {
                    allCandidates.add(
                        CandidateSentence(
                            pageNumber = page.pageNumber,
                            text = sent,
                            tokens = sentTokens,
                            sectionHeading = sectionHeading,
                            sectionTokens = secTokens,
                            trigrams = extractCharTrigrams(sent),
                            source = page.source
                        )
                    )
                }
            }
        }
    }

    if (allCandidates.isEmpty()) return emptyList()

    // Compute inverse document frequency (IDF) across candidate sentences + inherited section headers
    val totalDocs = allCandidates.size.coerceAtLeast(1)
    val docFreq = mutableMapOf<String, Int>()
    for (candidate in allCandidates) {
        for (uniqueToken in (candidate.tokens.toSet() + candidate.sectionTokens)) {
            docFreq[uniqueToken] = (docFreq[uniqueToken] ?: 0) + 1
        }
    }

    fun idf(term: String): Double {
        val df = docFreq[term] ?: 0
        return ln(1.0 + (totalDocs - df + 0.5) / (df + 0.5))
    }

    val scoredMatches = allCandidates.mapIndexed { idx, candidate ->
        val lowerSent = candidate.text.lowercase(Locale.ROOT)
        val tokenSet = candidate.tokens.toSet()
        var score = 0.0

        // 1. Exact, Stem & Inherited Section-Heading Matches (weighted by IDF)
        var exactQueryHits = 0
        var sectionHeaderHits = 0
        for (qToken in queryTokens) {
            val weight = idf(qToken).coerceAtLeast(0.8)
            when {
                qToken in tokenSet -> {
                    score += 3.2 * weight
                    exactQueryHits++
                }
                qToken.length >= 4 && tokenSet.any { it.startsWith(qToken.take(4)) || qToken.startsWith(it.take(4)) } -> {
                    score += 2.1 * weight
                    exactQueryHits++
                }
                lowerSent.contains(qToken) -> {
                    score += 1.45 * weight
                    exactQueryHits++
                }
            }

            // Parent-Child Section-Heading Context Inheritance Bonus
            if (qToken in candidate.sectionTokens ||
                (qToken.length >= 4 && candidate.sectionTokens.any { it.startsWith(qToken.take(4)) })
            ) {
                score += 1.75 * weight
                sectionHeaderHits++
            }
        }

        // 2. 5-Word Sliding-Window Phrase Proximity Bonus
        if (queryTokens.size >= 2 && exactQueryHits >= 2) {
            val proximityBonus = computePhraseProximityBonus(candidate.tokens, queryTokens)
            score += exactQueryHits * 1.6 + proximityBonus
        } else if (exactQueryHits >= 1 && sectionHeaderHits >= 1) {
            score += 1.8
        }

        // 3. Character 3-Gram Fuzzy Cosine Similarity (resilient to OCR typos & spelling variations)
        if (queryTrigrams.isNotEmpty() && candidate.trigrams.isNotEmpty()) {
            val trigramSim = computeTrigramCosineSimilarity(queryTrigrams, candidate.trigrams)
            if (trigramSim >= 0.14) {
                score += trigramSim * 3.4
            }
        }

        // 4. Domain Synonym Expansion Matches
        for (syn in expandedTerms) {
            if (syn !in queryTokens) {
                if (syn in tokenSet) {
                    score += 0.85 * idf(syn).coerceAtLeast(0.5)
                } else if (syn in candidate.sectionTokens) {
                    score += 0.55 * idf(syn).coerceAtLeast(0.5)
                }
            }
        }

        // 5. Quantitative / Date / Table Row / Finding Intent Alignment
        val hasDigits = candidate.text.any { it.isDigit() }
        val isTableRow = candidate.text.contains(" | ")
        val hasCurrencyOrPercent = candidate.text.any { it == '$' || it == '%' || it == '€' || it == '£' || it == '₹' }
        val hasDatePattern = Regex(
            "\\b(\\d{1,4}[/-]\\d{1,2}[/-]\\d{1,4}|20\\d{2}|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(candidate.text)

        if (wantsNumbers && hasDigits) {
            score += if (hasCurrencyOrPercent || isTableRow) 2.5 else 1.5
        }
        if (wantsDates && (hasDatePattern || hasDigits)) {
            score += if (hasDatePattern) 2.3 else 0.9
        }
        if (wantsFindings && (hasDigits || isTableRow || candidate.tokens.size in 7..38)) {
            score += 0.95
        }

        // 6. Explicit or Conversational Follow-up Page Preference
        if (explicitPageTarget != null && candidate.pageNumber == explicitPageTarget) {
            score += 3.5
        } else if (candidate.pageNumber in preferredPages && (exactQueryHits > 0 || sectionHeaderHits > 0)) {
            score += 1.15
        }

        // 7. Information Density & Sentence Quality Score
        val wordCount = candidate.tokens.size
        val isAllCapsHeader = candidate.text.count { it.isUpperCase() }.toFloat() /
            candidate.text.count { it.isLetter() }.coerceAtLeast(1).toFloat() > 0.78f
        if ((wordCount in 5..45 || isTableRow) && !isAllCapsHeader) {
            score += 0.65
        } else if (isAllCapsHeader && exactQueryHits == 0) {
            score -= 0.55
        }

        if (hasDigits) {
            score += 0.35
        }

        score += (1.0 / (idx + 10.0)) * 0.05

        PdfPassageMatch(
            pageNumber = candidate.pageNumber,
            snippet = candidate.text.take(360),
            score = score,
            source = candidate.source,
            sectionHeading = candidate.sectionHeading
        )
    }

    // 8. Maximal Marginal Relevance (MMR) Diversity Re-Ranking (lambda = 0.72)
    return selectDiverseWithMmr(
        candidates = scoredMatches,
        lambda = 0.72,
        topK = 5
    )
}

/**
 * Computes a sliding-window phrase proximity bonus when multiple query tokens appear within
 * a 5-word window inside the sentence.
 */
private fun computePhraseProximityBonus(
    sentenceTokens: List<String>,
    queryTokens: List<String>
): Double {
    if (sentenceTokens.size <= 1 || queryTokens.size <= 1) return 0.0
    val qPrefixes = queryTokens.map { if (it.length >= 4) it.take(4) else it }
    val windowSize = 5
    var maxWindowMatches = 0

    for (start in sentenceTokens.indices) {
        val end = (start + windowSize).coerceAtMost(sentenceTokens.size)
        val windowSlice = sentenceTokens.subList(start, end)
        val matchedQueries = qPrefixes.count { qPref ->
            windowSlice.any { sTok -> sTok == qPref || (qPref.length >= 4 && sTok.startsWith(qPref)) }
        }
        if (matchedQueries > maxWindowMatches) {
            maxWindowMatches = matchedQueries
        }
    }

    return if (maxWindowMatches >= 2) {
        maxWindowMatches * 1.35
    } else {
        0.0
    }
}

private fun extractCharTrigrams(text: String): Set<String> {
    val normalized = text.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
    if (normalized.length < 3) return if (normalized.isEmpty()) emptySet() else setOf(normalized)
    val trigrams = mutableSetOf<String>()
    for (i in 0..normalized.length - 3) {
        trigrams.add(normalized.substring(i, i + 3))
    }
    return trigrams
}

private fun computeTrigramCosineSimilarity(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    var intersection = 0
    val smaller = if (a.size <= b.size) a else b
    val larger = if (a.size <= b.size) b else a
    for (gram in smaller) {
        if (gram in larger) intersection++
    }
    if (intersection == 0) return 0.0
    return intersection.toDouble() / kotlin.math.sqrt(a.size.toDouble() * b.size.toDouble())
}

/**
 * Selects top-K passages using Maximal Marginal Relevance (MMR):
 *   MMR(s_i) = lambda * Rel(s_i) - (1 - lambda) * max_{s_j in Selected} Sim(s_i, s_j)
 * ensuring retrieved answers cover distinct facts rather than repeating near-duplicate sentences.
 */
private fun selectDiverseWithMmr(
    candidates: List<PdfPassageMatch>,
    lambda: Double = 0.72,
    topK: Int = 5
): List<PdfPassageMatch> {
    val dedupedPool = candidates
        .sortedByDescending { it.score }
        .distinctBy { it.snippet.lowercase(Locale.ROOT) }
        .take(22)
        .toMutableList()

    if (dedupedPool.size <= 1) return dedupedPool

    val maxScore = dedupedPool.first().score.coerceAtLeast(1e-6)
    val selected = mutableListOf<PdfPassageMatch>()
    val selectedTrigrams = mutableListOf<Set<String>>()

    // Pick the single highest-relevance passage first
    val first = dedupedPool.removeAt(0)
    selected.add(first)
    selectedTrigrams.add(extractCharTrigrams(first.snippet))

    while (selected.size < topK && dedupedPool.isNotEmpty()) {
        var bestIdx = 0
        var bestMmrScore = Double.NEGATIVE_INFINITY

        for ((idx, candidate) in dedupedPool.withIndex()) {
            val candTrigrams = extractCharTrigrams(candidate.snippet)
            val maxSimToSelected = selectedTrigrams.maxOfOrNull { selTrigrams ->
                computeTrigramCosineSimilarity(candTrigrams, selTrigrams)
            } ?: 0.0

            // Hard skip near-identical sentences (> 0.78 trigram overlap)
            if (maxSimToSelected > 0.78) continue

            val normalizedRelevance = candidate.score / maxScore
            val mmr = lambda * normalizedRelevance - (1.0 - lambda) * maxSimToSelected
            if (mmr > bestMmrScore) {
                bestMmrScore = mmr
                bestIdx = idx
            }
        }

        if (bestMmrScore == Double.NEGATIVE_INFINITY) break
        val chosen = dedupedPool.removeAt(bestIdx)
        selected.add(chosen)
        selectedTrigrams.add(extractCharTrigrams(chosen.snippet))
    }

    return selected
}

/**
 * Extractive TextRank / Centroid + Intent-Aware Synthesizer for Local Gemma E2B.
 * Produces rich, multi-paragraph, page- and section-cited answers grounded in the extracted PDF chunks.
 */
private fun synthesizeLocalGemmaResponse(
    file: PhoneFileItem,
    extraction: DocumentExtractionResult,
    topPassages: List<PdfPassageMatch>,
    question: String,
    conversationalQuery: ResolvedConversationalQuery? = null
): String {
    val pages = extraction.pages
    val totalPages = pages.size.coerceAtLeast(1)
    val ocrPageCount = pages.count {
        it.source == ExtractionSource.ML_KIT_OCR || it.source == ExtractionSource.FUSED_HYBRID_OCR
    }
    val digitalPageCount = pages.count { it.source == ExtractionSource.PDF_TEXT }

    val provenanceSummary = when {
        ocrPageCount > 0 && digitalPageCount > 0 ->
            "$totalPages pages ($digitalPageCount digital text + $ocrPageCount Visual/Fused OCR)"
        ocrPageCount > 0 ->
            "$totalPages ${if (totalPages == 1) "page" else "pages"} (extracted via on-device ML Kit Visual OCR)"
        else ->
            "$totalPages ${if (totalPages == 1) "page" else "pages"} (extracted via PDFBox digital text layer)"
    }

    val rawLowerQ = question.lowercase(Locale.ROOT)
    val isSummaryQuery = rawLowerQ.contains("summarize") || rawLowerQ.contains("summary") ||
        rawLowerQ.contains("overview") || rawLowerQ.contains("what is this") ||
        rawLowerQ.contains("about") || rawLowerQ.contains("tl;dr") || rawLowerQ.contains("main points") ||
        rawLowerQ.contains("executive")

    val isNumbersOrDatesQuery = rawLowerQ.contains("key numbers") || rawLowerQ.contains("dates & totals") ||
        rawLowerQ.contains("numbers, dates") || rawLowerQ.contains("all numbers") ||
        rawLowerQ.contains("amounts") || rawLowerQ.contains("figures") ||
        rawLowerQ.contains("metrics")

    val isActionItemsOrAnomaliesQuery = rawLowerQ.contains("action items") || rawLowerQ.contains("anomalies") ||
        rawLowerQ.contains("recommendations") || rawLowerQ.contains("next steps") ||
        rawLowerQ.contains("warnings") || rawLowerQ.contains("abnormal")

    val isFindingsQuery = rawLowerQ.contains("main findings") || rawLowerQ.contains("findings & values") ||
        rawLowerQ.contains("key findings") || rawLowerQ.contains("conclusions") ||
        rawLowerQ.contains("takeaways")

    val isPageOutlineQuery = rawLowerQ.contains("list all pages") || rawLowerQ.contains("pages & sections") ||
        rawLowerQ.contains("table of contents") || rawLowerQ.contains("sections")

    // 1. Comprehensive Multi-Section Executive Document Summary Mode
    if (isSummaryQuery) {
        return buildStructuredExecutiveSummary(
            fileName = file.name,
            extraction = extraction,
            provenanceSummary = provenanceSummary
        )
    }

    // 2. Action Items, Recommendations & Flagged Anomalies Mode
    if (isActionItemsOrAnomaliesQuery) {
        val actionItems = extractActionItemsAndAnomalies(extraction, maxItems = 8)
        val keyValues = extractStructuredKeyValuePairs(extraction, maxPairs = 6)
        return buildString {
            append("Action Items, Recommendations & Flagged Alerts — ${file.name} ($provenanceSummary):\n\n")
            if (actionItems.isNotEmpty()) {
                actionItems.forEach { (pageNum, item) ->
                    append("• [Page $pageNum] $item\n")
                }
            } else {
                val salient = extractSalientSummarySentences(extraction, maxSentences = 4)
                salient.forEach { (pageNum, sentence) ->
                    append("• [Page $pageNum] $sentence\n")
                }
            }
            if (keyValues.isNotEmpty()) {
                append("\nStructured Fields & Reference Values:\n")
                keyValues.forEach { (pageNum, pair) ->
                    append("• [Page $pageNum] $pair\n")
                }
            }
        }.trim()
    }

    // 3. Key Numbers, Dates & Totals Mode
    if (isNumbersOrDatesQuery) {
        val quantFacts = extractQuantitativeFacts(extraction, maxFacts = 10)
        val keyValues = extractStructuredKeyValuePairs(extraction, maxPairs = 6)
        return buildString {
            append("Key Numbers, Dates & Totals in ${file.name} ($provenanceSummary):\n\n")
            if (quantFacts.isNotEmpty()) {
                quantFacts.forEach { (pageNum, fact) ->
                    append("• [Page $pageNum] $fact\n")
                }
            } else if (topPassages.isNotEmpty()) {
                topPassages.forEach { match ->
                    val secPrefix = match.sectionHeading?.let { "[$it] " }.orEmpty()
                    append("• [Page ${match.pageNumber}] $secPrefix${match.snippet}\n")
                }
            } else {
                append("No explicit numerical totals, currency amounts, or dates were detected in the extracted text of ${file.name}.")
            }
            if (keyValues.isNotEmpty()) {
                append("\nExtracted Key-Value Fields:\n")
                keyValues.forEach { (pageNum, kv) ->
                    append("• [Page $pageNum] $kv\n")
                }
            }
        }.trim()
    }

    // 4. Main Findings & Values Mode
    if (isFindingsQuery) {
        val docCategory = classifyDocumentCategory(file.name, extraction)
        val salient = extractSalientSummarySentences(extraction, maxSentences = 6)
        val quantFacts = extractQuantitativeFacts(extraction, maxFacts = 5)
        val actionItems = extractActionItemsAndAnomalies(extraction, maxItems = 4)
        return buildString {
            append("Main Findings & Values — ${file.name}\n")
            append("• Document Category: $docCategory ($provenanceSummary)\n\n")
            if (salient.isNotEmpty()) {
                append("Core Findings:\n")
                salient.forEach { (pageNum, sentence) ->
                    append("• [Page $pageNum] $sentence\n")
                }
            }
            if (quantFacts.isNotEmpty()) {
                append("\nKey Recorded Values & Metrics:\n")
                quantFacts.forEach { (pageNum, fact) ->
                    append("• [Page $pageNum] $fact\n")
                }
            }
            if (actionItems.isNotEmpty()) {
                append("\nConclusions, Alerts & Next Steps:\n")
                actionItems.forEach { (pageNum, item) ->
                    append("• [Page $pageNum] $item\n")
                }
            }
        }.trim()
    }

    // 5. Page & Section Outline Mode
    if (isPageOutlineQuery) {
        return buildString {
            append("Pages & Sections Outline — ${file.name} ($provenanceSummary):\n\n")
            pages.forEach { page ->
                val sourceTag = page.source.badgeLabel
                val heading = extractPageHeadingOrTitle(page)
                val digest = summarizeSinglePage(page)
                append("• Page ${page.pageNumber} ($sourceTag) — $heading\n")
                if (page.detectedSections.size > 1) {
                    append("  Sections: ${page.detectedSections.joinToString(" • ")}\n")
                }
                if (digest.isNotBlank() && !digest.equals(heading, ignoreCase = true)) {
                    append("  $digest\n")
                }
            }
        }.trim()
    }

    // 6. Focused Question Answering over MMR-Ranked Sentence Chunks + Conversational Follow-up Context
    val bestMatches = topPassages.filter { it.score > 0.35 }.ifEmpty { topPassages.take(3) }
    return if (bestMatches.isNotEmpty()) {
        val primary = bestMatches.first()
        val supporting = bestMatches.drop(1).take(3)

        // Locate the surrounding 800-char TextChunk for richer paragraph context
        val matchingChunk = extraction.chunks.firstOrNull { chunk ->
            chunk.pageNumber == primary.pageNumber &&
                chunk.text.contains(primary.snippet.take(36), ignoreCase = true)
        }

        buildString {
            if (conversationalQuery != null && conversationalQuery.isFollowUpTurn && conversationalQuery.inheritedTopicTokens.isNotEmpty()) {
                append("Follow-up Context: Continuing from ")
                append(conversationalQuery.inheritedTopicTokens.joinToString(", "))
                append("\n\n")
            }

            val sectionLabel = primary.sectionHeading?.let { " • Section: $it" }.orEmpty()
            append("Direct Answer (Page ${primary.pageNumber} • ${primary.source.badgeLabel}$sectionLabel):\n")
            append(primary.snippet)
            if (!primary.snippet.endsWith('.') && !primary.snippet.endsWith('!') && !primary.snippet.endsWith('?')) {
                append('.')
            }

            if (supporting.isNotEmpty()) {
                append("\n\nRelated Findings Across Document (MMR Diverse):\n")
                supporting.forEach { match ->
                    val secTag = match.sectionHeading?.let { " [$it]" }.orEmpty()
                    append("• [Page ${match.pageNumber}$secTag] ${match.snippet}\n")
                }
            }

            if (matchingChunk != null && matchingChunk.text.length > primary.snippet.length + 40) {
                append("\nSupporting Paragraph Context (Page ${matchingChunk.pageNumber}, Chunk #${matchingChunk.chunkIndex + 1}):\n")
                append(matchingChunk.text.take(460))
            }
        }.trim()
    } else {
        val fallbackSummary = extractSalientSummarySentences(extraction, maxSentences = 3)
        buildString {
            append("No exact phrase match for \"$question\" was found in ${file.name} ($provenanceSummary).\n\n")
            if (fallbackSummary.isNotEmpty()) {
                append("Closest Key Statements in Document:\n")
                fallbackSummary.forEach { (pageNum, sentence) ->
                    append("• [Page $pageNum] $sentence\n")
                }
            }
        }.trim()
    }
}

/**
 * Builds a structured, multi-section Executive Summary for any PDF document.
 * Shared by both the PDF Chat Summarizer and the 1-tap "Summarize PDF" card inside the PDF Reader.
 */
internal fun buildStructuredExecutiveSummary(
    fileName: String,
    extraction: DocumentExtractionResult,
    provenanceSummary: String? = null
): String {
    val pages = extraction.pages
    val totalPages = pages.size.coerceAtLeast(1)
    val ocrPageCount = pages.count {
        it.source == ExtractionSource.ML_KIT_OCR || it.source == ExtractionSource.FUSED_HYBRID_OCR
    }
    val digitalPageCount = pages.count { it.source == ExtractionSource.PDF_TEXT }
    val resolvedProvenance = provenanceSummary ?: when {
        ocrPageCount > 0 && digitalPageCount > 0 ->
            "$totalPages pages ($digitalPageCount digital text + $ocrPageCount Visual/Fused OCR)"
        ocrPageCount > 0 ->
            "$totalPages ${if (totalPages == 1) "page" else "pages"} (Visual OCR via ML Kit)"
        else ->
            "$totalPages ${if (totalPages == 1) "page" else "pages"} (Digital Text via PDFBox)"
    }

    val fullDocumentText = pages.joinToString("\n\n") { it.normalizedText }
    val posSummary = OnDevicePosTagger.analyzeText(fullDocumentText)
    val readingTimeMin = (posSummary.totalWords / 200.0).coerceAtLeast(0.5)
    val readingTimeLabel = if (readingTimeMin < 1.0) "< 1 min read" else "${readingTimeMin.toInt()} min read"

    val docCategory = classifyDocumentCategory(fileName, extraction)
    val topTopics = extractTopDocumentKeyphrases(extraction, limit = 7)
    val salientSentences = extractSalientSummarySentences(extraction, maxSentences = 5)
    val keyValuePairs = extractStructuredKeyValuePairs(extraction, maxPairs = 6)
    val keyMetrics = extractQuantitativeFacts(extraction, maxFacts = 6)
    val actionItems = extractActionItemsAndAnomalies(extraction, maxItems = 5)
    val allSections = pages.flatMap { it.detectedSections }.distinct().take(8)

    val thesisLine = salientSentences.firstOrNull()?.second
        ?: pages.firstOrNull()?.let { summarizeSinglePage(it) }
        ?: "Structured document containing ${posSummary.totalWords} words across $totalPages pages."

    return buildString {
        append("Executive Summary — $fileName\n")
        append("• Document Type: $docCategory\n")
        append("• Core Purpose: $thesisLine\n")
        append("• Scope & Provenance: $resolvedProvenance • ${posSummary.totalWords} words ($readingTimeLabel) • ${extraction.chunks.size} indexed chunks")
        if (extraction.removedRunningHeadersCount > 0) {
            append(" (${extraction.removedRunningHeadersCount} repeated headers/footers stripped)")
        }
        append('\n')
        if (allSections.isNotEmpty()) {
            append("• Detected Sections: ${allSections.joinToString(" • ")}\n")
        }
        if (topTopics.isNotEmpty()) {
            append("• Key Concepts & Entities: ${topTopics.joinToString(", ")}\n")
        }

        if (keyValuePairs.isNotEmpty()) {
            append("\n1. Structured Fields & Identifiers:\n")
            keyValuePairs.forEach { (pageNum, kv) ->
                append("• [Page $pageNum] $kv\n")
            }
        }

        if (salientSentences.isNotEmpty()) {
            append("\n2. Key Highlights & Main Findings:\n")
            salientSentences.forEach { (pageNum, sentence) ->
                append("• [Page $pageNum] $sentence\n")
            }
        }

        if (keyMetrics.isNotEmpty()) {
            append("\n3. Notable Numbers, Dates, Tables & Measurements:\n")
            keyMetrics.forEach { (pageNum, fact) ->
                append("• [Page $pageNum] $fact\n")
            }
        }

        if (actionItems.isNotEmpty()) {
            append("\n4. Action Items, Recommendations & Flagged Alerts:\n")
            actionItems.forEach { (pageNum, item) ->
                append("• [Page $pageNum] $item\n")
            }
        }

        append("\n5. Page-by-Page Breakdown:\n")
        pages.take(10).forEach { page ->
            val heading = extractPageHeadingOrTitle(page)
            val pageDigest = summarizeSinglePage(page)
            val badge = when (page.source) {
                ExtractionSource.ML_KIT_OCR -> " [Visual OCR]"
                ExtractionSource.FUSED_HYBRID_OCR -> " [Hybrid Fused]"
                else -> ""
            }
            if (!pageDigest.equals(heading, ignoreCase = true) && !pageDigest.startsWith(heading, ignoreCase = true)) {
                append("• Page ${page.pageNumber}$badge ($heading): $pageDigest\n")
            } else {
                append("• Page ${page.pageNumber}$badge: $pageDigest\n")
            }
        }

        if (posSummary.totalWords >= 10) {
            val probPct = String.format(Locale.US, "%.1f%%", posSummary.probabilityOfFiction * 100.0)
            val radjpronStr = String.format(Locale.US, "%.3f", posSummary.rAdjPron)
            val radvadjStr = String.format(Locale.US, "%.3f", posSummary.rAdvAdj)
            append("\n6. Linguistic & Genre Profile:\n")
            append("• Classification: ${posSummary.predictedGenreLabel} (Probability of Fiction = $probPct, RADJPRON = $radjpronStr, RADVADJ = $radvadjStr)\n")
            append("• POS Breakdown: ${posSummary.nounCount} Nouns • ${posSummary.verbCount} Verbs • ${posSummary.adjectiveCount} Adjectives • ${posSummary.adverbCount} Adverbs • ${posSummary.pronounCount} Pronouns")
        }
    }.trim()
}

/**
 * Automatically classifies the PDF document domain based on filename, section headers, and lexical cues.
 */
internal fun classifyDocumentCategory(
    fileName: String,
    extraction: DocumentExtractionResult
): String {
    val sampleText = (fileName + "\n" + extraction.pages.take(3).joinToString("\n") { it.normalizedText })
        .lowercase(Locale.ROOT)

    return when {
        listOf("hemoglobin", "hematology", "pathology", "patient", "specimen", "mg/dl", "reference interval", "diagnostic", "clinical", "cholesterol", "glucose").count { sampleText.contains(it) } >= 2 ->
            "Medical / Clinical Lab Report"
        listOf("invoice", "subtotal", "amount due", "billing", "receipt", "tax", "payment", "remittance", "total due", "balance").count { sampleText.contains(it) } >= 2 ->
            "Financial Invoice / Billing Statement"
        listOf("abstract", "methodology", "algorithm", "experiment", "references", "theorem", "evaluation", "architecture", "dataset", "neural").count { sampleText.contains(it) } >= 2 ->
            "Technical / Academic Paper & Specification"
        listOf("agreement", "contract", "clause", "liability", "indemnity", "hereby", "jurisdiction", "warranty", "terms and conditions").count { sampleText.contains(it) } >= 2 ->
            "Legal Agreement / Policy Document"
        listOf("curriculum vitae", "resume", "experience", "education", "skills", "certifications", "employment").count { sampleText.contains(it) } >= 3 ->
            "Resume / Curriculum Vitae"
        listOf("itinerary", "boarding", "flight", "reservation", "check-in", "passenger", "booking").count { sampleText.contains(it) } >= 2 ->
            "Travel / Booking Itinerary"
        else ->
            "General Document / Reference Report"
    }
}

/**
 * Extracts structured `Key: Value` or `Column 1 → Column 2` fields (e.g., Patient Name, Invoice #,
 * Account ID, Date, Status, Total Amount) across all pages.
 */
internal fun extractStructuredKeyValuePairs(
    extraction: DocumentExtractionResult,
    maxPairs: Int
): List<Pair<Int, String>> {
    val pairs = mutableListOf<Pair<Int, String>>()
    val seenKeys = mutableSetOf<String>()

    for (page in extraction.pages) {
        for (rawLine in page.normalizedText.lines()) {
            val line = rawLine.trim().trimStart('•', '-', '*').trim()
            if (line.length !in 6..140 || isBoilerplateMetadataLine(line)) continue

            // Case 1: Explicit "Key: Value" line
            val colonIdx = line.indexOf(':')
            if (colonIdx in 2..42) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                val keyWords = key.split(Regex("\\s+")).size
                if (keyWords in 1..5 && value.length in 1..85 && key.any { it.isLetter() }) {
                    val normKey = key.lowercase(Locale.ROOT)
                    if (seenKeys.add(normKey)) {
                        pairs.add(page.pageNumber to "$key → $value")
                        if (pairs.size >= maxPairs) return pairs
                    }
                }
            } else if (line.contains(" | ")) {
                // Case 2: Reconstructed spatial table row ("Parameter | Value | Unit/Range")
                val cells = line.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }
                if (cells.size in 2..5 && cells[0].length in 2..42 && cells.drop(1).any { it.any { ch -> ch.isDigit() } }) {
                    val normKey = cells[0].lowercase(Locale.ROOT)
                    if (seenKeys.add(normKey)) {
                        val formatted = "${cells[0]} → ${cells.drop(1).joinToString(" | ")}"
                        pairs.add(page.pageNumber to formatted)
                        if (pairs.size >= maxPairs) return pairs
                    }
                }
            }
        }
    }
    return pairs
}

/**
 * Extracts actionable items, recommendations, deadlines, conclusions, and flagged anomalies
 * (e.g., out-of-range lab values, overdue balances, required follow-ups, or key conclusions).
 */
internal fun extractActionItemsAndAnomalies(
    extraction: DocumentExtractionResult,
    maxItems: Int
): List<Pair<Int, String>> {
    val results = mutableListOf<Pair<Int, String>>()
    val seen = mutableSetOf<String>()
    val actionOrAlertRegex = Regex(
        "\\b(recommend\\w*|action\\w*|follow[- ]?up|advise\\w*|must|required|deadline|due\\s+date|due\\s+by|urgent|warning|note:|important|conclusion\\w*|borderline|elevated|high|low|abnormal|deficit|insufficient|exceed\\w*|re-check|consult|schedule|pay\\s+by)\\b",
        RegexOption.IGNORE_CASE
    )

    for (page in extraction.pages) {
        val sentences = TextChunker.splitIntoSentences(page.normalizedText)
            .flatMap { block ->
                if (block.contains('\n')) block.lines().map { it.trim() } else listOf(block.trim())
            }
            .map { it.trimStart('•', '-', '*').trim() }
            .filter { it.length in 16..260 && !isBoilerplateMetadataLine(it) }

        for (sentence in sentences) {
            if (actionOrAlertRegex.containsMatchIn(sentence)) {
                val key = sentence.lowercase(Locale.ROOT).take(60)
                if (seen.add(key)) {
                    results.add(page.pageNumber to sentence)
                    if (results.size >= maxItems) return results
                }
            }
        }
    }
    return results
}

/**
 * Computes the top substantive keyphrases/terms across all extracted pages using TF-IDF frequency.
 */
private fun extractTopDocumentKeyphrases(
    extraction: DocumentExtractionResult,
    limit: Int
): List<String> {
    val freq = mutableMapOf<String, Int>()
    for (page in extraction.pages) {
        val lines = page.normalizedText.lines().filter { !isBoilerplateMetadataLine(it) }
        for (line in lines) {
            val words = line.lowercase(Locale.ROOT)
                .split(Regex("[^a-z]+"))
                .filter { it.length in 4..20 && it !in ragStopWords }
            for (w in words) {
                freq[w] = (freq[w] ?: 0) + 1
            }
        }
    }
    return freq.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { entry ->
            entry.key.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
}

/**
 * Extractive TextRank / Centroid sentence selector with MMR-style trigram deduplication (< 0.45)
 * that chooses the most informative, high-centrality sentences across the PDF while skipping
 * headers, footers, and metadata boilerplate.
 */
private fun extractSalientSummarySentences(
    extraction: DocumentExtractionResult,
    maxSentences: Int
): List<Pair<Int, String>> {
    val wordFreq = mutableMapOf<String, Int>()
    val candidates = mutableListOf<Pair<Int, String>>()

    for (page in extraction.pages) {
        val cleanBody = page.normalizedText
            .lines()
            .map { it.trim() }
            .filter { it.length >= 12 && !isBoilerplateMetadataLine(it) }
            .joinToString("\n")

        val sentences = TextChunker.splitIntoSentences(cleanBody)
            .flatMap { block ->
                if (block.contains('\n') && block.length > 160) {
                    block.lines().map { it.trim() }
                } else {
                    listOf(block.replace('\n', ' ').trim())
                }
            }
            .filter { it.length in 24..300 && !isBoilerplateMetadataLine(it) }

        for (sentence in sentences) {
            val words = sentence.lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 3 && it !in ragStopWords }
            if (words.size >= 4) {
                candidates.add(page.pageNumber to sentence)
                for (w in words) {
                    wordFreq[w] = (wordFreq[w] ?: 0) + 1
                }
            }
        }
    }

    if (candidates.isEmpty()) return emptyList()

    val scored = candidates.map { (pageNum, sentence) ->
        val words = sentence.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in ragStopWords }
        val centroidScore = words.sumOf { wordFreq[it] ?: 1 }.toDouble() / (words.size.toDouble() + 2.0)
        val hasDigits = if (sentence.any { it.isDigit() }) 1.35 else 1.0
        val isAllCaps = sentence.count { it.isUpperCase() }.toFloat() /
            sentence.count { it.isLetter() }.coerceAtLeast(1).toFloat() > 0.75f
        val penalty = if (isAllCaps) 0.55 else 1.0
        Triple(pageNum, sentence, centroidScore * hasDigits * penalty)
    }

    val selected = mutableListOf<Pair<Int, String>>()
    val selectedTrigrams = mutableListOf<Set<String>>()

    for ((pageNum, sentence, _) in scored.sortedByDescending { it.third }) {
        val candTrigrams = extractCharTrigrams(sentence)
        val tooSimilar = selectedTrigrams.any { existing ->
            computeTrigramCosineSimilarity(candTrigrams, existing) > 0.52
        }
        if (!tooSimilar) {
            selected.add(pageNum to sentence)
            selectedTrigrams.add(candTrigrams)
            if (selected.size >= maxSentences) break
        }
    }

    return selected.sortedBy { it.first }
}

/**
 * Extracts quantitative lines/sentences containing currency figures, percentages, dates,
 * reference ranges, or key-value/table metrics across all pages.
 */
private fun extractQuantitativeFacts(
    extraction: DocumentExtractionResult,
    maxFacts: Int
): List<Pair<Int, String>> {
    val facts = mutableListOf<Pair<Int, String>>()
    val seen = mutableSetOf<String>()
    val metricRegex = Regex(
        "(\\$[0-9,]+(\\.[0-9]{2})?|\\b\\d+(\\.\\d+)?\\s*(%|mg/dL|g/dL|mIU/L|U/L|uL|ng/mL|mL/min|MB|GB|KB|days|months|years|USD|EUR|INR)\\b|\\b\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4}\\b|\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{1,2}\\b)",
        RegexOption.IGNORE_CASE
    )

    for (page in extraction.pages) {
        val lines = page.normalizedText
            .lines()
            .map { it.trim().trimStart('•', '-', '*').trim() }
            .filter { it.length in 10..240 && !isBoilerplateMetadataLine(it) }

        for (line in lines) {
            val isMetricTableLine = line.contains(" | ") && line.any { it.isDigit() }
            if (metricRegex.containsMatchIn(line) || isMetricTableLine || (line.contains(':') && line.any { it.isDigit() })) {
                val key = line.lowercase(Locale.ROOT)
                if (seen.add(key)) {
                    facts.add(page.pageNumber to line)
                    if (facts.size >= maxFacts) return facts
                }
            }
        }
    }
    return facts
}

private fun extractPageHeadingOrTitle(page: ExtractedPage): String {
    if (page.detectedSections.isNotEmpty()) {
        return page.detectedSections.first()
    }
    val lines = page.normalizedText
        .lines()
        .map { it.trim() }
        .filter { it.length >= 5 && !isBoilerplateMetadataLine(it) }
    return lines.firstOrNull { line ->
        !line.startsWith("•") && line.length <= 90
    } ?: lines.firstOrNull()?.take(80) ?: "Page ${page.pageNumber} Content"
}

private fun summarizeSinglePage(page: ExtractedPage): String {
    val lines = page.normalizedText
        .lines()
        .map { it.trim().trimStart('•').trim() }
        .filter { it.length >= 12 && !isBoilerplateMetadataLine(it) }
    if (lines.isEmpty()) return "No readable text found on this page."
    val bodyLines = if (lines.size > 1 && lines[0].length < 75) lines.drop(1) else lines
    return bodyLines.take(2).joinToString(" • ").take(240)
}

@Composable
fun FullScreenPdfChatDialog(
    file: PhoneFileItem,
    onOpenPdfReader: (PhoneFileItem) -> Unit,
    onDismiss: () -> Unit,
    aiRepository: PdfChatAiRepository = remember { FirebaseAiLogicPdfChatRepository() }
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val hybridExtractor = remember(context) { HybridPdfExtractor(context.applicationContext) }

    var selectedEngine by remember(file.id) { mutableStateOf(PdfChatEngineOption.LOCAL_GEMMA_E2B) }
    var forceVisualOcrMode by remember(file.id) { mutableStateOf(false) }
    var extractionResult by remember(file.id) { mutableStateOf<DocumentExtractionResult?>(null) }
    var extractedPages by remember(file.id) { mutableStateOf<List<String>>(emptyList()) }
    var isIndexingPdf by remember(file.id) { mutableStateOf(true) }
    var indexingProgressLabel by remember(file.id) {
        mutableStateOf("Running Hybrid PDF Extractor (PDFBox + ML Kit Visual OCR)…")
    }
    var isGeneratingReply by remember(file.id) { mutableStateOf(false) }
    var questionInput by remember(file.id) { mutableStateOf("") }
    var messages by remember(file.id) { mutableStateOf<List<PdfChatMessage>>(emptyList()) }

    DisposableEffect(file.id) {
        onDispose {
            hybridExtractor.close()
        }
    }

    LaunchedEffect(file.id, forceVisualOcrMode) {
        isIndexingPdf = true
        val config = ExtractionConfig(
            enableOcrFallback = true,
            forceOcrAllPages = forceVisualOcrMode,
            smartVisualOcrFusion = true
        )
        val result = withContext(Dispatchers.IO) {
            hybridExtractor.extractForPhoneFile(
                file = file,
                config = config,
                onPageProgress = { current, total ->
                    indexingProgressLabel = if (forceVisualOcrMode) {
                        "Visual OCR Mode: Scanning Page $current of $total via ML Kit Vision…"
                    } else {
                        "Extracting Page $current of $total (Smart Hybrid PDFBox + Visual OCR)…"
                    }
                }
            )
        }
        extractionResult = result
        val pages = result.pages.map { it.normalizedText }
        extractedPages = pages

        val ocrCount = result.pages.count {
            it.source == ExtractionSource.ML_KIT_OCR || it.source == ExtractionSource.FUSED_HYBRID_OCR
        }
        val digitalCount = result.pages.count { it.source == ExtractionSource.PDF_TEXT }
        val sourceSummary = when {
            forceVisualOcrMode ->
                "${pages.size} pages via Full Visual ML Kit OCR (Image OCR pipeline)"
            ocrCount > 0 && digitalCount > 0 ->
                "$digitalCount digital text pages + $ocrCount Visual/Fused OCR pages"
            ocrCount > 0 ->
                "$ocrCount pages via on-device ML Kit Visual OCR"
            else ->
                "${pages.size} pages via PDFBox digital text layer"
        }

        val welcomeMsg = PdfChatMessage(
            id = "welcome_${file.id}_${forceVisualOcrMode}",
            isUser = false,
            engine = PdfChatEngineOption.LOCAL_GEMMA_E2B,
            text = "Hybrid PDF Extraction complete for ${file.name} (${classifyDocumentCategory(file.name, result)}): extracted $sourceSummary into ${result.chunks.size} sentence-aware chunks. " +
                "Tap \"Summarize this PDF\" below for a structured 6-part executive summary or ask any question.",
            pageCitations = result.pages.take(6).map { page ->
                when (page.source) {
                    ExtractionSource.ML_KIT_OCR -> "Page ${page.pageNumber} (Visual OCR)"
                    ExtractionSource.FUSED_HYBRID_OCR -> "Page ${page.pageNumber} (Hybrid Fused)"
                    else -> "Page ${page.pageNumber}"
                }
            }
        )
        if (messages.isEmpty() || messages.size == 1 && messages.first().id.startsWith("welcome_")) {
            messages = listOf(welcomeMsg)
        }
        isIndexingPdf = false
    }

    val sendQuestion: (String) -> Unit = { rawQuestion ->
        val trimmed = rawQuestion.trim()
        if (trimmed.isNotEmpty() && !isGeneratingReply) {
            focusManager.clearFocus()
            val activeEngine = selectedEngine
            val now = System.currentTimeMillis()
            val userMessage = PdfChatMessage(
                id = "user_$now",
                isUser = true,
                engine = activeEngine,
                text = trimmed
            )
            val streamingId = "stream_$now"
            val initialPendingMessage = PdfChatMessage(
                id = streamingId,
                isUser = false,
                engine = activeEngine,
                text = if (activeEngine == PdfChatEngineOption.LOCAL_GEMMA_E2B) {
                    "Running Local Gemma E2B RAG & Structured Synthesizer over PDF chunks…"
                } else {
                    "Connecting to Gemini 3.8 Flash (Keyless Free Tier) and analyzing PDF chunks…"
                },
                isStreaming = true
            )
            val priorConversationHistory = messages
            messages = messages + userMessage + initialPendingMessage
            questionInput = ""
            isGeneratingReply = true

            coroutineScope.launch {
                val targetIndex = (messages.size).coerceAtLeast(1)
                runCatching { listState.animateScrollToItem(targetIndex) }

                val currentExtraction = extractionResult ?: withContext(Dispatchers.IO) {
                    hybridExtractor.extractForPhoneFile(
                        file = file,
                        config = ExtractionConfig(forceOcrAllPages = forceVisualOcrMode)
                    )
                }.also {
                    extractionResult = it
                    extractedPages = it.pages.map { p -> p.normalizedText }
                }
                val pagesForQuery = currentExtraction.pages.map { it.normalizedText }

                val reply = when (activeEngine) {
                    PdfChatEngineOption.LOCAL_GEMMA_E2B -> {
                        withContext(Dispatchers.Default) {
                            generateLocalGemmaE2bPdfAnswer(
                                file = file,
                                pagesText = pagesForQuery,
                                question = trimmed,
                                extractionResult = currentExtraction,
                                conversationHistory = priorConversationHistory
                            )
                        }
                    }
                    PdfChatEngineOption.GEMINI_SUBSCRIPTION -> {
                        aiRepository.generateGeminiSubscriptionAnswer(
                            file = file,
                            pagesText = pagesForQuery,
                            question = trimmed,
                            onStreamUpdate = { partialText, citations ->
                                val streamingMsg = PdfChatMessage(
                                    id = streamingId,
                                    isUser = false,
                                    engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                                    text = partialText,
                                    pageCitations = citations,
                                    isStreaming = true
                                )
                                val withoutPrevStream = messages.filterNot { it.id == streamingId }
                                messages = withoutPrevStream + streamingMsg
                            }
                        )
                    }
                }
                messages = messages.filterNot { it.id == streamingId } + reply
                isGeneratingReply = false
                val answerItemIndex = (messages.size).coerceAtLeast(1)
                runCatching { listState.animateScrollToItem(answerItemIndex) }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .testTag("fullscreen_pdf_chat_dialog"),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp)
                ) {
                    // Pinned Compact Top Bar ONLY (~52dp) so the rest of the screen scrolls freely
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.PictureAsPdf,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.pdf_chat_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val chunkCount = extractionResult?.chunks?.size ?: 0
                                        Text(
                                            text = "${file.name} • ${extractedPages.size.coerceAtLeast(1)} ${if (extractedPages.size == 1) "page" else "pages"} • $chunkCount chunks",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    TextButton(
                                        onClick = { forceVisualOcrMode = !forceVisualOcrMode },
                                        modifier = Modifier.testTag("pdf_chat_visual_ocr_toggle")
                                    ) {
                                        Text(
                                            text = if (forceVisualOcrMode) "Visual OCR: ON" else "Visual OCR",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (forceVisualOcrMode) {
                                                MaterialTheme.colorScheme.tertiary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                        )
                                    }

                                    TextButton(
                                        onClick = { onOpenPdfReader(file) },
                                        modifier = Modifier.testTag("pdf_chat_open_reader_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PictureAsPdf,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.open_file_action),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    IconButton(
                                        onClick = onDismiss,
                                        modifier = Modifier.testTag("close_pdf_chat_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close PDF Chat"
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }

                    // Unified Full-Screen Vertically Scrollable LazyColumn:
                    // Contains Model Selector + Query Input Box + All Chat Messages so nothing ever overflows or gets stuck at the bottom!
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("pdf_chat_messages_list"),
                        contentPadding = PaddingValues(
                            start = 14.dp,
                            end = 14.dp,
                            top = 12.dp,
                            bottom = 112.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Top Item: Model Selector + Query Input Box + Quick Prompts
                        item(key = "pdf_chat_controls_and_query_box") {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 2.dp,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // 1. Dual Chat Engine Selector
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("pdf_chat_engine_selector"),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "1. SELECT CHAT MODEL",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            PdfEngineOptionCard(
                                                option = PdfChatEngineOption.LOCAL_GEMMA_E2B,
                                                selected = selectedEngine == PdfChatEngineOption.LOCAL_GEMMA_E2B,
                                                onClick = { selectedEngine = PdfChatEngineOption.LOCAL_GEMMA_E2B },
                                                testTag = "pdf_chat_option_local_gemma",
                                                modifier = Modifier.weight(1f)
                                            )

                                            PdfEngineOptionCard(
                                                option = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                                                selected = selectedEngine == PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                                                onClick = { selectedEngine = PdfChatEngineOption.GEMINI_SUBSCRIPTION },
                                                testTag = "pdf_chat_option_gemini_subscription",
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        if (selectedEngine == PdfChatEngineOption.GEMINI_SUBSCRIPTION) {
                                            Surface(
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                                                border = BorderStroke(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .testTag("pdf_chat_firebase_notice")
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.VerifiedUser,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.secondary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.firebase_required_notice),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                                    // 2. Query Input Box + Quick Prompts
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                                text = "2. ENTER YOUR QUESTION ABOUT THIS PDF",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = questionInput,
                                                onValueChange = { questionInput = it },
                                                placeholder = {
                                                    Text(
                                                        text = if (selectedEngine == PdfChatEngineOption.LOCAL_GEMMA_E2B) {
                                                            "Type a question for Local Gemma E2B…"
                                                        } else {
                                                            "Type a question for Gemini 3.8 Flash…"
                                                        },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                },
                                                trailingIcon = {
                                                    if (questionInput.isNotEmpty()) {
                                                        IconButton(
                                                            onClick = { questionInput = "" }
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Clear,
                                                                contentDescription = "Clear question"
                                                            )
                                                        }
                                                    }
                                                },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                                keyboardActions = KeyboardActions(
                                                    onSend = { sendQuestion(questionInput) }
                                                ),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                                    unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                                                ),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .testTag("pdf_chat_question_input")
                                            )

                                            Button(
                                                onClick = { sendQuestion(questionInput) },
                                                enabled = questionInput.isNotBlank() && !isGeneratingReply,
                                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                                modifier = Modifier
                                                    .height(54.dp)
                                                    .testTag("pdf_chat_send_button")
                                            ) {
                                                if (isGeneratingReply) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(18.dp),
                                                        strokeWidth = 2.dp,
                                                        color = MaterialTheme.colorScheme.onPrimary
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                                        contentDescription = "Ask PDF Question",
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
                                        }

                                        // Quick-Question Suggestion Strip
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val quickPrompts = listOf(
                                                "Summarize this PDF",
                                                "Action items & anomalies",
                                                "Key numbers, dates & totals",
                                                "Main findings & values",
                                                "List all pages & sections"
                                            )
                                            quickPrompts.forEachIndexed { idx, prompt ->
                                                AssistChip(
                                                    onClick = { sendQuestion(prompt) },
                                                    label = {
                                                        Text(
                                                            text = prompt,
                                                            style = MaterialTheme.typography.labelSmall
                                                        )
                                                    },
                                                    modifier = Modifier.testTag("pdf_chat_quick_prompt_$idx")
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (isIndexingPdf && messages.isEmpty()) {
                            item(key = "pdf_indexing_indicator") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        text = indexingProgressLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            items(
                                items = messages,
                                key = { it.id }
                            ) { msg ->
                                PdfChatBubble(message = msg)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfEngineOptionCard(
    option: PdfChatEngineOption,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor),
        modifier = modifier
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (option == PdfChatEngineOption.LOCAL_GEMMA_E2B) {
                    Icons.Default.Memory
                } else {
                    Icons.Default.CloudQueue
                },
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = option.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = option.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PdfChatBubble(message: PdfChatMessage) {
    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val bubbleColor = when {
        message.isUser -> MaterialTheme.colorScheme.primary
        message.isError -> MaterialTheme.colorScheme.errorContainer
        message.engine == PdfChatEngineOption.GEMINI_SUBSCRIPTION -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = when {
        message.isUser -> MaterialTheme.colorScheme.onPrimary
        message.isError -> MaterialTheme.colorScheme.onErrorContainer
        message.engine == PdfChatEngineOption.GEMINI_SUBSCRIPTION -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = bubbleColor,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth(if (message.isUser) 0.88f else 1f)
                .widthIn(max = 680.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!message.isUser) {
                        if (message.isStreaming) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = textColor
                            )
                        } else {
                            Icon(
                                imageVector = when {
                                    message.isError -> Icons.Default.ErrorOutline
                                    message.engine == PdfChatEngineOption.LOCAL_GEMMA_E2B -> Icons.Default.Memory
                                    else -> Icons.Default.AutoAwesome
                                },
                                contentDescription = null,
                                tint = textColor.copy(alpha = 0.85f),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = when {
                            message.isUser -> "You"
                            message.isError -> "${message.engine.badgeLabel} • Error"
                            message.isStreaming -> "${message.engine.badgeLabel} • Generating…"
                            else -> message.engine.badgeLabel
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = textColor.copy(alpha = 0.85f)
                    )
                }

                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (message.pageCitations.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sources:",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = textColor.copy(alpha = 0.75f)
                        )
                        message.pageCitations.forEach { pageLabel ->
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = Color.Black.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = pageLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = textColor,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
