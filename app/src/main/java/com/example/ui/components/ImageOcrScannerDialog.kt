package com.example.ui.components

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.model.PhoneFileItem
import com.example.pdfextractor.HybridPdfExtractor
import com.example.pdfextractor.TextNormalizer
import com.example.ui.theme.PosAdjectiveEmerald
import com.example.ui.theme.PosAdjectiveEmeraldBadgeText
import com.example.ui.theme.PosAdjectiveEmeraldHighlight
import com.example.ui.theme.PosAdverbViolet
import com.example.ui.theme.PosAdverbVioletBadgeText
import com.example.ui.theme.PosAdverbVioletHighlight
import com.example.ui.theme.PosNounYellow
import com.example.ui.theme.PosNounYellowBadgeText
import com.example.ui.theme.PosNounYellowHighlight
import com.example.ui.theme.PosPronounRose
import com.example.ui.theme.PosPronounRoseBadgeText
import com.example.ui.theme.PosPronounRoseHighlight
import com.example.ui.theme.PosVerbCyan
import com.example.ui.theme.PosVerbCyanBadgeText
import com.example.ui.theme.PosVerbCyanHighlight
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class ImageOcrResult(
    val sourceLabel: String,
    val extractedText: String,
    val blockCount: Int,
    val lineCount: Int,
    val wordCount: Int,
    val charCount: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val highContrastApplied: Boolean
)

enum class MajorPosCategory {
    NOUN,
    VERB,
    ADJECTIVE,
    ADVERB,
    PRONOUN,
    OTHER
}

data class PosWordSpan(
    val word: String,
    val startIndex: Int,
    val endIndex: Int,
    val category: MajorPosCategory
)

data class PosTagBreakdown(
    val totalWords: Int,
    val nounCount: Int,
    val verbCount: Int,
    val adjectiveCount: Int,
    val adverbCount: Int,
    val pronounCount: Int,
    val nounExamples: List<String> = emptyList(),
    val verbExamples: List<String> = emptyList(),
    val adjectiveExamples: List<String> = emptyList(),
    val adverbExamples: List<String> = emptyList(),
    val pronounExamples: List<String> = emptyList(),
    val wordSpans: List<PosWordSpan> = emptyList(),
    val rAdjPron: Double = 0.0,
    val rAdvAdj: Double = 0.0,
    val logitZ: Double = 1.7501,
    val probabilityOfFiction: Double = 1.0 / (1.0 + exp(-1.7501)),
    val isFictionPredicted: Boolean = true,
    val predictedGenreLabel: String = "Fiction"
)

/**
 * Context-aware on-device English Part-of-Speech (POS) analyzer that classifies extracted words
 * into major grammatical categories (Nouns, Verbs, Adjectives, Adverbs, and Pronouns) using
 * closed-class lexicons, morphological suffix rules, and local neighbor context (preceding
 * determiners, modals, auxiliaries, and prepositions).
 */
internal object OnDevicePosTagger {

    private val pronounsSet = setOf(
        "i", "me", "my", "mine", "myself",
        "you", "your", "yours", "yourself", "yourselves",
        "he", "him", "his", "himself",
        "she", "her", "hers", "herself",
        "it", "its", "itself",
        "we", "us", "our", "ours", "ourselves",
        "they", "them", "their", "theirs", "themselves",
        "this", "that", "these", "those",
        "who", "whom", "whose", "which", "what", "whoever", "whatever",
        "anyone", "anybody", "anything", "someone", "somebody", "something",
        "everyone", "everybody", "everything", "noone", "nobody", "nothing",
        "each", "either", "neither", "both", "several", "many", "few",
        "all", "some", "any", "none", "one", "ones", "other", "others", "another"
    )

    private val articlesAndDeterminers = setOf(
        "the", "a", "an", "every", "no"
    )

    private val prepositionsAndConjunctions = setOf(
        "in", "on", "at", "by", "for", "with", "about", "against", "between", "into",
        "through", "during", "before", "after", "above", "below", "to", "from", "up",
        "down", "of", "off", "over", "under", "via", "per", "within", "without",
        "and", "but", "or", "nor", "so", "yet", "because", "although", "unless",
        "while", "if", "than", "whether", "as", "until", "since"
    )

    private val modalsAndAuxiliaries = setOf(
        "is", "am", "are", "was", "were", "be", "been", "being",
        "have", "has", "had", "having",
        "do", "does", "did", "doing", "done",
        "will", "would", "shall", "should", "can", "could", "may", "might", "must"
    )

    private val commonVerbs = modalsAndAuxiliaries + setOf(
        "go", "goes", "went", "gone", "going",
        "make", "makes", "made", "making",
        "take", "takes", "took", "taken", "taking",
        "get", "gets", "got", "gotten", "getting",
        "give", "gives", "gave", "given", "giving",
        "find", "finds", "found", "finding",
        "know", "knows", "knew", "known", "knowing",
        "think", "thinks", "thought", "thinking",
        "see", "sees", "saw", "seen", "seeing",
        "come", "comes", "came", "coming",
        "want", "wants", "wanted",
        "use", "uses", "used", "using",
        "work", "works", "worked", "working",
        "call", "calls", "called", "calling",
        "try", "tries", "tried", "trying",
        "ask", "asks", "asked", "asking",
        "need", "needs", "needed",
        "feel", "feels", "felt",
        "become", "becomes", "became",
        "leave", "leaves", "left", "leaving",
        "put", "puts", "putting",
        "mean", "means", "meant",
        "keep", "keeps", "kept", "keeping",
        "let", "lets", "letting",
        "begin", "begins", "began", "begun",
        "seem", "seems", "seemed",
        "help", "helps", "helped",
        "talk", "talks", "talked",
        "turn", "turns", "turned",
        "start", "starts", "started",
        "show", "shows", "showed", "shown",
        "hear", "hears", "heard",
        "play", "plays", "played",
        "run", "runs", "ran", "running",
        "move", "moves", "moved",
        "live", "lives", "lived",
        "believe", "believes", "believed",
        "hold", "holds", "held",
        "bring", "brings", "brought",
        "happen", "happens", "happened",
        "write", "writes", "wrote", "written", "writing",
        "provide", "provides", "provided",
        "sit", "sits", "sat",
        "stand", "stands", "stood",
        "lose", "loses", "lost",
        "pay", "pays", "paid",
        "meet", "meets", "met",
        "include", "includes", "included", "including",
        "continue", "continues", "continued",
        "set", "sets", "setting",
        "learn", "learns", "learned",
        "change", "changes", "changed",
        "lead", "leads", "led",
        "understand", "understands", "understood",
        "watch", "watches", "watched",
        "follow", "follows", "followed",
        "stop", "stops", "stopped",
        "create", "creates", "created",
        "speak", "speaks", "spoke", "spoken",
        "read", "reads", "reading",
        "allow", "allows", "allowed",
        "add", "adds", "added",
        "spend", "spends", "spent",
        "grow", "grows", "grew", "grown",
        "open", "opens", "opened",
        "walk", "walks", "walked",
        "win", "wins", "won",
        "offer", "offers", "offered",
        "remember", "remembers", "remembered",
        "consider", "considers", "considered",
        "appear", "appears", "appeared",
        "buy", "buys", "bought",
        "wait", "waits", "waited",
        "serve", "serves", "served",
        "send", "sends", "sent",
        "expect", "expects", "expected",
        "build", "builds", "built",
        "stay", "stays", "stayed",
        "fall", "falls", "fell", "fallen",
        "cut", "cuts",
        "reach", "reaches", "reached",
        "remain", "remains", "remained",
        "suggest", "suggests", "suggested",
        "raise", "raises", "raised",
        "pass", "passes", "passed",
        "sell", "sells", "sold",
        "require", "requires", "required",
        "decide", "decides", "decided",
        "return", "returns", "returned",
        "explain", "explains", "explained",
        "develop", "develops", "developed",
        "carry", "carries", "carried",
        "break", "breaks", "broke", "broken",
        "receive", "receives", "received",
        "agree", "agrees", "agreed",
        "support", "supports", "supported",
        "produce", "produces", "produced",
        "eat", "eats", "ate", "eaten",
        "cover", "covers", "covered",
        "catch", "catches", "caught",
        "draw", "draws", "drew", "drawn",
        "choose", "chooses", "chose", "chosen",
        "extract", "extracts", "extracted", "extracting",
        "verify", "verifies", "verified",
        "issue", "issues", "issued",
        "measure", "measures", "measured",
        "record", "records", "recorded",
        "confirm", "confirms", "confirmed", "confirming",
        "recommend", "recommends", "recommended",
        "identify", "identifies", "identified",
        "scan", "scans", "scanned", "scanning",
        "upload", "uploads", "uploaded", "uploading",
        "click", "clicks", "clicked",
        "contain", "contains", "contained",
        "describe", "describes", "described",
        "present", "presents", "presented",
        "apply", "applies", "applied"
    )

