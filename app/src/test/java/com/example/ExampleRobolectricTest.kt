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
}

