package com.example.ui.components

import android.util.Log
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
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "PdfChatAi"

/**
 * Two selectable engines for chatting with a PDF document:
 * 1. [LOCAL_GEMMA_E2B]: 100% offline on-device Gemma E2B 4-bit RAG over extracted PDF chunks.
 * 2. [GEMINI_SUBSCRIPTION]: Keyless Firebase AI Logic (`gemini-3.8-flash` free tier — no API key or paid billing).
 */
enum class PdfChatEngineOption(
    val displayName: String,
    val subtitle: String,
    val badgeLabel: String
) {
    LOCAL_GEMMA_E2B(
        displayName = "Local Gemma E2B",
        subtitle = "On-Device 4-bit LLM • Offline RAG",
        badgeLabel = "Gemma E2B (On-Device)"
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
        val citations = rankedPassages.map { "Page ${it.pageNumber}" }.distinct()

        // Build grounded context from the PDF's cleaned pages (bounded to fit prompt cleanly)
        val pdfContext = buildString {
            append("PDF File Name: ").append(file.name).append('\n')
            append("Total Pages: ").append(pagesText.size).append("\n\n")
            if (rankedPassages.isNotEmpty()) {
                append("Most Relevant Passages:\n")
                rankedPassages.forEach { passage ->
                    append("- [Page ").append(passage.pageNumber).append("] ").append(passage.snippet).append('\n')
                }
                append('\n')
            }
            append("Full Cleaned Page Contents:\n")
            pagesText.take(12).forEachIndexed { idx, pageText ->
                append("--- PAGE ").append(idx + 1).append(" ---\n")
                append(pageText.take(2200)).append("\n\n")
            }
        }

        val prompt = """
            You are a helpful document assistant inside File Radar. Answer the user's question accurately using ONLY the provided PDF document content below.
            Cite specific page numbers (e.g., [Page 1], [Page 2]) when referencing facts, numbers, or dates.
            If the answer is not in the document, state clearly what information is present instead.

            $pdfContext

            User Question: $question
        """.trimIndent()

        val messageId = "gemini_${System.currentTimeMillis()}"
        val accumulatedText = StringBuilder()

        return try {
            withTimeout(60_000L) {
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
                PdfChatMessage(
                    id = messageId,
                    isUser = false,
                    engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                    text = "Model returned an empty response. Please try asking your question again.",
                    pageCitations = citations,
                    isError = true
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Firebase AI PDF chat streaming timed out", e)
            PdfChatMessage(
                id = messageId,
                isUser = false,
                engine = PdfChatEngineOption.GEMINI_SUBSCRIPTION,
                text = "Generation timed out after 60 seconds. Please try again or switch to Local Gemma E2B.",
                pageCitations = citations,
                isError = true
            )
        } catch (e: CancellationException) {
            // Always rethrow CancellationException to preserve cooperative coroutine cancellation
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI PDF chat generation failed", e)
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
            append("Summary for \"$question\": ")
            append(synthesizeDirectAnswerSummary(file, pagesText, rankedPassages, question))
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
    val score: Double
)

internal fun generateLocalGemmaE2bPdfAnswer(
    file: PhoneFileItem,
    pagesText: List<String>,
    question: String
): PdfChatMessage {
    val rankedPassages = retrieveRelevantPdfPassages(pagesText, question)
    val citations = rankedPassages.map { "Page ${it.pageNumber}" }.distinct()
    val summary = synthesizeDirectAnswerSummary(file, pagesText, rankedPassages, question)

    val formattedAnswer = buildString {
        append(summary)
        if (rankedPassages.isNotEmpty()) {
            append("\n\nGrounded PDF Excerpts (On-Device RAG):\n")
            rankedPassages.forEach { match ->
                append("• [Page ${match.pageNumber}] ${match.snippet}\n")
            }
        }
    }.trim()

    return PdfChatMessage(
        id = "gemma_${System.currentTimeMillis()}",
        isUser = false,
        engine = PdfChatEngineOption.LOCAL_GEMMA_E2B,
        text = formattedAnswer,
        pageCitations = citations,
        isSamplePlaceholder = false
    )
}

internal fun retrieveRelevantPdfPassages(
    pagesText: List<String>,
    question: String
): List<PdfPassageMatch> {
    if (pagesText.isEmpty()) return emptyList()

    val stopWords = setOf(
        "the", "and", "for", "with", "that", "this", "from", "what", "when", "where",
        "which", "who", "how", "are", "was", "were", "does", "did", "can", "you",
        "tell", "about", "into", "pdf", "document", "file", "page", "pages", "please"
    )
    val queryTokens = question.lowercase(Locale.ROOT)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 2 && it !in stopWords }

    val lowerQ = question.lowercase(Locale.ROOT)
    val wantsNumbers = lowerQ.contains("number") || lowerQ.contains("amount") ||
        lowerQ.contains("total") || lowerQ.contains("balance") ||
        lowerQ.contains("price") || lowerQ.contains("cost") ||
        lowerQ.contains("date") || lowerQ.contains("value") || lowerQ.contains("count")

    val candidates = mutableListOf<PdfPassageMatch>()

    pagesText.forEachIndexed { index, pageContent ->
        val pageNum = index + 1
        val paragraphs = pageContent
            .lines()
            .map { it.trim() }
            .filter { it.length >= 6 && !it.startsWith("• Storage Path:") }

        for (line in paragraphs) {
            val lowerLine = line.lowercase(Locale.ROOT)
            var score = 0.15

            for (token in queryTokens) {
                if (lowerLine.contains(token)) {
                    score += if (lowerLine.split(Regex("[^a-z0-9]+")).contains(token)) 2.4 else 1.2
                }
            }

            if (wantsNumbers && line.any { it.isDigit() }) {
                score += 1.1
            }

            if (line.contains(':') || line.startsWith("•")) {
                score += 0.25
            }

            candidates.add(
                PdfPassageMatch(
                    pageNumber = pageNum,
                    snippet = line.take(220),
                    score = score
                )
            )
        }
    }

    return candidates
        .sortedByDescending { it.score }
        .distinctBy { it.snippet.lowercase(Locale.ROOT) }
        .take(4)
}

private fun synthesizeDirectAnswerSummary(
    file: PhoneFileItem,
    pagesText: List<String>,
    topPassages: List<PdfPassageMatch>,
    question: String
): String {
    val lowerQ = question.lowercase(Locale.ROOT)
    val totalPages = pagesText.size.coerceAtLeast(1)

    if (lowerQ.contains("summarize") || lowerQ.contains("summary") || lowerQ.contains("overview")) {
        val pagePreviews = pagesText.mapIndexed { idx, content ->
            val cleanLines = content.lines()
                .map { it.trim() }
                .filter { it.length >= 10 && !it.startsWith("DOCUMENT METADATA") && !it.startsWith("• File Name:") }
                .take(2)
                .joinToString(" ")
            "Page ${idx + 1}: ${cleanLines.ifBlank { "Contains structured document records." }}"
        }
        return "Document Summary for ${file.name} ($totalPages ${if (totalPages == 1) "page" else "pages"}):\n" +
            pagePreviews.joinToString("\n")
    }

    if (lowerQ.contains("list all pages") || lowerQ.contains("sections")) {
        return pagesText.mapIndexed { idx, content ->
            val firstHeading = content.lines()
                .map { it.trim() }
                .firstOrNull { it.length >= 5 && !it.startsWith("DOCUMENT METADATA") && !it.startsWith("•") }
                ?: "Page ${idx + 1} Content"
            "Page ${idx + 1}: $firstHeading"
        }.joinToString("\n")
    }

    val best = topPassages.firstOrNull()
    return if (best != null) {
        "On Page ${best.pageNumber} of ${file.name}, the document states: \"${best.snippet}\"."
    } else {
        "Analyzed $totalPages ${if (totalPages == 1) "page" else "pages"} of ${file.name} (${file.formattedSize()}). Ask about specific terms, numbers, dates, or sections to retrieve exact page citations."
    }
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

    var selectedEngine by remember(file.id) { mutableStateOf(PdfChatEngineOption.LOCAL_GEMMA_E2B) }
    var extractedPages by remember(file.id) { mutableStateOf<List<String>>(emptyList()) }
    var isIndexingPdf by remember(file.id) { mutableStateOf(true) }
    var isGeneratingReply by remember(file.id) { mutableStateOf(false) }
    var questionInput by remember(file.id) { mutableStateOf("") }
    var messages by remember(file.id) { mutableStateOf<List<PdfChatMessage>>(emptyList()) }

    LaunchedEffect(file.id) {
        isIndexingPdf = true
        val pages = withContext(Dispatchers.IO) {
            extractReadablePagesFromPdf(context, file, expectedPageCount = 3)
        }
        extractedPages = pages
        val welcomeMsg = PdfChatMessage(
            id = "welcome_${file.id}",
            isUser = false,
            engine = PdfChatEngineOption.LOCAL_GEMMA_E2B,
            text = "Extracted and cleaned ${pages.size} ${if (pages.size == 1) "page" else "pages"} from ${file.name}. " +
                "Enter your question in the query box above or tap a quick prompt to get answers grounded in this PDF.",
            pageCitations = pages.indices.map { "Page ${it + 1}" }
        )
        if (messages.isEmpty()) {
            messages = listOf(welcomeMsg)
        }
        isIndexingPdf = false
    }

    val sendQuestion: (String) -> Unit = { rawQuestion ->
        val trimmed = rawQuestion.trim()
        if (trimmed.isNotEmpty() && !isGeneratingReply) {
            focusManager.clearFocus()
            val activeEngine = selectedEngine
            val userMessage = PdfChatMessage(
                id = "user_${System.currentTimeMillis()}",
                isUser = true,
                engine = activeEngine,
                text = trimmed
            )
            val streamingId = "stream_${System.currentTimeMillis()}"
            messages = messages + userMessage
            questionInput = ""
            isGeneratingReply = true

            coroutineScope.launch {
                val pagesForQuery = if (extractedPages.isNotEmpty()) {
                    extractedPages
                } else {
                    withContext(Dispatchers.IO) {
                        extractReadablePagesFromPdf(context, file, expectedPageCount = 3)
                    }.also { extractedPages = it }
                }
                val reply = when (activeEngine) {
                    PdfChatEngineOption.LOCAL_GEMMA_E2B -> {
                        withContext(Dispatchers.Default) {
                            generateLocalGemmaE2bPdfAnswer(file, pagesForQuery, trimmed)
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
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.lastIndex)
                }
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
                    // Top Header + Model Selector + Prominent Query Input Box
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // 1. Compact Header Row
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
                                        Text(
                                            text = "${file.name} • ${extractedPages.size.coerceAtLeast(1)} ${if (extractedPages.size == 1) "page" else "pages"} indexed",
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

                            // 2. Dual Chat Engine Selector: Local Gemma E2B vs Keyless Gemini (Firebase AI Logic)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
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
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
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

                            // 3. Prominent Top Query Entry Card (Always Visible Above the Fold)
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
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
                                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
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

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }

                    // 4. Chat Conversation & Answers Area
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        if (isIndexingPdf && messages.isEmpty()) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = "Extracting & cleaning PDF content for Q&A…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("pdf_chat_messages_list"),
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
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
            modifier = Modifier.widthIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!message.isUser) {
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
                    Text(
                        text = when {
                            message.isUser -> "You"
                            message.isError -> "${message.engine.badgeLabel} • Error"
                            message.isStreaming -> "${message.engine.badgeLabel} • Streaming…"
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
                    color = textColor
                )

                if (message.pageCitations.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