    private val commonAdverbs = setOf(
        "not", "very", "also", "often", "however", "too", "usually", "really",
        "never", "always", "sometimes", "together", "simply", "generally",
        "instead", "actually", "again", "rather", "almost", "especially", "ever",
        "quickly", "probably", "already", "directly", "therefore", "else", "thus",
        "easily", "eventually", "exactly", "certainly", "normally", "currently",
        "extremely", "finally", "constantly", "properly", "soon", "specifically",
        "ahead", "highly", "immediately", "relatively", "slowly", "fairly",
        "primarily", "completely", "ultimately", "widely", "recently", "seriously",
        "frequently", "fully", "mostly", "naturally", "nearly", "occasionally",
        "carefully", "clearly", "effectively", "here", "there", "now", "then",
        "today", "tomorrow", "yesterday", "well", "quite", "still", "just",
        "only", "even", "back", "away", "far", "twice", "once", "abroad", "anywhere",
        "everywhere", "nowhere", "somewhere", "upstairs", "downstairs", "indoors",
        "outdoors", "forever", "seldom", "rarely", "hardly", "scarcely", "barely",
        "indeed", "furthermore", "moreover", "nevertheless", "nonetheless", "otherwise"
    )

    private val nonAdverbLyWords = setOf(
        "family", "friendly", "lovely", "daily", "early", "holy", "ugly", "silly",
        "lonely", "elderly", "costly", "timely", "orderly", "lively", "likely",
        "unlikely", "supply", "reply", "apply", "july", "italy", "belly", "ally",
        "bully", "jelly", "lily", "rally", "tally", "valley", "fly", "rely"
    )

    private val commonAdjectives = setOf(
        "good", "better", "best", "bad", "worse", "worst",
        "new", "first", "last", "long", "great", "little", "own", "old", "right",
        "big", "high", "different", "small", "large", "next", "early", "young",
        "important", "public", "same", "able", "normal", "clinical", "diagnostic",
        "annual", "metabolic", "acute", "total", "valid", "digital", "local",
        "full", "empty", "clean", "readable", "medical", "financial", "monthly",
        "weekly", "daily", "available", "free", "real", "major", "minor", "single",
        "multiple", "main", "key", "final", "initial", "current", "previous",
        "recent", "modern", "native", "safe", "clear", "bright", "dark", "light",
        "heavy", "fast", "slow", "warm", "cold", "hot", "cool", "fresh", "dry",
        "wet", "hard", "soft", "strong", "weak", "deep", "wide", "narrow", "thick",
        "thin", "short", "tall", "low", "rich", "poor", "true", "false", "closed",
        "ready", "simple", "complex", "special", "general", "specific", "certain",
        "sure", "possible", "impossible", "likely", "unlikely", "necessary", "useful",
        "helpful", "effective", "efficient", "accurate", "exact", "complete", "entire",
        "whole", "various", "similar", "popular", "common", "rare", "unique",
        "private", "personal", "social", "political", "economic", "legal", "physical",
        "mental", "natural", "human", "global", "national", "international", "central",
        "primary", "secondary", "active", "passive", "positive", "negative", "direct",
        "indirect", "automatic", "manual", "virtual", "internal", "external", "standard",
        "custom", "balanced", "routine", "fasting", "glycemic", "hematological", "lipid"
    )

    private val commonNouns = setOf(
        "invoice", "number", "date", "monitor", "array", "amount", "warranty", "months",
        "purchase", "code", "status", "receipt", "note", "hardware", "cloud", "summary",
        "report", "patient", "evaluation", "hemoglobin", "concentration", "reference",
        "interval", "range", "cholesterol", "abnormalities", "panel", "recommendations",
        "glucose", "plasma", "control", "vitamin", "testing", "nutrition", "text",
        "image", "photo", "picture", "file", "page", "pages", "document", "table",
        "section", "line", "lines", "word", "words", "time", "year", "years", "day",
        "days", "week", "weeks", "system", "data", "information", "result", "results",
        "value", "values", "level", "levels", "rate", "count", "balance", "payment",
        "account", "bank", "hospital", "center", "doctor", "Physician", "lab"
    )

    private data class IndexedToken(
        val rawWord: String,
        val startIndex: Int,
        val endIndex: Int
    )

    private fun isWordChar(ch: Char): Boolean {
        return ch.isLetterOrDigit() || ch == '\'' || ch == '-'
    }

