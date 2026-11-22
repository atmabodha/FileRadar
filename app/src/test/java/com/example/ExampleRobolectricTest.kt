package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ai.OnDeviceAiEngine
import com.example.model.FileCategory
import com.example.model.FileFormatters
import com.example.model.PhoneFileItem
import com.example.model.StorageLocation
import com.example.ui.components.PdfChatEngineOption
import com.example.ui.components.PlaceholderGeminiSubscriptionRepository
import com.example.ui.components.cleanAndSanitizePdfPageText
import com.example.ui.components.generateLocalGemmaE2bPdfAnswer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("File Radar", appName)
    }

    @Test
    fun `file category detection and size formatting work accurately`() {
        assertEquals(FileCategory.PDF, FileCategory.fromExtension("pdf"))
        assertEquals(FileCategory.IMAGE, FileCategory.fromExtension("png"))
        assertEquals(FileCategory.VIDEO, FileCategory.fromExtension("mp4"))
        assertEquals(FileCategory.AUDIO, FileCategory.fromExtension("flac"))
        assertEquals(FileCategory.ARCHIVE, FileCategory.fromExtension("zip"))
        assertTrue(FileFormatters.formatFileSize(4_852_190L).contains("MB"))
    }

    @Test
    fun `clicking All File Types dropdown opens menu and selects PDF category`() {
        composeTestRule.onNodeWithTag("file_type_dropdown_trigger")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("dropdown_category_PDF")
            .assertIsDisplayed()
            .performClick()
    }

    @Test
    fun `bottom navigation switches between Standard, AI Search, and Settings screens`() {
        composeTestRule.onNodeWithTag("nav_tab_ai_search")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("ai_search_screen")
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("ai_file_type_dropdown_trigger")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("ai_dropdown_category_PDF")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("ai_sort_by_dropdown_trigger")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("ai_sort_menu_item_SIZE")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("ai_sort_order_toggle")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("nav_tab_settings")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("ai_settings_screen")
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("preset_lens_input")
            .assertExists()

        composeTestRule.onNodeWithTag("preset_lens_chip_Blood Report")
            .assertExists()

        composeTestRule.onNodeWithTag("preset_lens_chip_Bank Records")
            .assertExists()

        composeTestRule.onNodeWithTag("nav_tab_standard")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("file_results_list")
            .assertIsDisplayed()
    }

    @Test
    fun `on-device sentence chunker preserves overlap across chunks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = OnDeviceAiEngine(context)
        val longText = (1..25).joinToString(" ") {
            "Sentence number $it describes cloud infrastructure invoices, monitor receipts, and Tokyo flight boarding passes."
        }
        val chunks = engine.chunkTextBySentences(
            fileId = "test_doc",
            fileName = "test.pdf",
            text = longText,
            chunkSizeTokens = 64,
            chunkOverlapTokens = 15
        )
        assertTrue(chunks.size >= 2)
        assertEquals(64, chunks.first().embedding.size)
    }

    @Test
    fun `pdf text data cleaning strips junk symbols and garbled runs while preserving clean content`() {
        val noisyPdfInput = """
            I N V O I C E
            ~`^|\<>{}@#$%*_+=  a3${'$'}B9!k  /F12  begincmap  endstream
            Patient Hemoglobin: 14.8 g/dL (Normal Reference: 13.2 - 16.6)
            bcdfghjklmnpqrstvwxz ~~~ === *** \uFFFD\uE001\uF0B7
            The total statement balance of ${'$'}1,420.50 was verifi-
            ed on 2026-11-18.
        """.trimIndent()

        val cleaned = cleanAndSanitizePdfPageText(noisyPdfInput)
        assertTrue(cleaned.contains("INVOICE"))
        assertTrue(cleaned.contains("Patient Hemoglobin: 14.8 g/dL"))
        assertTrue(cleaned.contains("verified on 2026-11-18."))
        assertFalse(cleaned.contains("a3\$B9!k"))
        assertFalse(cleaned.contains("begincmap"))
        assertFalse(cleaned.contains("bcdfghjklmnpqrstvwxz"))
    }

    @Test
    fun `pdf chat answers questions using extracted pages in both Local Gemma E2B and Gemini Subscription modes`() = runBlocking {
        val samplePdf = PhoneFileItem(
            id = "pdf_test_1",
            name = "Clinical_Blood_Report_2026.pdf",
            fullPath = "/storage/emulated/0/Documents/Clinical_Blood_Report_2026.pdf",
            extension = "pdf",
            mimeType = "application/pdf",
            sizeBytes = 420_000L,
            createdAtMillis = 1_760_000_000_000L,
            modifiedAtMillis = 1_760_000_000_000L,
            category = FileCategory.PDF,
            storageLocation = StorageLocation.INTERNAL,
            volumeLabel = "Internal Shared",
            isSampleFile = true
        )
        val pages = listOf(
            "Page 1: Hemoglobin (Hb) is 14.8 g/dL within normal reference range.",
            "Page 2: Fasting Plasma Glucose is 89 mg/dL and HbA1c is 5.2%."
        )

        val localAnswer = generateLocalGemmaE2bPdfAnswer(samplePdf, pages, "What is the Hemoglobin value?")
        assertEquals(PdfChatEngineOption.LOCAL_GEMMA_E2B, localAnswer.engine)
        assertTrue(localAnswer.text.contains("14.8 g/dL"))
        assertTrue(localAnswer.pageCitations.contains("Page 1"))

        val geminiRepo = PlaceholderGeminiSubscriptionRepository()
        val geminiAnswer = geminiRepo.generateGeminiSubscriptionAnswer(samplePdf, pages, "What is the HbA1c value?")
        assertEquals(PdfChatEngineOption.GEMINI_SUBSCRIPTION, geminiAnswer.engine)
        assertTrue(geminiAnswer.isSamplePlaceholder)
        assertTrue(geminiAnswer.text.contains("5.2%"))
        assertTrue(geminiAnswer.pageCitations.contains("Page 2"))
    }

    @Test
    fun `hybrid pdf TextNormalizer and TextChunker produce sentence-aware overlapping chunks for Local Gemma E2B`() {
        val samplePdf = PhoneFileItem(
            id = "pdf_test_hybrid",
            name = "Diagnostic_Summary_2026.pdf",
            fullPath = "/storage/emulated/0/Documents/Diagnostic_Summary_2026.pdf",
            extension = "pdf",
            mimeType = "application/pdf",
            sizeBytes = 512_000L,
            createdAtMillis = 1_760_000_000_000L,
            modifiedAtMillis = 1_760_000_000_000L,
            category = FileCategory.PDF,
            storageLocation = StorageLocation.INTERNAL,
            volumeLabel = "Internal Shared",
            isSampleFile = true
        )
        val rawPage1 = """
            Clinical Diagnostic Summary Report.
            The patient presented for annual metabolic and hematology evaluation on 2026-11-15.
            Hemoglobin concentration measured 14.8 g/dL, which is well within the normal reference interval of 13.2 to 16.6 g/dL.
            Total cholesterol was recorded at 176 mg/dL with HDL cholesterol at 58 mg/dL and LDL cholesterol at 98 mg/dL.
            No acute hematological or lipid abnormalities were identi-
            fied during this diagnostic screening panel.
        """.trimIndent()
        val rawPage2 = """
            Metabolic & Glycemic Follow-up Recommendations.
            Fasting plasma glucose was 89 mg/dL and HbA1c measured 5.2%, confirming normal glycemic control.
            Vitamin D (25-Hydroxy) was 41 ng/mL and TSH was 1.84 mIU/L.
            Routine annual follow-up testing is recommended in 12 months with continued balanced nutrition.
        """.trimIndent()

        val norm1 = com.example.pdfextractor.TextNormalizer.normalize(rawPage1)
        val norm2 = com.example.pdfextractor.TextNormalizer.normalize(rawPage2)
        assertTrue(norm1.contains("identified during this diagnostic"))

        val extractedPages = listOf(
            com.example.pdfextractor.model.ExtractedPage(
                pageNumber = 1,
                rawText = rawPage1,
                normalizedText = norm1,
                source = com.example.pdfextractor.model.ExtractionSource.PDF_TEXT
            ),
            com.example.pdfextractor.model.ExtractedPage(
                pageNumber = 2,
                rawText = rawPage2,
                normalizedText = norm2,
                source = com.example.pdfextractor.model.ExtractionSource.ML_KIT_OCR
            )
        )
        val docUri = android.net.Uri.parse("file:///storage/emulated/0/Documents/Diagnostic_Summary_2026.pdf")
        val chunks = com.example.pdfextractor.TextChunker.chunkPages(
            documentUri = docUri,
            pages = extractedPages,
            maxChunkChars = 220,
            overlapChars = 60
        )
        assertTrue(chunks.size >= 3)

        val extractionResult = com.example.pdfextractor.model.DocumentExtractionResult(
            documentUri = docUri,
            totalPages = 2,
            pages = extractedPages,
            chunks = chunks
        )

        val summaryAnswer = generateLocalGemmaE2bPdfAnswer(
            file = samplePdf,
            pagesText = listOf(norm1, norm2),
            question = "Summarize this PDF and list key takeaways",
            extractionResult = extractionResult
        )
        assertTrue(summaryAnswer.text.contains("Executive Summary"))
        assertTrue(summaryAnswer.text.contains("14.8 g/dL") || summaryAnswer.text.contains("89 mg/dL"))
        assertTrue(summaryAnswer.pageCitations.isNotEmpty())
    }

    @Test
    fun `cross-page running header stripping, section inheritance, and multi-turn conversational memory work accurately`() {
        val samplePdf = PhoneFileItem(
            id = "pdf_test_advanced_rag",
            name = "Apex_Hospital_Panel_2026.pdf",
            fullPath = "/storage/emulated/0/Documents/Apex_Hospital_Panel_2026.pdf",
            extension = "pdf",
            mimeType = "application/pdf",
            sizeBytes = 640_000L,
            createdAtMillis = 1_760_000_000_000L,
            modifiedAtMillis = 1_760_000_000_000L,
            category = FileCategory.PDF,
            storageLocation = StorageLocation.INTERNAL,
            volumeLabel = "Internal Shared",
            isSampleFile = true
        )

        val rawPages = listOf(
            """
                Apex Clinical Diagnostics Center - Confidential Report
                1. HEMATOLOGY PANEL
                Hemoglobin | 14.8 g/dL | 13.2 - 16.6 g/dL
                White Blood Cell Count | 6.4 x10^3/uL | 4.5 - 11.0 x10^3/uL
                Page 1 of 3
            """.trimIndent(),
            """
                Apex Clinical Diagnostics Center - Confidential Report
                2. LIPID METABOLIC PANEL
                Total Cholesterol | 176 mg/dL | < 200 mg/dL
                LDL Cholesterol | 98 mg/dL | < 100 mg/dL
                Page 2 of 3
            """.trimIndent(),
            """
                Apex Clinical Diagnostics Center - Confidential Report
                3. THYROID & VITAMIN PANEL
                TSH (Thyroid Stimulating Hormone) | 1.84 mIU/L | 0.45 - 4.50 mIU/L
                Vitamin D 25-Hydroxy | 41 ng/mL | 30 - 100 ng/mL
                Page 3 of 3
            """.trimIndent()
        ).mapIndexed { index, raw ->
            com.example.pdfextractor.model.ExtractedPage(
                pageNumber = index + 1,
                rawText = raw,
                normalizedText = com.example.pdfextractor.TextNormalizer.normalize(raw),
                source = com.example.pdfextractor.model.ExtractionSource.ML_KIT_OCR
            )
        }

        // Verify cross-page running header ("Apex Clinical Diagnostics Center - Confidential Report") is stripped
        val (cleanedPages, removedCount) = com.example.pdfextractor.TextNormalizer.stripCrossPageRunningHeadersAndFooters(rawPages)
        assertTrue(removedCount >= 3)
        assertFalse(cleanedPages[0].normalizedText.contains("Apex Clinical Diagnostics Center"))
        assertTrue(cleanedPages[0].detectedSections.any { it.contains("HEMATOLOGY PANEL") })

        val docUri = android.net.Uri.parse("file:///storage/emulated/0/Documents/Apex_Hospital_Panel_2026.pdf")
        val chunks = com.example.pdfextractor.TextChunker.chunkPages(docUri, cleanedPages)
        assertTrue(chunks.any { it.sectionHeading?.contains("HEMATOLOGY PANEL") == true })

        val extraction = com.example.pdfextractor.model.DocumentExtractionResult(
            documentUri = docUri,
            totalPages = cleanedPages.size,
            pages = cleanedPages,
            chunks = chunks,
            removedRunningHeadersCount = removedCount
        )

        // Turn 1: Ask about Hemoglobin
        val turn1User = com.example.ui.components.PdfChatMessage(
            id = "u1",
            isUser = true,
            engine = PdfChatEngineOption.LOCAL_GEMMA_E2B,
            text = "What is the Hemoglobin value?"
        )
        val turn1Reply = generateLocalGemmaE2bPdfAnswer(
            file = samplePdf,
            pagesText = cleanedPages.map { it.normalizedText },
            question = turn1User.text,
            extractionResult = extraction
        )
        assertTrue(turn1Reply.text.contains("14.8 g/dL"))

        // Turn 2: Ask a pronoun follow-up ("What is the normal range for it?") relying on conversational memory
        val turn2Reply = generateLocalGemmaE2bPdfAnswer(
            file = samplePdf,
            pagesText = cleanedPages.map { it.normalizedText },
            question = "What is the normal range for it?",
            extractionResult = extraction,
            conversationHistory = listOf(turn1User, turn1Reply)
        )
        assertTrue(turn2Reply.text.contains("13.2 - 16.6") || turn2Reply.text.contains("14.8 g/dL"))
        assertTrue(turn2Reply.text.contains("Follow-up Context"))
    }

    @Test
    fun `clicking Scan Text OCR button opens Image Text Scanner with Take Picture and Upload Image options`() {
        composeTestRule.onNodeWithTag("open_image_ocr_scanner_button")
            .assertIsDisplayed()
            .performClick()

        composeTestRule.onNodeWithTag("image_ocr_scanner_dialog")
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("ocr_take_photo_button")
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("ocr_upload_image_button")
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("ocr_high_contrast_toggle")
            .assertIsDisplayed()

        composeTestRule.onNodeWithTag("close_image_ocr_scanner_button")
            .assertIsDisplayed()
            .performClick()
    }

    @Test
    fun `on-device POS tagger accurately counts total words, nouns, verbs, adjectives, adverbs, pronouns and highlights spans with matching bar colors`() {
        val sampleOcrText =
            "They quickly verified our clinical report and confirmed that the normal hemoglobin value is completely valid."
        val breakdown = com.example.ui.components.OnDevicePosTagger.analyzeText(sampleOcrText)

        assertEquals(16, breakdown.totalWords)
        assertEquals(16, breakdown.wordSpans.size)
        assertTrue(breakdown.pronounCount >= 2)   // "They", "our", "that"
        assertTrue(breakdown.adverbCount >= 2)    // "quickly", "completely"
        assertTrue(breakdown.verbCount >= 3)      // "verified", "confirmed", "is"
        assertTrue(breakdown.adjectiveCount >= 3) // "clinical", "normal", "valid"
        assertTrue(breakdown.nounCount >= 2)      // "report", "hemoglobin", "value"

        // Verify Noun bar color is Yellow and every Noun span is highlighted with Yellow
        assertEquals(
            com.example.ui.theme.PosNounYellow,
            com.example.ui.components.OnDevicePosTagger.barColorFor(
                com.example.ui.components.MajorPosCategory.NOUN
            )
        )

        val annotatedAll = com.example.ui.components.OnDevicePosTagger.buildPosHighlightedAnnotatedString(
            text = sampleOcrText,
            wordSpans = breakdown.wordSpans,
            activeCategoryFilter = null
        )
        assertEquals(sampleOcrText, annotatedAll.text)
        assertTrue(annotatedAll.spanStyles.isNotEmpty())

        // Filter specifically to NOUN and verify only noun spans get PosNounYellowHighlight background
        val annotatedNounsOnly = com.example.ui.components.OnDevicePosTagger.buildPosHighlightedAnnotatedString(
            text = sampleOcrText,
            wordSpans = breakdown.wordSpans,
            activeCategoryFilter = com.example.ui.components.MajorPosCategory.NOUN
        )
        assertEquals(breakdown.nounCount, annotatedNounsOnly.spanStyles.size)
        assertTrue(
            annotatedNounsOnly.spanStyles.all {
                it.item.background == com.example.ui.theme.PosNounYellowHighlight
            }
        )

        // Verify exact RADJPRON, RADVADJ, z, and Probability of Fiction formula
        val expectedRAdjPron = breakdown.adjectiveCount.toDouble() / breakdown.pronounCount.toDouble()
        val expectedRAdvAdj = breakdown.adverbCount.toDouble() / breakdown.adjectiveCount.toDouble()
        val expectedZ = 1.7501 + (-4.3818 * expectedRAdjPron) + (2.0998 * expectedRAdvAdj)
        val expectedProbFiction = 1.0 / (1.0 + kotlin.math.exp(-expectedZ))

        assertEquals(expectedRAdjPron, breakdown.rAdjPron, 1e-6)
        assertEquals(expectedRAdvAdj, breakdown.rAdvAdj, 1e-6)
        assertEquals(expectedZ, breakdown.logitZ, 1e-6)
        assertEquals(expectedProbFiction, breakdown.probabilityOfFiction, 1e-6)

        // Verify a heavy technical non-fiction passage predicts Non-Fiction (high RADJPRON, low RADVADJ)
        val nonFictionBreakdown = com.example.ui.components.OnDevicePosTagger.analyzeText(
            "Clinical diagnostic metabolic hematological panel shows normal annual fasting glucose range."
        )
        assertFalse(nonFictionBreakdown.isFictionPredicted)
        assertEquals("Non-Fiction", nonFictionBreakdown.predictedGenreLabel)

        // Verify a pronoun- and adverb-rich narrative passage predicts Fiction (low RADJPRON, high RADVADJ)
        val fictionBreakdown = com.example.ui.components.OnDevicePosTagger.analyzeText(
            "She quietly walked to him and they suddenly ran away together."
        )
        assertTrue(fictionBreakdown.isFictionPredicted)
        assertEquals("Fiction", fictionBreakdown.predictedGenreLabel)
    }
}




