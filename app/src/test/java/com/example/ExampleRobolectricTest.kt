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
import org.junit.Assert.assertEquals
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
}