    fun barColorFor(category: MajorPosCategory): Color = when (category) {
        MajorPosCategory.NOUN -> PosNounYellow
        MajorPosCategory.VERB -> PosVerbCyan
        MajorPosCategory.ADJECTIVE -> PosAdjectiveEmerald
        MajorPosCategory.ADVERB -> PosAdverbViolet
        MajorPosCategory.PRONOUN -> PosPronounRose
        MajorPosCategory.OTHER -> Color.Transparent
    }

    fun highlightColorsFor(category: MajorPosCategory): Pair<Color, Color> = when (category) {
        MajorPosCategory.NOUN -> PosNounYellowHighlight to PosNounYellowBadgeText
        MajorPosCategory.VERB -> PosVerbCyanHighlight to PosVerbCyanBadgeText
        MajorPosCategory.ADJECTIVE -> PosAdjectiveEmeraldHighlight to PosAdjectiveEmeraldBadgeText
        MajorPosCategory.ADVERB -> PosAdverbVioletHighlight to PosAdverbVioletBadgeText
        MajorPosCategory.PRONOUN -> PosPronounRoseHighlight to PosPronounRoseBadgeText
        MajorPosCategory.OTHER -> Color.Transparent to Color.Unspecified
    }

    fun buildPosHighlightedAnnotatedString(
        text: String,
        wordSpans: List<PosWordSpan>,
        activeCategoryFilter: MajorPosCategory? = null
    ): AnnotatedString = buildAnnotatedString {
        append(text)
        for (span in wordSpans) {
            if (span.category == MajorPosCategory.OTHER) continue
            if (activeCategoryFilter != null && span.category != activeCategoryFilter) continue
            val safeStart = span.startIndex.coerceIn(0, text.length)
            val safeEnd = span.endIndex.coerceIn(safeStart, text.length)
            if (safeStart < safeEnd) {
                val (bgColor, fgColor) = highlightColorsFor(span.category)
                addStyle(
                    style = SpanStyle(
                        background = bgColor,
                        color = fgColor,
                        fontWeight = FontWeight.SemiBold
                    ),
                    start = safeStart,
                    end = safeEnd
                )
            }
        }
    }

    fun analyzeText(text: String): PosTagBreakdown {
        if (text.isBlank()) {
            return PosTagBreakdown(0, 0, 0, 0, 0, 0)
        }

        // Extract word tokens along with their exact character offsets in `text`
        val indexedTokens = mutableListOf<IndexedToken>()
        for (match in Regex("\\S+").findAll(text)) {
            var start = match.range.first
            var end = match.range.last + 1
            while (start < end && !isWordChar(text[start])) {
                start++
            }
            while (end > start && !isWordChar(text[end - 1])) {
                end--
            }
            if (start < end) {
                indexedTokens.add(
                    IndexedToken(
                        rawWord = text.substring(start, end),
                        startIndex = start,
                        endIndex = end
                    )
                )
            }
        }

        val totalWords = indexedTokens.size
        var nouns = 0
        var verbs = 0
        var adjectives = 0
        var adverbs = 0
        var pronouns = 0

        val nounExamples = mutableListOf<String>()
        val verbExamples = mutableListOf<String>()
        val adjectiveExamples = mutableListOf<String>()
        val adverbExamples = mutableListOf<String>()
        val pronounExamples = mutableListOf<String>()
        val wordSpans = ArrayList<PosWordSpan>(totalWords)

        val loweredTokens = indexedTokens.map { it.rawWord.lowercase(Locale.ROOT) }

        for (i in indexedTokens.indices) {
            val token = indexedTokens[i]
            val rawWord = token.rawWord
            val lower = loweredTokens[i]
            val prevLower = if (i > 0) loweredTokens[i - 1] else ""
            val nextLower = if (i + 1 < loweredTokens.size) loweredTokens[i + 1] else ""

            val category = classifyToken(
                rawWord = rawWord,
                lower = lower,
                prevLower = prevLower,
                nextLower = nextLower
            )

            wordSpans.add(
                PosWordSpan(
                    word = rawWord,
                    startIndex = token.startIndex,
                    endIndex = token.endIndex,
                    category = category
                )
            )

            when (category) {
                MajorPosCategory.NOUN -> {
                    nouns++
                    addExample(nounExamples, rawWord)
                }
                MajorPosCategory.VERB -> {
                    verbs++
                    addExample(verbExamples, rawWord)
                }
                MajorPosCategory.ADJECTIVE -> {
                    adjectives++
                    addExample(adjectiveExamples, rawWord)
                }
                MajorPosCategory.ADVERB -> {
                    adverbs++
                    addExample(adverbExamples, rawWord)
                }
                MajorPosCategory.PRONOUN -> {
                    pronouns++
                    addExample(pronounExamples, rawWord)
                }
                MajorPosCategory.OTHER -> Unit
            }
        }

        val rAdjPron = if (pronouns > 0) {
            adjectives.toDouble() / pronouns.toDouble()
        } else if (adjectives > 0) {
            adjectives.toDouble()
        } else {
            0.0
        }

        val rAdvAdj = if (adjectives > 0) {
            adverbs.toDouble() / adjectives.toDouble()
        } else if (adverbs > 0) {
            adverbs.toDouble()
        } else {
            0.0
        }

        val (logitZ, probabilityOfFiction) = computeFictionLogitAndProbability(
            rAdjPron = rAdjPron,
            rAdvAdj = rAdvAdj
        )
        val isFiction = probabilityOfFiction >= 0.5

        return PosTagBreakdown(
            totalWords = totalWords,
            nounCount = nouns,
            verbCount = verbs,
            adjectiveCount = adjectives,
            adverbCount = adverbs,
            pronounCount = pronouns,
            nounExamples = nounExamples,
            verbExamples = verbExamples,
            adjectiveExamples = adjectiveExamples,
            adverbExamples = adverbExamples,
            pronounExamples = pronounExamples,
            wordSpans = wordSpans,
            rAdjPron = rAdjPron,
            rAdvAdj = rAdvAdj,
            logitZ = logitZ,
            probabilityOfFiction = probabilityOfFiction,
            isFictionPredicted = isFiction,
            predictedGenreLabel = if (isFiction) "Fiction" else "Non-Fiction"
        )
    }

    /**
     * Computes the logistic regression logit `z` and `Probability of Fiction`:
     *   z = 1.7501 + (-4.3818 * RADJPRON) + (2.0998 * RADVADJ)
     *   Probability of Fiction = 1 / (1 + e^(-z))
     */
    fun computeFictionLogitAndProbability(
        rAdjPron: Double,
        rAdvAdj: Double
    ): Pair<Double, Double> {
        val z = 1.7501 + (-4.3818 * rAdjPron) + (2.0998 * rAdvAdj)
        val probabilityOfFiction = 1.0 / (1.0 + exp(-z))
        return z to probabilityOfFiction
    }

    private fun addExample(list: MutableList<String>, word: String) {
        if (list.size < 4 && list.none { it.equals(word, ignoreCase = true) }) {
            list.add(word)
        }
    }

    private fun classifyToken(
        rawWord: String,
        lower: String,
        prevLower: String,
        nextLower: String
    ): MajorPosCategory {
        // Pure numbers, currency codes, or single symbols are not one of the 5 lexical POS tags
        val hasLetter = lower.any { it.isLetter() }
        if (!hasLetter) return MajorPosCategory.OTHER
        val digitCount = lower.count { it.isDigit() }
        if (digitCount > 0 && digitCount.toFloat() / lower.length.toFloat() >= 0.4f) {
            return MajorPosCategory.OTHER
        }

        // 1. Articles, prepositions, and conjunctions -> OTHER
        if (lower in articlesAndDeterminers || lower in prepositionsAndConjunctions) {
            return MajorPosCategory.OTHER
        }

        // 2. Pronouns (Personal, Possessive, Reflexive, Demonstrative, Interrogative, Indefinite)
        if (lower in pronounsSet) {
            return MajorPosCategory.PRONOUN
        }

        // 3. Adverbs (Lexicon + morphological "-ly" rule)
        if (lower in commonAdverbs || lower.endsWith("n't")) {
            return MajorPosCategory.ADVERB
        }
        if (lower.length >= 5 && lower.endsWith("ly") && lower !in nonAdverbLyWords) {
            return MajorPosCategory.ADVERB
        }

        // 4. Contextual & Lexical Disambiguation for words that can be Noun or Verb (e.g. "issue", "record", "report", "purchase")
        val isAfterDeterminerOrPrep = prevLower in articlesAndDeterminers ||
            prevLower in setOf("of", "in", "on", "at", "from", "with", "by", "for", "my", "your", "his", "her", "our", "their", "this", "that", "these", "those")
        val isAfterModalOrInfinitive = prevLower in modalsAndAuxiliaries || prevLower == "to" ||
            prevLower in setOf("i", "you", "we", "they", "he", "she")

        if (lower in commonNouns && isAfterDeterminerOrPrep && !lower.endsWith("ed") && !lower.endsWith("ing")) {
            return MajorPosCategory.NOUN
        }

        // 5. Verbs (Modals, Auxiliaries, Common Verbs, Infinitive/Modal context, and Verb Suffixes)
        if (lower in modalsAndAuxiliaries) {
            return MajorPosCategory.VERB
        }
        if (lower in commonVerbs) {
            // If immediately preceded by "the"/"a"/"an" and followed by a preposition ("the report of"), treat as noun
            if (prevLower in articlesAndDeterminers && lower in commonNouns) {
                return MajorPosCategory.NOUN
            }
            return MajorPosCategory.VERB
        }
        if (isAfterModalOrInfinitive && lower !in commonAdjectives && lower !in commonAdverbs) {
            return MajorPosCategory.VERB
        }
        if (lower.length >= 5 && (
                lower.endsWith("ed") ||
                    lower.endsWith("ing") ||
                    lower.endsWith("ize") ||
                    lower.endsWith("ise") ||
                    lower.endsWith("fy") ||
                    lower.endsWith("ate")
                ) && !isAfterDeterminerOrPrep
        ) {
            return MajorPosCategory.VERB
        }

        // 6. Adjectives (Lexicon + Morphological Adjective Suffixes + Pre-Noun Determiner Context)
        if (lower in commonAdjectives) {
            return MajorPosCategory.ADJECTIVE
        }
        if (lower.length >= 5 && (
                lower.endsWith("able") ||
                    lower.endsWith("ible") ||
                    lower.endsWith("ful") ||
                    lower.endsWith("less") ||
                    lower.endsWith("ous") ||
                    lower.endsWith("ive") ||
                    lower.endsWith("ic") ||
                    lower.endsWith("ish") ||
                    lower.endsWith("al")
                ) && lower !in commonNouns
        ) {
            return MajorPosCategory.ADJECTIVE
        }
        if (prevLower in articlesAndDeterminers && nextLower.isNotEmpty() &&
            nextLower !in prepositionsAndConjunctions && nextLower !in modalsAndAuxiliaries &&
            (lower.endsWith("ed") || lower.endsWith("ing") || lower.endsWith("y"))
        ) {
            return MajorPosCategory.ADJECTIVE
        }

        // 7. Nouns (Common nouns, proper nouns, morphological noun suffixes, and open-class substantive content words)
        if (lower.length >= 2) {
            return MajorPosCategory.NOUN
        }

        return MajorPosCategory.OTHER
    }
}

/**
 * On-device Image OCR Extractor powered by Google ML Kit Text Recognition with spatial
 * bounding-box row & table reconstruction and optional high-contrast pre-filtering.
 */
object ImageOcrExtractor {

    suspend fun extractTextFromUri(
        context: Context,
        uri: Uri,
        sourceLabel: String,
        enhanceContrast: Boolean = false
    ): Pair<Bitmap?, ImageOcrResult> = withContext(Dispatchers.IO) {
        val decoded = decodeSampledBitmapFromUri(context, uri, maxDimensionPx = 2200)
            ?: return@withContext null to ImageOcrResult(
                sourceLabel = sourceLabel,
                extractedText = "",
                blockCount = 0,
                lineCount = 0,
                wordCount = 0,
                charCount = 0,
                imageWidth = 0,
                imageHeight = 0,
                highContrastApplied = enhanceContrast
            )

        val result = extractTextFromBitmap(
            context = context,
            sourceBitmap = decoded,
            sourceLabel = sourceLabel,
            enhanceContrast = enhanceContrast
        )
        decoded to result
    }

    suspend fun extractTextFromBitmap(
        context: Context,
        sourceBitmap: Bitmap,
        sourceLabel: String,
        enhanceContrast: Boolean = false
    ): ImageOcrResult = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val hybridHelper = HybridPdfExtractor(context.applicationContext)
        var contrastBitmap: Bitmap? = null

        try {
            val bitmapForOcr = if (enhanceContrast) {
                val copy = Bitmap.createBitmap(
                    sourceBitmap.width,
                    sourceBitmap.height,
                    Bitmap.Config.ARGB_8888
                )
                contrastBitmap = copy
                val canvas = Canvas(copy)
                canvas.drawColor(AndroidColor.WHITE)
                val contrast = 1.28f
                val translate = -22f
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
                canvas.drawBitmap(sourceBitmap, 0f, 0f, paint)
                copy
            } else {
                sourceBitmap
            }

            val inputImage = InputImage.fromBitmap(bitmapForOcr, 0)
            val visionText = recognizer.process(inputImage).await()

            val spatiallyOrdered = hybridHelper.reconstructSpatialTableAndLines(visionText)
            val normalized = TextNormalizer.normalize(spatiallyOrdered).ifBlank {
                spatiallyOrdered.trim()
            }

            val blockCount = visionText.textBlocks.size
            val lineCount = visionText.textBlocks.sumOf { it.lines.size }
            val words = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val chars = normalized.count { !it.isWhitespace() }

            ImageOcrResult(
                sourceLabel = sourceLabel,
                extractedText = normalized,
                blockCount = blockCount,
                lineCount = lineCount,
                wordCount = words.size,
                charCount = chars,
                imageWidth = sourceBitmap.width,
                imageHeight = sourceBitmap.height,
                highContrastApplied = enhanceContrast
            )
        } catch (e: Throwable) {
            ImageOcrResult(
                sourceLabel = sourceLabel,
                extractedText = "",
                blockCount = 0,
                lineCount = 0,
                wordCount = 0,
                charCount = 0,
                imageWidth = sourceBitmap.width,
                imageHeight = sourceBitmap.height,
                highContrastApplied = enhanceContrast
            )
        } finally {
            runCatching { hybridHelper.close() }
            runCatching { recognizer.close() }
            contrastBitmap?.let {
                if (!it.isRecycled && it !== sourceBitmap) {
                    it.recycle()
                }
            }
        }
    }

    private fun decodeSampledBitmapFromUri(
        context: Context,
        uri: Uri,
        maxDimensionPx: Int
    ): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val width = boundsOptions.outWidth
            val height = boundsOptions.outHeight
            if (width <= 0 || height <= 0) return null

            var inSampleSize = 1
            val largestSide = max(width, height)
            while (largestSide / inSampleSize > maxDimensionPx) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Generates a crisp, realistic photographed document / receipt bitmap with printed text
     * so users on emulators without a camera or gallery images can test live ML Kit OCR immediately.
     */
    fun createSampleTextDocumentBitmap(): Bitmap {
        val width = 1080
        val height = 760
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(AndroidColor.rgb(248, 250, 252))

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(203, 213, 225)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRoundRect(RectF(24f, 24f, width - 24f, height - 24f), 20f, 20f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(15, 23, 42)
            textSize = 40f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(3, 105, 161)
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(30, 41, 59)
            textSize = 29f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }

        canvas.drawText("FILE RADAR - SNAPSHOT RECEIPT & NOTE", 64f, 96f, titlePaint)
        canvas.drawText("1. HARDWARE & CLOUD INVOICE SUMMARY", 64f, 162f, sectionPaint)
        canvas.drawText("Invoice Number: INV-2026-8841", 64f, 220f, bodyPaint)
        canvas.drawText("Date Issued: 2026-11-20", 64f, 272f, bodyPaint)
        canvas.drawText("4K UltraSharp Monitor   $649.00", 64f, 332f, bodyPaint)
        canvas.drawText("NVMe 2TB SSD Array      $189.50", 64f, 384f, bodyPaint)
        canvas.drawText("Total Amount Paid:      $838.50", 64f, 444f, bodyPaint)

        canvas.drawText("2. ACTION NOTES", 64f, 524f, sectionPaint)
        canvas.drawText("Warranty valid for 36 months from purchase.", 64f, 580f, bodyPaint)
        canvas.drawText("Serial Code: FR-9920-X7B", 64f, 632f, bodyPaint)
        canvas.drawText("Status: Verified via On-Device ML Kit OCR.", 64f, 684f, bodyPaint)

        return bmp
    }
}

@Composable
fun FullScreenImageOcrScannerDialog(
    initialFile: PhoneFileItem? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val copiedToastText = stringResource(R.string.ocr_extracted_text_copied)

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeUri by remember { mutableStateOf<Uri?>(null) }
    var activeSourceLabel by remember { mutableStateOf(initialFile?.name ?: "No image selected yet") }
    var ocrResult by remember { mutableStateOf<ImageOcrResult?>(null) }
    var editableExtractedText by remember { mutableStateOf("") }
    var isProcessingOcr by remember { mutableStateOf(false) }
    var highContrastEnabled by remember { mutableStateOf(false) }
    var pendingCameraCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val runOcrOnUri: (Uri, String, Boolean) -> Unit = { uri, label, useContrast ->
        coroutineScope.launch {
            isProcessingOcr = true
            activeUri = uri
            activeSourceLabel = label
            val (bmp, result) = ImageOcrExtractor.extractTextFromUri(
                context = context,
                uri = uri,
                sourceLabel = label,
                enhanceContrast = useContrast
            )
            if (bmp != null) {
                previewBitmap = bmp
            }
            ocrResult = result
            editableExtractedText = result.extractedText
            isProcessingOcr = false
        }
    }

    val runOcrOnBitmap: (Bitmap, String, Boolean) -> Unit = { bmp, label, useContrast ->
        coroutineScope.launch {
            isProcessingOcr = true
            activeUri = null
            previewBitmap = bmp
            activeSourceLabel = label
            val result = ImageOcrExtractor.extractTextFromBitmap(
                context = context,
                sourceBitmap = bmp,
                sourceLabel = label,
                enhanceContrast = useContrast
            )
            ocrResult = result
            editableExtractedText = result.extractedText
            isProcessingOcr = false
        }
    }

    // If opened from an existing indexed Image file in File Radar, auto-run OCR on it
    LaunchedEffect(initialFile?.id) {
        if (initialFile != null) {
            isProcessingOcr = true
            activeSourceLabel = initialFile.name
            val diskFile = File(initialFile.fullPath)
            if (diskFile.exists() && diskFile.canRead() && diskFile.length() > 128L) {
                val uri = Uri.fromFile(diskFile)
                activeUri = uri
                val (bmp, result) = ImageOcrExtractor.extractTextFromUri(
                    context = context,
                    uri = uri,
                    sourceLabel = initialFile.name,
                    enhanceContrast = highContrastEnabled
                )
                previewBitmap = bmp
                ocrResult = result
                editableExtractedText = result.extractedText
            } else if (!initialFile.contentUri.isNullOrBlank()) {
                val uri = Uri.parse(initialFile.contentUri)
                activeUri = uri
                val (bmp, result) = ImageOcrExtractor.extractTextFromUri(
                    context = context,
                    uri = uri,
                    sourceLabel = initialFile.name,
                    enhanceContrast = highContrastEnabled
                )
                previewBitmap = bmp
                ocrResult = result
                editableExtractedText = result.extractedText
            } else {
                val sampleBmp = ImageOcrExtractor.createSampleTextDocumentBitmap()
                previewBitmap = sampleBmp
                val result = ImageOcrExtractor.extractTextFromBitmap(
                    context = context,
                    sourceBitmap = sampleBmp,
                    sourceLabel = initialFile.name,
                    enhanceContrast = highContrastEnabled
                )
                ocrResult = result
                editableExtractedText = result.extractedText
            }
            isProcessingOcr = false
        }
    }

    // 1. Zero-Permission Android Photo Picker for "Upload Image" (Google Play policy compliant)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { selectedUri: Uri? ->
        if (selectedUri != null) {
            runOcrOnUri(selectedUri, "Uploaded Image", highContrastEnabled)
        }
    }

    // 2. Camera Thumbnail fallback launcher in case full-file camera URI fails on an emulator
    val takePicturePreviewLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { capturedBitmap: Bitmap? ->
        if (capturedBitmap != null) {
            runOcrOnBitmap(capturedBitmap, "Camera Snapshot", highContrastEnabled)
        }
    }

    // 3. Full-resolution Camera Capture launcher via FileProvider
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        val capturedUri = pendingCameraCaptureUri
        if (success && capturedUri != null) {
            runOcrOnUri(capturedUri, "Camera Photo", highContrastEnabled)
        }
    }

    val launchCameraCapture: () -> Unit = {
        try {
            val capturesDir = File(context.cacheDir, "ocr_captures").apply { mkdirs() }
            val photoFile = File(capturesDir, "ocr_capture_${System.currentTimeMillis()}.jpg")
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            pendingCameraCaptureUri = contentUri
            takePictureLauncher.launch(contentUri)
        } catch (_: Throwable) {
            try {
                takePicturePreviewLauncher.launch(null)
            } catch (e: Throwable) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Camera unavailable on this device. Tap 'Upload Image' or 'Sample Note'.")
                }
            }
        }
    }

    // 4. Runtime CAMERA permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            launchCameraCapture()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Camera permission is required to take a picture. You can still upload any image using 'Upload Image'.")
            }
        }
    }

    val onTakePictureClicked: () -> Unit = {
        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            launchCameraCapture()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val onUploadImageClicked: () -> Unit = {
        photoPickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    val copyExtractedText: () -> Unit = {
        if (editableExtractedText.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("OCR Extracted Text", editableExtractedText))
            coroutineScope.launch {
                snackbarHostState.showSnackbar(copiedToastText)
            }
        }
    }

    val shareExtractedText: () -> Unit = {
        if (editableExtractedText.isNotBlank()) {
            try {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Extracted Text (${activeSourceLabel})")
                    putExtra(Intent.EXTRA_TEXT, editableExtractedText)
                }
                val chooser = Intent.createChooser(shareIntent, "Share Extracted Text").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (_: Throwable) {
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
                .testTag("image_ocr_scanner_dialog"),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
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
                    // Clean Top App Bar
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
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
                                                imageVector = Icons.Default.DocumentScanner,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    Text(
                                        text = stringResource(R.string.image_ocr_scanner_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.testTag("close_image_ocr_scanner_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close Image OCR Scanner"
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                    }

                    var selectedPosFilter by remember {
                        mutableStateOf<MajorPosCategory?>(null)
                    }
                    var showExpandedImagePreview by remember {
                        mutableStateOf(false)
                    }
                    val posBreakdown = remember(editableExtractedText) {
                        OnDevicePosTagger.analyzeText(editableExtractedText)
                    }

                    // Vertically Scrollable Body with Clean Top-Level Cards
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 14.dp,
                            bottom = 40.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 1. Compact Capture & Source Bar Card
                        item(key = "ocr_capture_controls") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = onTakePictureClicked,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("ocr_take_photo_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PhotoCamera,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.ocr_take_photo_action),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        FilledTonalButton(
                                            onClick = onUploadImageClicked,
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("ocr_upload_image_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AddPhotoAlternate,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = stringResource(R.string.ocr_upload_image_action),
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    // Compact Options Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        FilterChip(
                                            selected = highContrastEnabled,
                                            onClick = {
                                                val newContrast = !highContrastEnabled
                                                highContrastEnabled = newContrast
                                                val currentUri = activeUri
                                                val currentBmp = previewBitmap
                                                if (currentUri != null) {
                                                    runOcrOnUri(currentUri, activeSourceLabel, newContrast)
                                                } else if (currentBmp != null) {
                                                    runOcrOnBitmap(currentBmp, activeSourceLabel, newContrast)
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = "High-Contrast",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = if (highContrastEnabled) {
                                                        Icons.Default.Check
                                                    } else {
                                                        Icons.Default.AutoFixHigh
                                                    },
                                                    contentDescription = null,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            },
                                            modifier = Modifier.testTag("ocr_high_contrast_toggle")
                                        )

                                        AssistChip(
                                            onClick = {
                                                val sampleBmp = ImageOcrExtractor.createSampleTextDocumentBitmap()
                                                runOcrOnBitmap(
                                                    sampleBmp,
                                                    "Sample Receipt",
                                                    highContrastEnabled
                                                )
                                            },
                                            label = {
                                                Text(
                                                    text = "Sample Receipt",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            },
                                            modifier = Modifier.testTag("ocr_try_sample_image_button")
                                        )
                                    }

                                    // Integrated Compact Source Image Strip when an image is loaded
                                    val currentPreview = previewBitmap
                                    if (currentPreview != null) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                        )

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(MaterialTheme.shapes.small)
                                                .clickable {
                                                    showExpandedImagePreview = !showExpandedImagePreview
                                                }
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Image(
                                                bitmap = currentPreview.asImageBitmap(),
                                                contentDescription = "Captured or uploaded image for OCR",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(MaterialTheme.shapes.extraSmall)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .testTag("ocr_source_image_preview")
                                            )

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = activeSourceLabel,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                ocrResult?.let { res ->
                                                    Text(
                                                        text = "${res.lineCount} lines • ${res.wordCount} words • ${res.charCount} chars",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }

                                            Text(
                                                text = if (showExpandedImagePreview) "Hide Image" else "View Image",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        if (showExpandedImagePreview) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(min = 120.dp, max = 220.dp)
                                                    .clip(MaterialTheme.shapes.small)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Image(
                                                    bitmap = currentPreview.asImageBitmap(),
                                                    contentDescription = "Expanded source image preview",
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Loading Indicator during ML Kit OCR Execution
                        if (isProcessingOcr) {
                            item(key = "ocr_loading") {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.5.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Extracting text from $activeSourceLabel…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        val currentResult = ocrResult
                        if (currentResult != null) {
                            // 3. Extracted Text Card (Clean editor + color highlight filter chips)
                            item(key = "ocr_extracted_text_result") {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = MaterialTheme.shapes.medium,
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "Extracted Text",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )

                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                FilledTonalButton(
                                                    onClick = copyExtractedText,
                                                    enabled = editableExtractedText.isNotBlank(),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .height(34.dp)
                                                        .testTag("ocr_copy_text_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.ContentCopy,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                    Text(
                                                        text = stringResource(R.string.ocr_copy_extracted_text),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }

                                                OutlinedButton(
                                                    onClick = shareExtractedText,
                                                    enabled = editableExtractedText.isNotBlank(),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                    modifier = Modifier
                                                        .height(34.dp)
                                                        .testTag("ocr_share_text_button")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Share,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                    Text(
                                                        text = stringResource(R.string.ocr_share_extracted_text),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            }
                                        }

                                        if (editableExtractedText.isBlank() && currentResult.wordCount == 0) {
                                            Surface(
                                                shape = MaterialTheme.shapes.small,
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = "No readable text detected. Try enabling High-Contrast above or taking a clearer photo.",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(12.dp)
                                                )
                                            }
                                        } else {
                                            val posVisualTransformation = remember(
                                                posBreakdown.wordSpans,
                                                selectedPosFilter
                                            ) {
                                                PosHighlightVisualTransformation(
                                                    wordSpans = posBreakdown.wordSpans,
                                                    activeCategoryFilter = selectedPosFilter
                                                )
                                            }

                                            SelectionContainer {
                                                OutlinedTextField(
                                                    value = editableExtractedText,
                                                    onValueChange = { editableExtractedText = it },
                                                    visualTransformation = posVisualTransformation,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(min = 140.dp, max = 340.dp)
                                                        .testTag("ocr_extracted_text_field"),
                                                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 4. Separate Top-Level POS Breakdown & Bar Graph Card + Final Fiction Prediction Card
                            if (editableExtractedText.isNotBlank() || currentResult.wordCount > 0) {
                                item(key = "ocr_pos_and_prediction_section") {
                                    OcrWordAndPosBreakdownSection(
                                        posBreakdown = posBreakdown,
                                        selectedCategory = selectedPosFilter,
                                        onSelectCategory = { clickedCategory ->
                                            selectedPosFilter = if (selectedPosFilter == clickedCategory) {
                                                null
                                            } else {
                                                clickedCategory
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        } else if (!isProcessingOcr) {
                            // Clean Empty State before user snaps or uploads an image
                            item(key = "ocr_empty_state") {
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(52.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.DocumentScanner,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }

                                        Text(
                                            text = "Snap a Photo or Upload an Image",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )

                                        Text(
                                            text = "Extract text on-device, highlight parts of speech by color, and classify Fiction vs. Non-Fiction.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class PosHighlightVisualTransformation(
    private val wordSpans: List<PosWordSpan>,
    private val activeCategoryFilter: MajorPosCategory?
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = OnDevicePosTagger.buildPosHighlightedAnnotatedString(
            text = text.text,
            wordSpans = wordSpans,
            activeCategoryFilter = activeCategoryFilter
        )
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}

private data class PosBarItem(
    val category: MajorPosCategory,
    val label: String,
    val shortTag: String,
    val colorName: String,
    val count: Int,
    val barColor: Color,
    val highlightBgColor: Color,
    val badgeTextColor: Color,
    val examples: List<String>,
    val barTestTag: String,
    val cardTestTag: String
)

@Composable
private fun OcrWordAndPosBreakdownSection(
    posBreakdown: PosTagBreakdown,
    selectedCategory: MajorPosCategory?,
    onSelectCategory: (MajorPosCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    val barItems = remember(posBreakdown) {
        listOf(
            PosBarItem(
                category = MajorPosCategory.NOUN,
                label = "Nouns",
                shortTag = "NOUN",
                colorName = "Yellow",
                count = posBreakdown.nounCount,
                barColor = PosNounYellow,
                highlightBgColor = PosNounYellowHighlight,
                badgeTextColor = PosNounYellowBadgeText,
                examples = posBreakdown.nounExamples,
                barTestTag = "ocr_pos_bar_noun",
                cardTestTag = "ocr_pos_noun_count"
            ),
            PosBarItem(
                category = MajorPosCategory.VERB,
                label = "Verbs",
                shortTag = "VERB",
                colorName = "Cyan",
                count = posBreakdown.verbCount,
                barColor = PosVerbCyan,
                highlightBgColor = PosVerbCyanHighlight,
                badgeTextColor = PosVerbCyanBadgeText,
                examples = posBreakdown.verbExamples,
                barTestTag = "ocr_pos_bar_verb",
                cardTestTag = "ocr_pos_verb_count"
            ),
            PosBarItem(
                category = MajorPosCategory.ADJECTIVE,
                label = "Adjectives",
                shortTag = "ADJ",
                colorName = "Green",
                count = posBreakdown.adjectiveCount,
                barColor = PosAdjectiveEmerald,
                highlightBgColor = PosAdjectiveEmeraldHighlight,
                badgeTextColor = PosAdjectiveEmeraldBadgeText,
                examples = posBreakdown.adjectiveExamples,
                barTestTag = "ocr_pos_bar_adjective",
                cardTestTag = "ocr_pos_adjective_count"
            ),
            PosBarItem(
                category = MajorPosCategory.ADVERB,
                label = "Adverbs",
                shortTag = "ADV",
                colorName = "Violet",
                count = posBreakdown.adverbCount,
                barColor = PosAdverbViolet,
                highlightBgColor = PosAdverbVioletHighlight,
                badgeTextColor = PosAdverbVioletBadgeText,
                examples = posBreakdown.adverbExamples,
                barTestTag = "ocr_pos_bar_adverb",
                cardTestTag = "ocr_pos_adverb_count"
            ),
            PosBarItem(
                category = MajorPosCategory.PRONOUN,
                label = "Pronouns",
                shortTag = "PRON",
                colorName = "Rose",
                count = posBreakdown.pronounCount,
                barColor = PosPronounRose,
                highlightBgColor = PosPronounRoseHighlight,
                badgeTextColor = PosPronounRoseBadgeText,
                examples = posBreakdown.pronounExamples,
                barTestTag = "ocr_pos_bar_pronoun",
                cardTestTag = "ocr_pos_pronoun_count"
            )
        )
    }

    Column(
        modifier = modifier.testTag("ocr_pos_breakdown_section"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Card A: Word Count & POS Bar Graph + Compact Tag List
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header Row: Title + Total Word Count Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Part-of-Speech (POS) Breakdown",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Tap any bar or tag to filter word highlights",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.testTag("ocr_total_word_count_badge")
                    ) {
                        Text(
                            text = "${posBreakdown.totalWords} ${if (posBreakdown.totalWords == 1) "Word" else "Words"}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PosLegendChip(
                        label = "All Colors",
                        swatchColor = MaterialTheme.colorScheme.primary,
                        isSelected = selectedCategory == null,
                        onClick = { onSelectCategory(null) },
                        testTag = "ocr_pos_filter_all"
                    )
                    barItems.forEach { item ->
                        PosLegendChip(
                            label = item.label,
                            swatchColor = item.barColor,
                            isSelected = selectedCategory == item.category,
                            onClick = { onSelectCategory(item.category) },
                            testTag = "ocr_pos_filter_${item.shortTag.lowercase(Locale.ROOT)}"
                        )
                    }
                }

                // Clean 5-Column Vertical Bar Graph (no duplicate horizontal chart)
                OcrPosBarGraphCard(
                    barItems = barItems,
                    selectedCategory = selectedCategory,
                    onSelectCategory = onSelectCategory
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

                // Compact 5-Row POS Breakdown List (Color dot + Label + Sample Words + Count)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    barItems.forEach { item ->
                        PosCompactRowItem(
                            item = item,
                            totalWords = posBreakdown.totalWords,
                            isSelected = selectedCategory == item.category,
                            onClick = { onSelectCategory(item.category) }
                        )
                    }
                }
            }
        }

        // Card B: Final Output — Fiction vs. Non-Fiction Prediction
        OcrFictionPredictionFinalOutputCard(
            posBreakdown = posBreakdown,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OcrFictionPredictionFinalOutputCard(
    posBreakdown: PosTagBreakdown,
    modifier: Modifier = Modifier
) {
    val rAdjPronStr = remember(posBreakdown.rAdjPron) {
        String.format(Locale.US, "%.4f", posBreakdown.rAdjPron)
    }
    val rAdvAdjStr = remember(posBreakdown.rAdvAdj) {
        String.format(Locale.US, "%.4f", posBreakdown.rAdvAdj)
    }
    val zStr = remember(posBreakdown.logitZ) {
        String.format(Locale.US, "%.4f", posBreakdown.logitZ)
    }
    val probFictionDecimalStr = remember(posBreakdown.probabilityOfFiction) {
        String.format(Locale.US, "%.4f", posBreakdown.probabilityOfFiction)
    }
    val probFictionPercentStr = remember(posBreakdown.probabilityOfFiction) {
        String.format(Locale.US, "%.1f%%", posBreakdown.probabilityOfFiction * 100.0)
    }
    val probNonFictionPercentStr = remember(posBreakdown.probabilityOfFiction) {
        String.format(Locale.US, "%.1f%%", (1.0 - posBreakdown.probabilityOfFiction) * 100.0)
    }

    val isFiction = posBreakdown.isFictionPredicted
    val accentColor = if (isFiction) PosAdverbViolet else PosVerbCyan
    val badgeBgColor = if (isFiction) PosAdverbVioletHighlight else PosVerbCyanHighlight

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.25.dp, accentColor.copy(alpha = 0.65f)),
        modifier = modifier.testTag("ocr_fiction_prediction_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Clean Hero Header: Title + Predicted Verdict Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Genre Classification",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Based on RADJPRON & RADVADJ ratios",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = badgeBgColor,
                    border = BorderStroke(1.dp, accentColor),
                    modifier = Modifier.testTag("ocr_fiction_prediction_badge")
                ) {
                    Text(
                        text = posBreakdown.predictedGenreLabel.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }

            // 2. Probability of Fiction Hero Readout + Clean Progress Bar
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "Probability of Fiction",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "$probFictionPercentStr ($probFictionDecimalStr)",
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.testTag("ocr_probability_of_fiction")
                    )
                }

                val fictionFraction = posBreakdown.probabilityOfFiction.toFloat().coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = fictionFraction)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Non-Fiction ($probNonFictionPercentStr)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!isFiction) PosVerbCyan else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Fiction ($probFictionPercentStr)",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isFiction) PosAdverbViolet else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3. Two Clean Side-by-Side Ratio Tiles: RADJPRON & RADVADJ
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ocr_ratio_radjpron")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "RADJPRON (ADJ / PRON)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = PosAdjectiveEmerald
                        )
                        Text(
                            text = rAdjPronStr,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${posBreakdown.adjectiveCount} Adj / ${posBreakdown.pronounCount} Pron",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ocr_ratio_radvadj")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "RADVADJ (ADV / ADJ)",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = PosAdverbViolet
                        )
                        Text(
                            text = rAdvAdjStr,
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${posBreakdown.adverbCount} Adv / ${posBreakdown.adjectiveCount} Adj",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Compact Logistic Equation Readout
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "z = 1.7501 - 4.3818($rAdjPronStr) + 2.0998($rAdvAdjStr) = $zStr",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("ocr_fiction_logit_z")
                    )
                    Text(
                        text = "P(Fiction) = 1 / (1 + e^(-z)) = $probFictionDecimalStr",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PosLegendChip(
    label: String,
    swatchColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = CircleShape,
        color = if (isSelected) {
            swatchColor.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) swatchColor else Color.Transparent
        ),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(swatchColor)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun OcrPosBarGraphCard(
    barItems: List<PosBarItem>,
    selectedCategory: MajorPosCategory?,
    onSelectCategory: (MajorPosCategory?) -> Unit
) {
    val maxCount = remember(barItems) {
        max(1, barItems.maxOfOrNull { it.count } ?: 1)
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ocr_pos_bar_graph")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(128.dp)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            barItems.forEach { item ->
                val fraction = (item.count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
                val isHighlighted = selectedCategory == null || selectedCategory == item.category
                val effectiveBarColor = if (isHighlighted) {
                    item.barColor
                } else {
                    item.barColor.copy(alpha = 0.3f)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { onSelectCategory(item.category) }
                        .testTag(item.barTestTag),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        text = item.count.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = effectiveBarColor
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        ComposeCanvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp)
                        ) {
                            val cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            drawRoundRect(
                                color = trackColor,
                                topLeft = Offset.Zero,
                                size = size,
                                cornerRadius = cornerRadius
                            )
                            val minBarHeightPx = if (item.count > 0) 8.dp.toPx() else 3.dp.toPx()
                            val barHeightPx = max(minBarHeightPx, size.height * fraction)
                            drawRoundRect(
                                color = effectiveBarColor,
                                topLeft = Offset(0f, size.height - barHeightPx),
                                size = Size(size.width, barHeightPx),
                                cornerRadius = cornerRadius
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(5.dp))

                    Text(
                        text = item.shortTag,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isHighlighted) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun PosCompactRowItem(
    item: PosBarItem,
    totalWords: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val sharePercent = if (totalWords > 0) {
        ((item.count.toFloat() / totalWords.toFloat()) * 100f).roundToInt()
    } else {
        0
    }

    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (isSelected) {
            item.highlightBgColor.copy(alpha = 0.2f)
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .testTag(item.cardTestTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(item.barColor)
            )

            Text(
                text = item.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(76.dp)
            )

            Text(
                text = if (item.examples.isNotEmpty()) {
                    item.examples.take(3).joinToString(", ")
                } else {
                    "—"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = "${item.count} ($sharePercent%)",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.Bold,
                color = item.barColor,
                textAlign = TextAlign.End
            )
        }
    }
}


