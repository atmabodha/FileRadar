package com.example.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.model.PhoneFileItem
import com.example.pdfextractor.HybridPdfExtractor
import com.example.pdfextractor.model.DocumentExtractionResult
import com.example.pdfextractor.model.ExtractedPage
import com.example.pdfextractor.model.ExtractionConfig
import com.example.pdfextractor.model.ExtractionSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.zip.Inflater
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private object PdfPageBitmapCache {
    private val cache = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    fun get(key: String): Bitmap? = synchronized(cache) { cache.get(key) }

    fun put(key: String, bitmap: Bitmap) {
        synchronized(cache) {
            if (cache.get(key) == null) {
                cache.put(key, bitmap)
            }
        }
    }
}

private class PdfRenderSession(
    val sessionId: String,
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    private val renderMutex = Mutex()
    val pageCount: Int = renderer.pageCount.coerceAtLeast(1)

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? {
        val cacheKey = "${sessionId}_p${pageIndex}_w${targetWidthPx}"
        PdfPageBitmapCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            renderMutex.withLock {
                PdfPageBitmapCache.get(cacheKey)?.let { return@withLock it }
                try {
                    if (pageIndex !in 0 until renderer.pageCount) return@withLock null
                    renderer.openPage(pageIndex).use { page ->
                        val rawW = page.width.coerceAtLeast(1)
                        val rawH = page.height.coerceAtLeast(1)
                        val scale = targetWidthPx.toFloat() / rawW.toFloat()
                        val bitmapW = targetWidthPx.coerceAtLeast(1)
                        val bitmapH = (rawH * scale).roundToInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(bitmapW, bitmapH, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(AndroidColor.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        PdfPageBitmapCache.put(cacheKey, bitmap)
                        bitmap
                    }
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    fun close() {
        try {
            renderer.close()
        } catch (_: Throwable) {
        }
        try {
            pfd.close()
        } catch (_: Throwable) {
        }
    }
}

@Composable
fun FullScreenPdfReaderDialog(
    file: PhoneFileItem,
    onCopyPath: (String) -> Unit,
    onShareFile: (PhoneFileItem) -> Unit,
    onOpenMetadata: (PhoneFileItem) -> Unit,
    onDismiss: () -> Unit,
    onChatPdf: ((PhoneFileItem) -> Unit)? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val pageListState = rememberLazyListState()

    var renderSession by remember(file.id) { mutableStateOf<PdfRenderSession?>(null) }
    var pageCount by remember(file.id) { mutableIntStateOf(1) }
    var isPreparingDocument by remember(file.id) { mutableStateOf(true) }
    var isTextMode by remember(file.id) { mutableStateOf(false) }
    var forceVisualOcrMode by remember(file.id) { mutableStateOf(false) }
    var showExecutiveSummaryCard by remember(file.id) { mutableStateOf(true) }
    var textFontSizeSp by remember(file.id) { mutableFloatStateOf(15f) }
    var zoomScale by remember(file.id) { mutableFloatStateOf(1f) }
    var extractionResultState by remember(file.id) { mutableStateOf<DocumentExtractionResult?>(null) }
    var extractedPages by remember(file.id) { mutableStateOf<List<ExtractedPage>>(emptyList()) }
    var isExtractingText by remember(file.id) { mutableStateOf(false) }
    var extractionProgressText by remember(file.id) { mutableStateOf("Extracting text (PDFBox + ML Kit Visual OCR)…") }
    val hybridExtractor = remember(context) { HybridPdfExtractor(context.applicationContext) }

    val activeVisiblePage by remember {
        derivedStateOf {
            pageListState.firstVisibleItemIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
    }

    // Open the real PDF file (via File, ContentUri, or MediaStore lookup) or synthesize sample PDF for virtual items
    LaunchedEffect(file.id) {
        isPreparingDocument = true
        val opened = withContext(Dispatchers.IO) {
            openPdfRenderSession(context, file)
        }
        renderSession = opened
        pageCount = opened?.pageCount ?: 1
        isPreparingDocument = false
    }

    // Extract text via HybridPdfExtractor (Smart Hybrid Fusion OR Full Visual ML Kit OCR)
    LaunchedEffect(file.id, isTextMode, forceVisualOcrMode, pageCount) {
        if (isTextMode) {
            isExtractingText = true
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
                        extractionProgressText = if (forceVisualOcrMode) {
                            "Visual OCR Mode: Scanning Page $current of $total via ML Kit Vision…"
                        } else {
                            "Extracting Page $current of $total (Smart Hybrid PDFBox + Visual OCR)…"
                        }
                    }
                )
            }
            extractionResultState = result
            extractedPages = result.pages
            isExtractingText = false
        }
    }

    DisposableEffect(file.id) {
        onDispose {
            renderSession?.close()
            hybridExtractor.close()
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
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("fullscreen_pdf_reader"),
            color = Color(0xFF0A101D)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Reader Bar
                Surface(
                    color = Color(0xFF111A2E),
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
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
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${stringResource(R.string.pdf_reader_title)} • ${file.formattedSize()} • Page ${activeVisiblePage + 1} of $pageCount",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.75f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                TextButton(
                                    onClick = { isTextMode = !isTextMode },
                                    modifier = Modifier.testTag("pdf_reader_mode_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (isTextMode) Icons.Default.Description else Icons.AutoMirrored.Filled.Article,
                                        contentDescription = null,
                                        tint = Color(0xFF67E8F9),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isTextMode) "Page View" else "Text View",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFF67E8F9)
                                    )
                                }

                                if (onChatPdf != null) {
                                    TextButton(
                                        onClick = { onChatPdf(file) },
                                        modifier = Modifier.testTag("pdf_reader_chat_button")
                                    ) {
                                        Text(
                                            text = stringResource(R.string.chat_pdf_action),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF34D399)
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { onCopyPath(file.fullPath) },
                                    modifier = Modifier.testTag("pdf_reader_copy_path_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = stringResource(R.string.copy_path_action),
                                        tint = Color.White
                                    )
                                }

                                IconButton(
                                    onClick = { onShareFile(file) },
                                    modifier = Modifier.testTag("pdf_reader_share_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = stringResource(R.string.share_file_action),
                                        tint = Color(0xFF38BDF8)
                                    )
                                }

                                IconButton(
                                    onClick = { onOpenMetadata(file) },
                                    modifier = Modifier.testTag("pdf_reader_info_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = stringResource(R.string.file_inspector_title),
                                        tint = Color.White
                                    )
                                }

                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.testTag("close_pdf_reader_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close PDF Reader",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
                    }
                }

                // Secondary Toolbar: Zoom controls in Page View OR Visual OCR + Summary + Font Size controls in Text View
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF152038))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (isTextMode) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            TextButton(
                                onClick = { forceVisualOcrMode = !forceVisualOcrMode },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("pdf_reader_visual_ocr_toggle")
                            ) {
                                Text(
                                    text = if (forceVisualOcrMode) "Visual OCR: ON" else "Force Visual OCR",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (forceVisualOcrMode) Color(0xFFFBBF24) else Color(0xFF67E8F9)
                                )
                            }

                            TextButton(
                                onClick = { showExecutiveSummaryCard = !showExecutiveSummaryCard },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("pdf_reader_summary_toggle")
                            ) {
                                Text(
                                    text = if (showExecutiveSummaryCard) "Hide Summary" else "Show Summary",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF34D399)
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = { textFontSizeSp = (textFontSizeSp - 2f).coerceAtLeast(12f) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TextDecrease,
                                    contentDescription = "Decrease font size",
                                    tint = Color.White
                                )
                            }
                            Text(
                                text = "${textFontSizeSp.roundToInt()} sp",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF67E8F9)
                            )
                            IconButton(
                                onClick = { textFontSizeSp = (textFontSizeSp + 2f).coerceAtMost(24f) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TextIncrease,
                                    contentDescription = "Increase font size",
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Scroll vertically to read all $pageCount ${if (pageCount == 1) "page" else "pages"} • Double-tap page to zoom",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = { zoomScale = (zoomScale - 0.25f).coerceAtLeast(1f) },
                                enabled = zoomScale > 1f,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ZoomOut,
                                    contentDescription = "Zoom out",
                                    tint = if (zoomScale > 1f) Color.White else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "${(zoomScale * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF67E8F9)
                            )
                            IconButton(
                                onClick = { zoomScale = (zoomScale + 0.25f).coerceAtMost(2.5f) },
                                enabled = zoomScale < 2.5f,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ZoomIn,
                                    contentDescription = "Zoom in",
                                    tint = if (zoomScale < 2.5f) Color.White else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (zoomScale > 1f) {
                                IconButton(
                                    onClick = { zoomScale = 1f },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .testTag("reset_pdf_zoom_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ZoomOutMap,
                                        contentDescription = "Reset zoom",
                                        tint = Color.White,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Main Reader Content Area: Continuous Multi-Page Vertical Scroll OR Extracted Text View
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isPreparingDocument) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8))
                    } else if (isTextMode) {
                        if (isExtractingText) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(color = Color(0xFF38BDF8))
                                Text(
                                    text = extractionProgressText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                            }
                        } else {
                            val currentResult = extractionResultState
                            val executiveSummaryText = remember(currentResult, file.name) {
                                currentResult?.let {
                                    buildStructuredExecutiveSummary(
                                        fileName = file.name,
                                        extraction = it
                                    )
                                }
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("pdf_text_reader_list"),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                if (showExecutiveSummaryCard && !executiveSummaryText.isNullOrBlank()) {
                                    item(key = "pdf_reader_executive_summary_card") {
                                        Surface(
                                            shape = MaterialTheme.shapes.medium,
                                            color = Color(0xFFECFDF5),
                                            border = BorderStroke(1.5.dp, Color(0xFF10B981)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("pdf_reader_executive_summary_card")
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(18.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "AI Structured Document Summary",
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF065F46)
                                                    )
                                                    Surface(
                                                        shape = MaterialTheme.shapes.extraSmall,
                                                        color = Color(0xFFD1FAE5)
                                                    ) {
                                                        Text(
                                                            text = if (forceVisualOcrMode) "Visual OCR Mode" else "Smart Hybrid Parser",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = Color(0xFF047857),
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                HorizontalDivider(color = Color(0xFFA7F3D0))
                                                Text(
                                                    text = executiveSummaryText,
                                                    fontSize = (textFontSizeSp - 1f).coerceAtLeast(12f).sp,
                                                    lineHeight = ((textFontSizeSp - 1f).coerceAtLeast(12f) * 1.42f).sp,
                                                    color = Color(0xFF064E3B)
                                                )
                                            }
                                        }
                                    }
                                }

                                itemsIndexed(extractedPages) { idx, pageItem ->
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = Color(0xFFF8FAFC),
                                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(20.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = file.name,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = Color(0xFF475569),
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Surface(
                                                    shape = MaterialTheme.shapes.extraSmall,
                                                    color = if (pageItem.source == ExtractionSource.ML_KIT_OCR || pageItem.source == ExtractionSource.FUSED_HYBRID_OCR) {
                                                        Color(0xFFFEF3C7)
                                                    } else {
                                                        Color(0xFFE0F2FE)
                                                    }
                                                ) {
                                                    Text(
                                                        text = pageItem.source.badgeLabel,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (pageItem.source == ExtractionSource.ML_KIT_OCR || pageItem.source == ExtractionSource.FUSED_HYBRID_OCR) {
                                                            Color(0xFFB45309)
                                                        } else {
                                                            Color(0xFF0369A1)
                                                        },
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "Page ${idx + 1} of ${extractedPages.size}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = Color(0xFF0284C7)
                                                )
                                            }
                                            HorizontalDivider(color = Color(0xFFE2E8F0))
                                            Text(
                                                text = pageItem.normalizedText.ifBlank {
                                                    "No readable text detected on Page ${idx + 1}."
                                                },
                                                fontSize = textFontSizeSp.sp,
                                                lineHeight = (textFontSizeSp * 1.45f).sp,
                                                color = Color(0xFF0F172A)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val session = renderSession
                        if (session != null) {
                            LazyColumn(
                                state = pageListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("pdf_pages_lazy_column"),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                items(
                                    count = pageCount,
                                    key = { pageIdx -> "${file.id}_page_$pageIdx" }
                                ) { pageIdx ->
                                    PdfRenderedPageCard(
                                        session = session,
                                        pageIndex = pageIdx,
                                        totalPageCount = pageCount,
                                        zoomScale = zoomScale,
                                        onToggleZoom = {
                                            zoomScale = if (zoomScale > 1.05f) 1f else 1.75f
                                        }
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Unable to open PDF renderer for this file.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                // Bottom Page Navigation Bar
                Surface(
                    color = Color(0xFF111A2E),
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!isTextMode && pageCount > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        val prev = (activeVisiblePage - 1).coerceAtLeast(0)
                                        coroutineScope.launch {
                                            pageListState.animateScrollToItem(prev)
                                        }
                                    },
                                    enabled = activeVisiblePage > 0,
                                    modifier = Modifier.testTag("pdf_prev_page_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.NavigateBefore,
                                        contentDescription = "Previous page"
                                    )
                                }

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Text(
                                        text = "Page ${activeVisiblePage + 1} of $pageCount",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                    Slider(
                                        value = activeVisiblePage.toFloat(),
                                        onValueChange = { target ->
                                            val idx = target.roundToInt().coerceIn(0, pageCount - 1)
                                            coroutineScope.launch {
                                                pageListState.scrollToItem(idx)
                                            }
                                        },
                                        valueRange = 0f..(pageCount - 1).coerceAtLeast(1).toFloat(),
                                        steps = (pageCount - 2).coerceIn(0, 50),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(24.dp)
                                    )
                                }

                                FilledTonalIconButton(
                                    onClick = {
                                        val next = (activeVisiblePage + 1).coerceAtMost(pageCount - 1)
                                        coroutineScope.launch {
                                            pageListState.animateScrollToItem(next)
                                        }
                                    },
                                    enabled = activeVisiblePage < pageCount - 1,
                                    modifier = Modifier.testTag("pdf_next_page_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.NavigateNext,
                                        contentDescription = "Next page"
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = file.fullPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.White.copy(alpha = 0.72f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "$pageCount ${if (pageCount == 1) "Page" else "Pages"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF67E8F9)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfRenderedPageCard(
    session: PdfRenderSession,
    pageIndex: Int,
    totalPageCount: Int,
    zoomScale: Float,
    onToggleZoom: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.coerceAtLeast(320)
    val targetRenderWidthPx = remember(zoomScale) {
        if (zoomScale > 1.2f) 1800 else 1280
    }

    val pageBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = session.sessionId,
        key2 = pageIndex,
        key3 = targetRenderWidthPx
    ) {
        value = session.renderPage(pageIndex, targetRenderWidthPx)
    }

    val horizontalScrollState = rememberScrollState()

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFF334155)),
        tonalElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(pageIndex) {
                detectTapGestures(onDoubleTap = { onToggleZoom() })
            }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Subtle Page Header Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF1F5F9))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Page ${pageIndex + 1} of $totalPageCount",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1E293B)
                )
                if (zoomScale > 1.05f) {
                    Text(
                        text = "Swipe horizontally to pan (${(zoomScale * 100).roundToInt()}%)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF0284C7)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFFE2E8F0))

            val bmp = pageBitmap
            if (bmp != null) {
                val aspect = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()).coerceIn(0.35f, 2.5f)
                val displayWidthDp = ((screenWidthDp - 26) * zoomScale).dp

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScrollState),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "PDF Page ${pageIndex + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .width(displayWidthDp)
                            .aspectRatio(aspect)
                            .let { mod ->
                                if (pageIndex == 0) mod.testTag("pdf_rendered_page_image") else mod
                            }
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFF0284C7),
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Rendering Page ${pageIndex + 1}…",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF475569)
                        )
                    }
                }
            }
        }
    }
}

private fun openPdfRenderSession(
    context: Context,
    file: PhoneFileItem
): PdfRenderSession? {
    // 1. Try opening real file via direct path, contentUri, or MediaStore query
    if (!file.isSampleFile) {
        val realPfd = openRealPdfFileDescriptor(context, file)
        if (realPfd != null) {
            try {
                val renderer = PdfRenderer(realPfd)
                if (renderer.pageCount > 0) {
                    return PdfRenderSession(
                        sessionId = "${file.id}_${file.modifiedAtMillis}",
                        pfd = realPfd,
                        renderer = renderer
                    )
                } else {
                    renderer.close()
                    realPfd.close()
                }
            } catch (_: Throwable) {
                try {
                    realPfd.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // 2. Fallback for built-in virtual sample files (or unreadable sample paths)
    val cacheDir = File(context.cacheDir, "in_app_pdf_reader_v2").apply { mkdirs() }
    val safeName = file.name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val generatedFile = File(cacheDir, "sample_$safeName")
    val samplePages = buildSamplePdfPagesText(file)

    if (!generatedFile.exists() || generatedFile.length() < 256L) {
        generateSamplePdfFile(generatedFile, file, samplePages)
    }

    return try {
        val pfd = ParcelFileDescriptor.open(generatedFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        PdfRenderSession(
            sessionId = "sample_${file.id}",
            pfd = pfd,
            renderer = renderer
        )
    } catch (_: Throwable) {
        null
    }
}

private fun openRealPdfFileDescriptor(
    context: Context,
    file: PhoneFileItem
): ParcelFileDescriptor? {
    // Strategy A: Direct java.io.File access (works when MANAGE_EXTERNAL_STORAGE is granted or in app dirs)
    try {
        val diskFile = File(file.fullPath)
        if (diskFile.exists() && diskFile.canRead() && diskFile.length() > 64L) {
            return ParcelFileDescriptor.open(diskFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    } catch (_: Throwable) {
    }

    // Strategy B: Stored content:// URI from MediaStore or SAF scan
    if (!file.contentUri.isNullOrBlank()) {
        try {
            val uri = Uri.parse(file.contentUri)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) return pfd
        } catch (_: Throwable) {
        }
    }

    // Strategy C: Query MediaStore by exact path or display name + size
    val resolvedUri = lookupMediaStoreUriForFile(context, file)
    if (resolvedUri != null) {
        try {
            val pfd = context.contentResolver.openFileDescriptor(resolvedUri, "r")
            if (pfd != null) return pfd
        } catch (_: Throwable) {
        }
    }

    return null
}

private fun lookupMediaStoreUriForFile(
    context: Context,
    file: PhoneFileItem
): Uri? {
    val contentResolver = context.contentResolver
    val candidateTables = mutableListOf<Uri>(MediaStore.Files.getContentUri("external"))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        candidateTables.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
    }

    val projection = arrayOf(MediaStore.Files.FileColumns._ID)

    for (tableUri in candidateTables) {
        // First try matching by DISPLAY_NAME
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

/**
 * Extracts readable, data-cleaned text pages from a real PDF on the user's phone by:
 * 1. Parsing embedded `/ToUnicode` CMap tables (`beginbfchar` / `beginbfrange`) across decompressed streams,
 * 2. Skipping non-text binary streams (`/Subtype /Image`, `/FontFile`, `/ICCBased`, `/Metadata`),
 * 3. Extracting and translating `BT..ET` text blocks, and
 * 4. Running the multi-stage garble/junk-symbol sanitizer (`cleanAndSanitizePdfPageText`).
 */
internal fun extractReadablePagesFromPdf(
    context: Context,
    file: PhoneFileItem,
    expectedPageCount: Int = 3
): List<String> {
    if (file.isSampleFile) {
        return buildSamplePdfPagesText(file)
    }

    val rawBytes = readRealPdfBytes(context, file, maxBytes = 12 * 1024 * 1024)
    if (rawBytes != null && rawBytes.size > 64) {
        val cleanedPages = parsePdfStreamsForText(rawBytes, expectedPageCount)
        if (cleanedPages.isNotEmpty()) {
            return cleanedPages
        }

        return listOf(
            "File: ${file.name}\nPath: ${file.fullPath}\nTotal Pages: $expectedPageCount\n\n" +
                "Note: This PDF contains scanned page images or non-text vector artwork with no extractable readable text layer.\n\n" +
                "Tap 'Page View' in the top bar to view and scroll through all $expectedPageCount rendered PDF pages directly."
        )
    }

    return buildSamplePdfPagesText(file)
}

/**
 * Multi-stage PDF text data-cleaning pipeline that eliminates junk symbols, unmapped CID font
 * artifacts, binary stream noise, Private Use Area glyph boxes, and garbled character runs while
 * preserving clean sentences, numbers, dates, tables, and structured bullet points.
 */
internal fun cleanAndSanitizePdfPageText(rawText: String): String {
    if (rawText.isBlank()) return ""

    // Stage 1: Expand typographic ligatures and normalize Unicode compatibility characters
    val ligatureExpanded = rawText
        .replace("\uFB00", "ff")
        .replace("\uFB01", "fi")
        .replace("\uFB02", "fl")
        .replace("\uFB03", "ffi")
        .replace("\uFB04", "ffl")
        .replace("\uFB05", "st")
        .replace("\uFB06", "st")
        .replace('\u00A0', ' ')
        .replace('\u2007', ' ')
        .replace('\u202F', ' ')
        .replace("\u2018", "'")
        .replace("\u2019", "'")
        .replace("\u201C", "\"")
        .replace("\u201D", "\"")
        .replace("\u2013", "-")
        .replace("\u2014", " - ")
        .replace("\u2212", "-")
        .replace("\u2022", "• ")
        .replace("\u25CF", "• ")
        .replace("\u25AA", "• ")
        .replace("\uF0B7", "• ")
        .replace("\uF0A7", "• ")

    val nfkc = try {
        Normalizer.normalize(ligatureExpanded, Normalizer.Form.NFKC)
    } catch (_: Throwable) {
        ligatureExpanded
    }

    // Stage 2: Strip non-printable control chars, replacement chars (U+FFFD), and Private Use Area font icons
    val charFiltered = StringBuilder(nfkc.length)
    for (ch in nfkc) {
        val code = ch.code
        when {
            ch == '\n' || ch == '\r' -> charFiltered.append('\n')
            ch == '\t' -> charFiltered.append(' ')
            // Skip ASCII & C1 control characters, soft hyphens, zero-width chars, and replacement char
            code in 0x00..0x1F || code in 0x7F..0x9F -> continue
            code == 0x00AD || code in 0x200B..0x200F || code == 0xFEFF || code == 0xFFFD -> continue
            // Skip Private Use Area (U+E000..U+F8FF) and Specials/Box Drawing junk
            code in 0xE000..0xF8FF || code in 0x2500..0x259F || code in 0xFFF0..0xFFFF -> continue
            else -> charFiltered.append(ch)
        }
    }

    // Stage 3: Line-by-line & token-by-token cleaning
    val rawLines = charFiltered.toString().lines()
    val cleanedLines = mutableListOf<String>()

    for (rawLine in rawLines) {
        val collapsedSpaces = rawLine.trim().replace(multiSpaceRegex, " ")
        if (collapsedSpaces.isBlank()) {
            if (cleanedLines.isNotEmpty() && cleanedLines.last().isNotEmpty()) {
                cleanedLines.add("")
            }
            continue
        }

        // Repair spaced-out kerning lines like "I N V O I C E   S U M M A R Y" -> "INVOICE SUMMARY"
        val deKernedLine = repairSpacedOutKerning(collapsedSpaces)

        // Filter individual tokens to strip symbol soup, PostScript operators, and garbled CID runs
        val cleanTokens = deKernedLine
            .split(' ')
            .mapNotNull { token -> sanitizePdfToken(token) }

        if (cleanTokens.isEmpty()) continue

        val candidateLine = cleanTokens.joinToString(" ").trim()
        if (isCleanReadableLine(candidateLine)) {
            cleanedLines.add(candidateLine)
        }
    }

    // Stage 4: Rejoin hyphenated line breaks (e.g. "docu-\nment" -> "document") and collapse blank lines
    val rejoined = StringBuilder()
    var i = 0
    while (i < cleanedLines.size) {
        val current = cleanedLines[i]
        if (current.isEmpty()) {
            if (rejoined.isNotEmpty() && !rejoined.endsWith("\n\n")) {
                rejoined.append("\n")
            }
            i++
            continue
        }

        if (current.endsWith("-") && current.length >= 4 && i + 1 < cleanedLines.size) {
            val nextLine = cleanedLines[i + 1]
            val firstNextChar = nextLine.firstOrNull()
            if (firstNextChar != null && firstNextChar.isLowerCase()) {
                rejoined.append(current.dropLast(1)).append(nextLine).append('\n')
                i += 2
                continue
            }
        }

        rejoined.append(current).append('\n')
        i++
    }

    return rejoined.toString()
        .replace(excessNewlinesRegex, "\n\n")
        .trim()
}

/**
 * Repairs lines where PDF kerning placed spaces between every single letter
 * (e.g., "P A T I E N T   R E P O R T" -> "PATIENT REPORT"), while never touching
 * pipe-delimited table rows or single-digit table values.
 */
private fun repairSpacedOutKerning(line: String): String {
    if (line.contains('|')) return line
    val tokens = line.split(' ')
    if (tokens.size < 4) return line
    val singleLetterTokens = tokens.count { it.length == 1 && it[0].isLetter() }
    if (singleLetterTokens.toFloat() / tokens.size.toFloat() < 0.75f) {
        return line
    }
    return buildString {
        for (token in tokens) {
            if (token.length == 1 && token[0].isLetter()) {
                append(token)
            } else {
                if (isNotEmpty() && last() != ' ') append(' ')
                append(token)
                append(' ')
            }
        }
    }.trim()
}

/**
 * Cleans a single whitespace-delimited token and returns `null` if the token is garbled noise,
 * a leaked PDF operator, or high-density symbol soup, while preserving lab units, serial/invoice IDs,
 * dates, prices, ranges, and table delimiters.
 */
private fun sanitizePdfToken(rawToken: String): String? {
    if (rawToken == "|" || rawToken == ":" || rawToken == "/" || rawToken == "-") return rawToken

    val trimmed = rawToken.trim { ch ->
        ch in "`~\\<>{}" || ch.code < 32
    }
    if (trimmed.isEmpty()) return null

    // Allow standard bullet, list, or table column markers
    if (trimmed == "•" || trimmed == "-" || trimmed == "–" || trimmed == "—") return "•"
    if (trimmed == "<" || trimmed == ">" || trimmed == "|") return trimmed

    // Reject leaked internal PDF/PostScript syntax keywords
    if (trimmed in pdfInternalOperatorRejectSet) return null
    if (pdfFontOrResourceTagRegex.matches(trimmed)) return null

    // Reject runs of repeated symbols (e.g. "____", "=====", "*****", "~~~~~", ".....")
    if (repeatedSymbolRunRegex.matches(trimmed)) return null

    // Reject tokens containing 2+ consecutive high-noise ASCII symbols (~ ` \ < > { } # * _)
    if (consecutiveJunkSymbolsRegex.containsMatchIn(trimmed)) return null

    val length = trimmed.length
    var letters = 0
    var digits = 0
    var standardPunct = 0
    var noisySymbols = 0

    for (ch in trimmed) {
        when {
            ch.isLetter() -> letters++
            ch.isDigit() -> digits++
            ch in ".,;:!?'\"()-/$%&•+@|<>^=[]" -> standardPunct++
            else -> noisySymbols++
        }
    }

    val alnum = letters + digits
    if (alnum == 0) return null

    // If token has any noisy symbol and is short, or noisy symbols exceed 20% of token, drop it
    if (noisySymbols > 0 && (length <= 4 || noisySymbols.toFloat() / length.toFloat() > 0.20f)) {
        return null
    }

    // For tokens of length >= 3, require at least 45% alphanumeric characters
    // (exempting clean numeric/currency/percentage/unit tokens with zero noisy symbols like "5.2%.", "mL/min/1.73m2", "$1.50,")
    val isCleanNumericOrUnitPunct = noisySymbols == 0 && (digits >= 1 || trimmed.contains('/'))
    if (!isCleanNumericOrUnitPunct && length >= 3 && alnum.toFloat() / length.toFloat() < 0.45f) {
        return null
    }

    // Detect garbled interleaved symbol/digit/case tokens typical of unmapped CID font bytes (e.g. "a3$B9!k" or "G#8p@2")
    if (length >= 4 && letters > 0 && digits > 0 && (noisySymbols > 0 || standardPunct >= 2)) {
        val isNormalDateOrCurrencyOrVersion = validStructuredAlnumPunctRegex.matches(trimmed)
        if (!isNormalDateOrCurrencyOrVersion) {
            return null
        }
    }

    // Detect unpronounceable consonant/symbol gibberish in long Latin tokens
    if (letters >= 8 && digits == 0 && !trimmed.contains('/') && !trimmed.contains('-')) {
        val vowelCount = trimmed.count { it.lowercaseChar() in "aeiouy" }
        val nonLatinCount = trimmed.count { it.code > 127 && it.isLetter() }
        if (vowelCount == 0 && nonLatinCount == 0) {
            return null
        }
    }

    // Reject excessively long unbroken non-URL strings (e.g. base64 or hex dumps)
    if (length > 64 && !trimmed.startsWith("http", ignoreCase = true) && !trimmed.contains('/') && !trimmed.contains('-')) {
        return null
    }

    return trimmed
}

/**
 * Evaluates whether a reconstructed line is clean, human-readable text rather than garbled stream noise,
 * while preserving table rows (` | `), key-value pairs (`Key: Value`), phone numbers, dates, and financial figures.
 */
private fun isCleanReadableLine(line: String): Boolean {
    if (line.isBlank()) return false

    var letters = 0
    var digits = 0
    var spaces = 0
    var cleanPunct = 0
    var otherChars = 0

    for (ch in line) {
        when {
            ch.isLetter() -> letters++
            ch.isDigit() -> digits++
            ch == ' ' -> spaces++
            ch in ".,;:!?'\"()-/$%&•+@|<>^=[]" -> cleanPunct++
            else -> otherChars++
        }
    }

    val alnum = letters + digits
    if (alnum == 0) return false

    // Allow short numeric page labels, list numbers, or short headers (e.g. "1", "12", "ID", "OK")
    if (line.length <= 4 && alnum >= 1 && otherChars == 0) return true

    if (alnum < 2) return false

    // Require at least 82% of the line to be letters, digits, spaces, or normal punctuation
    val cleanRatio = (alnum + spaces + cleanPunct).toFloat() / line.length.toFloat()
    if (cleanRatio < 0.82f) return false

    // Allow punctuation-rich table rows, dates, phone numbers, and units as long as noisy otherChars are low
    val totalSymbols = cleanPunct + otherChars
    val minAlnumToSymbolRatio = if (line.contains('|') || line.contains(':') || digits >= 2) 0.85f else 1.5f
    if (totalSymbols > 0 && alnum.toFloat() / totalSymbols.toFloat() < minAlnumToSymbolRatio) {
        return false
    }

    // If the line has 6+ Latin letters, ensure it has a realistic vowel ratio (>= 12%) so shifted-ASCII consonant garble is rejected
    val latinLetters = line.count { it in 'A'..'Z' || it in 'a'..'z' }
    if (latinLetters >= 6) {
        val vowels = line.count { it.lowercaseChar() in "aeiouy" }
        val vowelRatio = vowels.toFloat() / latinLetters.toFloat()
        if (vowelRatio < 0.12f || vowelRatio > 0.90f) {
            return false
        }
    }

    return true
}

private val multiSpaceRegex = Regex(" {2,}")
private val whitespaceRegex = Regex("\\s+")
private val excessNewlinesRegex = Regex("\n{3,}")
private val repeatedSymbolRunRegex = Regex("^[^a-zA-Z0-9]{3,}$|([_\\-=*~.#•])\\1{2,}")
private val consecutiveJunkSymbolsRegex = Regex("[~`\\\\<>{}#*_]{2,}")
private val pdfFontOrResourceTagRegex = Regex("^/[A-Za-z0-9_+\\-.]+$")
private val validStructuredAlnumPunctRegex = Regex(
    "^(\\$?[0-9]{1,3}(,[0-9]{3})*(\\.[0-9]+)?%?|v?[0-9]+(\\.[0-9]+)+|[0-9]{1,4}[/-][0-9]{1,2}[/-][0-9]{1,4}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|[A-Za-z0-9]+(-[A-Za-z0-9]+)+|[A-Za-z0-9.^+-]+(/[A-Za-z0-9.^+-]+)+|[0-9]+[A-Za-z]{1,6}|\\([0-9A-Za-z.,%/+-]+\\))[,.;:]?$"
)

private val pdfInternalOperatorRejectSet = setOf(
    "BT", "ET", "Tj", "TJ", "Tf", "Td", "TD", "Tm", "T*", "Tc", "Tw", "Tz", "TL", "Ts", "Tr",
    "cm", "re", "rg", "RG", "gs", "q", "Q", "W", "W*", "n", "f", "F", "f*", "B", "B*", "b", "b*",
    "stream", "endstream", "obj", "endobj", "xref", "trailer", "startxref",
    "FlateDecode", "Identity-H", "CIDInit", "ProcSet", "begincmap", "endcmap",
    "begincodespacerange", "endcodespacerange", "beginbfchar", "endbfchar", "beginbfrange", "endbfrange"
)

private fun readRealPdfBytes(
    context: Context,
    file: PhoneFileItem,
    maxBytes: Int
): ByteArray? {
    try {
        val diskFile = File(file.fullPath)
        if (diskFile.exists() && diskFile.canRead() && diskFile.length() in 64L..maxBytes.toLong()) {
            return diskFile.readBytes()
        }
    } catch (_: Throwable) {
    }

    val uri = file.contentUri?.let { Uri.parse(it) } ?: lookupMediaStoreUriForFile(context, file)
    if (uri != null) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    total += read
                    if (total >= maxBytes) break
                }
                if (out.size() > 64) return out.toByteArray()
            }
        } catch (_: Throwable) {
        }
    }
    return null
}

private val bfCharEntryRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
private val bfRangeEntryRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
private val pdfTextOperatorCheckRegex = Regex("\\b(Tj|TJ|Tf)\\b")

private fun parsePdfStreamsForText(
    pdfBytes: ByteArray,
    expectedPageCount: Int
): List<String> {
    val rawLatin = String(pdfBytes, StandardCharsets.ISO_8859_1)
    val decompressedCandidateStreams = mutableListOf<String>()
    val toUnicodeMap = mutableMapOf<Int, String>()

    var searchIdx = 0
    while (searchIdx < rawLatin.length && decompressedCandidateStreams.size < 160) {
        val streamKeywordIdx = rawLatin.indexOf("stream", searchIdx)
        if (streamKeywordIdx < 0) break

        if (streamKeywordIdx >= 3 && rawLatin.substring(streamKeywordIdx - 3, streamKeywordIdx) == "end") {
            searchIdx = streamKeywordIdx + 6
            continue
        }

        // Inspect the object dictionary immediately preceding `stream`
        val dictWindowStart = (streamKeywordIdx - 360).coerceAtLeast(0)
        val streamDictHeader = rawLatin.substring(dictWindowStart, streamKeywordIdx)
        if (
            streamDictHeader.contains("/Subtype /Image") ||
            streamDictHeader.contains("/Subtype/Image") ||
            streamDictHeader.contains("/FontFile") ||
            streamDictHeader.contains("/Length1") ||
            streamDictHeader.contains("/Metadata") ||
            streamDictHeader.contains("/ICCBased") ||
            streamDictHeader.contains("/Type /XRef") ||
            streamDictHeader.contains("/Type/XRef")
        ) {
            val skipEnd = rawLatin.indexOf("endstream", streamKeywordIdx + 6)
            searchIdx = if (skipEnd > 0) skipEnd + 9 else streamKeywordIdx + 6
            continue
        }

        var dataStart = streamKeywordIdx + 6
        if (dataStart < rawLatin.length && rawLatin[dataStart] == '\r') dataStart++
        if (dataStart < rawLatin.length && rawLatin[dataStart] == '\n') dataStart++

        val endStreamIdx = rawLatin.indexOf("endstream", dataStart)
        if (endStreamIdx < 0) break

        var dataEnd = endStreamIdx
        while (dataEnd > dataStart && (rawLatin[dataEnd - 1] == '\r' || rawLatin[dataEnd - 1] == '\n')) {
            dataEnd--
        }

        val streamLength = dataEnd - dataStart
        if (streamLength in 8..512_000) {
            val streamBytes = pdfBytes.copyOfRange(dataStart, dataEnd)
            val decodedString = tryInflatePdfStream(streamBytes)
                ?: String(streamBytes, StandardCharsets.ISO_8859_1)

            if (decodedString.contains("begincmap") &&
                (decodedString.contains("beginbfchar") || decodedString.contains("beginbfrange"))
            ) {
                parseToUnicodeCMapInto(decodedString, toUnicodeMap)
            } else if (
                decodedString.contains("BT") &&
                decodedString.contains("ET") &&
                pdfTextOperatorCheckRegex.containsMatchIn(decodedString)
            ) {
                decompressedCandidateStreams.add(decodedString)
            }
        }

        searchIdx = endStreamIdx + 9
    }

    if (decompressedCandidateStreams.isEmpty()) return emptyList()

    val extractedBlocks = mutableListOf<String>()
    for (decodedStream in decompressedCandidateStreams) {
        val rawStreamText = extractTextFromBtEtBlocks(decodedStream, toUnicodeMap)
        val cleanedStreamText = cleanAndSanitizePdfPageText(rawStreamText)
        if (cleanedStreamText.length >= 16) {
            extractedBlocks.add(cleanedStreamText)
        }
    }

    if (extractedBlocks.isEmpty()) return emptyList()

    return if (extractedBlocks.size <= expectedPageCount.coerceAtLeast(1)) {
        extractedBlocks
    } else {
        val perPage = (extractedBlocks.size.toFloat() / expectedPageCount.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
        extractedBlocks.chunked(perPage).map { it.joinToString("\n\n") }
    }
}

private fun parseToUnicodeCMapInto(cmapStream: String, targetMap: MutableMap<Int, String>) {
    // 1. Parse beginbfchar ... endbfchar
    var searchCursor = 0
    while (searchCursor < cmapStream.length && targetMap.size < 16_384) {
        val startIdx = cmapStream.indexOf("beginbfchar", searchCursor)
        if (startIdx < 0) break
        val endIdx = cmapStream.indexOf("endbfchar", startIdx + 11)
        if (endIdx < 0) break

        val section = cmapStream.substring(startIdx + 11, endIdx)
        for (match in bfCharEntryRegex.findAll(section)) {
            val srcCode = match.groupValues[1].toIntOrNull(16) ?: continue
            val dstUnicode = decodeHexToUnicodeString(match.groupValues[2])
            if (dstUnicode.isNotEmpty()) {
                targetMap[srcCode] = dstUnicode
            }
        }
        searchCursor = endIdx + 9
    }

    // 2. Parse beginbfrange ... endbfrange
    searchCursor = 0
    while (searchCursor < cmapStream.length && targetMap.size < 16_384) {
        val startIdx = cmapStream.indexOf("beginbfrange", searchCursor)
        if (startIdx < 0) break
        val endIdx = cmapStream.indexOf("endbfrange", startIdx + 12)
        if (endIdx < 0) break

        val section = cmapStream.substring(startIdx + 12, endIdx)
        for (match in bfRangeEntryRegex.findAll(section)) {
            val startCode = match.groupValues[1].toIntOrNull(16) ?: continue
            val endCode = match.groupValues[2].toIntOrNull(16) ?: continue
            val baseTarget = match.groupValues[3].toIntOrNull(16) ?: continue
            val span = (endCode - startCode).coerceIn(0, 512)
            for (offset in 0..span) {
                val targetCodePoint = baseTarget + offset
                if (targetCodePoint in 32..0xD7FF || targetCodePoint in 0xE000..0xFFFD) {
                    targetMap[startCode + offset] = targetCodePoint.toChar().toString()
                }
            }
        }
        searchCursor = endIdx + 10
    }
}

private fun decodeHexToUnicodeString(hex: String): String {
    if (hex.isEmpty()) return ""
    return try {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        if (clean.length % 4 == 0) {
            val bytes = ByteArray(clean.length / 2)
            for (i in bytes.indices) {
                bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            String(bytes, StandardCharsets.UTF_16BE)
        } else {
            val code = clean.toInt(16)
            if (code in 32..126 || code >= 160) code.toChar().toString() else ""
        }
    } catch (_: Throwable) {
        ""
    }
}

private fun tryInflatePdfStream(compressed: ByteArray): String? {
    for (nowrap in booleanArrayOf(false, true)) {
        val inflater = Inflater(nowrap)
        try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream(compressed.size * 2)
            val buffer = ByteArray(4096)
            var iterations = 0
            while (!inflater.finished() && iterations < 256) {
                iterations++
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break
                }
            }
            if (out.size() > 0) {
                return String(out.toByteArray(), StandardCharsets.ISO_8859_1)
            }
        } catch (_: Throwable) {
        } finally {
            inflater.end()
        }
    }
    return null
}

private fun extractTextFromBtEtBlocks(
    content: String,
    toUnicodeMap: Map<Int, String>
): String {
    val sb = StringBuilder()
    var cursor = 0
    while (cursor < content.length) {
        val bt = content.indexOf("BT", cursor)
        if (bt < 0) break
        val et = content.indexOf("ET", bt + 2)
        if (et < 0) break

        val block = content.substring(bt + 2, et)
        var i = 0
        while (i < block.length) {
            val ch = block[i]
            if (ch == '(') {
                var depth = 1
                i++
                val rawCodes = mutableListOf<Int>()
                val directChars = StringBuilder()
                while (i < block.length && depth > 0) {
                    val c = block[i]
                    if (c == '\\' && i + 1 < block.length) {
                        val next = block[i + 1]
                        when (next) {
                            'n' -> {
                                directChars.append('\n')
                                i += 2
                            }
                            'r' -> {
                                directChars.append('\r')
                                i += 2
                            }
                            't' -> {
                                directChars.append(' ')
                                i += 2
                            }
                            '(', ')', '\\' -> {
                                rawCodes.add(next.code)
                                directChars.append(next)
                                i += 2
                            }
                            in '0'..'7' -> {
                                val octalEnd = (i + 4).coerceAtMost(block.length)
                                var octStr = ""
                                var k = i + 1
                                while (k < octalEnd && block[k] in '0'..'7') {
                                    octStr += block[k]
                                    k++
                                }
                                val code = octStr.toIntOrNull(8)
                                if (code != null) {
                                    rawCodes.add(code)
                                    if (code in 32..126) directChars.append(code.toChar())
                                }
                                i = k
                            }
                            else -> {
                                rawCodes.add(next.code)
                                directChars.append(next)
                                i += 2
                            }
                        }
                    } else if (c == '(') {
                        depth++
                        directChars.append(c)
                        i++
                    } else if (c == ')') {
                        depth--
                        if (depth > 0) directChars.append(c)
                        i++
                    } else {
                        rawCodes.add(c.code and 0xFF)
                        if (c.code in 32..126 || c == '\n') {
                            directChars.append(c)
                        }
                        i++
                    }
                }
                val directStr = directChars.toString().trim()
                // If direct ASCII string looks garbled and a CMap mapping exists, try CMap translation
                val resolvedToken = if (
                    toUnicodeMap.isNotEmpty() &&
                    rawCodes.isNotEmpty() &&
                    directStr.count { it in "`~^|\\<>{}@#$%*_+=!" } > directStr.length / 3
                ) {
                    rawCodes.joinToString("") { code -> toUnicodeMap[code] ?: "" }.trim()
                } else {
                    directStr
                }

                if (resolvedToken.isNotEmpty()) {
                    sb.append(resolvedToken).append(' ')
                }
            } else if (ch == '<' && (i + 1 >= block.length || block[i + 1] != '<')) {
                val closeIdx = block.indexOf('>', i + 1)
                if (closeIdx > i + 1) {
                    val hex = block.substring(i + 1, closeIdx).replace(whitespaceRegex, "")
                    val decodedHex = decodePdfHexString(hex, toUnicodeMap)
                    if (decodedHex.isNotBlank()) {
                        sb.append(decodedHex).append(' ')
                    }
                    i = closeIdx + 1
                } else {
                    i++
                }
            } else if (ch == 'T' && i + 1 < block.length && (block[i + 1] == 'd' || block[i + 1] == 'D' || block[i + 1] == '*')) {
                sb.append('\n')
                i += 2
            } else {
                i++
            }
        }
        sb.append('\n')
        cursor = et + 2
    }

    return sb.toString()
}

private fun decodePdfHexString(
    hex: String,
    toUnicodeMap: Map<Int, String>
): String {
    if (hex.length < 2) return ""
    return try {
        val clean = if (hex.length % 2 == 0) hex else "${hex}0"

        // 1. Try 2-byte (4 hex char) CMap lookup first (standard for Identity-H CID fonts)
        if (toUnicodeMap.isNotEmpty() && clean.length % 4 == 0) {
            val mapped16 = StringBuilder()
            var hits = 0
            val totalGlyphs = clean.length / 4
            for (idx in 0 until totalGlyphs) {
                val code = clean.substring(idx * 4, idx * 4 + 4).toInt(16)
                val mapped = toUnicodeMap[code]
                if (mapped != null) {
                    hits++
                    mapped16.append(mapped)
                } else if (code in 32..126) {
                    mapped16.append(code.toChar())
                }
            }
            if (hits > 0 && hits * 2 >= totalGlyphs) {
                return mapped16.toString()
            }
        }

        // 2. Try 1-byte (2 hex char) CMap lookup
        if (toUnicodeMap.isNotEmpty()) {
            val mapped8 = StringBuilder()
            var hits = 0
            val totalGlyphs = clean.length / 2
            for (idx in 0 until totalGlyphs) {
                val code = clean.substring(idx * 2, idx * 2 + 2).toInt(16)
                val mapped = toUnicodeMap[code]
                if (mapped != null) {
                    hits++
                    mapped8.append(mapped)
                }
            }
            if (hits > 0 && hits * 2 >= totalGlyphs) {
                return mapped8.toString()
            }
        }

        val bytes = ByteArray(clean.length / 2)
        for (idx in bytes.indices) {
            bytes[idx] = clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        } else {
            buildString {
                for (b in bytes) {
                    val code = b.toInt() and 0xFF
                    if (code in 32..126) append(code.toChar())
                }
            }
        }
    } catch (_: Throwable) {
        ""
    }
}

private fun generateSamplePdfFile(
    targetFile: File,
    file: PhoneFileItem,
    pagesText: List<String>
) {
    val pdfDoc = PdfDocument()
    try {
        val pageWidth = 612
        val pageHeight = 792

        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(15, 23, 42)
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subHeaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(2, 132, 199)
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(30, 41, 59)
            textSize = 12f
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(203, 213, 225)
            strokeWidth = 1.2f
        }
        val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(241, 245, 249)
            style = Paint.Style.FILL
        }

        pagesText.forEachIndexed { index, content ->
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
            val page = pdfDoc.startPage(pageInfo)
            val canvas = page.canvas

            canvas.drawColor(AndroidColor.WHITE)

            canvas.drawRect(36f, 32f, (pageWidth - 36).toFloat(), 96f, boxFillPaint)
            canvas.drawText(file.name.take(46), 48f, 58f, headerPaint)
            canvas.drawText(
                "Path: ${file.fullPath.take(58)}  •  Size: ${file.formattedSize()}",
                48f,
                80f,
                subHeaderPaint
            )
            canvas.drawLine(36f, 108f, (pageWidth - 36).toFloat(), 108f, linePaint)

            var y = 136f
            val maxLineWidth = pageWidth - 96f
            for (paragraph in content.split("\n")) {
                if (paragraph.isBlank()) {
                    y += 12f
                    continue
                }
                val words = paragraph.split(" ")
                val lineBuilder = StringBuilder()
                for (word in words) {
                    val testLine = if (lineBuilder.isEmpty()) word else "$lineBuilder $word"
                    if (bodyPaint.measureText(testLine) > maxLineWidth) {
                        canvas.drawText(lineBuilder.toString(), 48f, y, bodyPaint)
                        y += 20f
                        lineBuilder.clear()
                        lineBuilder.append(word)
                    } else {
                        if (lineBuilder.isNotEmpty()) lineBuilder.append(" ")
                        lineBuilder.append(word)
                    }
                }
                if (lineBuilder.isNotEmpty()) {
                    canvas.drawText(lineBuilder.toString(), 48f, y, bodyPaint)
                    y += 22f
                }
            }

            canvas.drawLine(36f, (pageHeight - 54).toFloat(), (pageWidth - 36).toFloat(), (pageHeight - 54).toFloat(), linePaint)
            canvas.drawText(
                "File Radar In-App PDF Reader  •  Page ${index + 1} of ${pagesText.size}",
                48f,
                (pageHeight - 34).toFloat(),
                subHeaderPaint
            )

            pdfDoc.finishPage(page)
        }

        FileOutputStream(targetFile).use { out ->
            pdfDoc.writeTo(out)
        }
    } finally {
        pdfDoc.close()
    }
}

internal fun buildSamplePdfPagesText(file: PhoneFileItem): List<String> {
    val lower = file.name.lowercase(Locale.ROOT)
    val metaBlock = "DOCUMENT METADATA\n" +
        "• File Name: ${file.name}\n" +
        "• Storage Path: ${file.fullPath}\n" +
        "• Volume: ${file.volumeLabel} (${file.storageLocation.displayName})\n" +
        "• File Size: ${file.formattedSize()} (${file.formattedExactBytes()})\n" +
        "• Last Modified: ${file.formattedModifiedDate()}"

    return when {
        lower.contains("blood") || lower.contains("pathology") -> listOf(
            "$metaBlock\n\nPAGE 1 — CLINICAL PATHOLOGY & COMPLETE BLOOD COUNT (CBC)\n\n" +
                "Patient Diagnostic Panel — Verified Laboratory Report\n" +
                "1. Hemoglobin (Hb): 14.8 g/dL  [Reference: 13.2 - 16.6 g/dL]\n" +
                "2. Total Leukocyte Count (WBC): 6.2 x10^3 / uL  [Reference: 4.5 - 11.0]\n" +
                "3. Platelet Count: 245,000 / uL  [Reference: 150,000 - 410,000]\n" +
                "4. Hematocrit (PCV): 43.5%  [Reference: 38.3% - 48.6%]\n" +
                "5. RBC Count: 4.92 million/uL  [Reference: 4.35 - 5.65]",
            "PAGE 2 — LIPID PROFILE & METABOLIC PANEL\n\n" +
                "Biochemistry & Cardiovascular Risk Markers:\n" +
                "• Fasting Plasma Glucose: 89 mg/dL  [Optimal < 100 mg/dL]\n" +
                "• Glycosylated Hemoglobin (HbA1c): 5.2%  [Non-Diabetic Range: 4.0 - 5.6%]\n" +
                "• Total Cholesterol: 176 mg/dL  [Desirable < 200 mg/dL]\n" +
                "• HDL Cholesterol: 58 mg/dL  [Optimal > 40 mg/dL]\n" +
                "• LDL Cholesterol (Calculated): 98 mg/dL  [Optimal < 100 mg/dL]\n" +
                "• Triglycerides: 102 mg/dL  [Normal < 150 mg/dL]",
            "PAGE 3 — LIVER, THYROID & RENAL FUNCTION SUMMARY\n\n" +
                "Organ Function Biomarkers:\n" +
                "• Serum Creatinine: 0.92 mg/dL  [Reference: 0.70 - 1.30 mg/dL]\n" +
                "• eGFR (Glomerular Filtration Rate): 98 mL/min/1.73m2\n" +
                "• TSH (Thyroid Stimulating Hormone): 2.14 mIU/L  [Reference: 0.45 - 4.50]\n" +
                "• ALT (SGPT): 24 U/L  |  AST (SGOT): 21 U/L\n\n" +
                "Clinical Impression: All hematological, glycemic, lipid, renal, and hepatic parameters are within normal reference limits."
        )

        lower.contains("bank_statement") || lower.contains("financial_audit") -> listOf(
            "$metaBlock\n\nPAGE 1 — CONSOLIDATED BANK & FINANCIAL STATEMENT\n\n" +
                "Account Summary & Reconciliation:\n" +
                "• Opening Balance: $18,420.50\n" +
                "• Total Credits / Deposits: +$9,650.00\n" +
                "• Total Debits / Disbursements: -$3,412.88\n" +
                "• Ending Verified Balance: $24,657.62",
            "PAGE 2 — ITEMIZED TRANSACTION LEDGER\n\n" +
                " chronological Posting Log:\n" +
                "• Nov 02 — Direct Deposit: Payroll & Consulting Settlement (+$8,400.00)\n" +
                "• Nov 06 — ACH Transfer: Treasury Yield Distribution (+$1,250.00)\n" +
                "• Nov 11 — Cloud Infrastructure & GPU Cluster Invoice (-$642.88)\n" +
                "• Nov 14 — Workstation 4K Monitor Hardware Receipt (-$799.00)\n" +
                "• Nov 16 — Commercial Property Lease Settlement (-$1,971.00)",
            "PAGE 3 — AUDIT & COMPLIANCE SIGN-OFF\n\n" +
                "1. All electronic wire transfers and ACH clearing entries reconciled against ledger receipts.\n" +
                "2. Quarterly hardware depreciation schedules verified for tax reporting.\n" +
                "3. Zero discrepancies detected across primary checking and reserve accounts."
        )

        else -> listOf(
            "$metaBlock\n\nPAGE 1 — DOCUMENT OVERVIEW & TABLE OF CONTENTS\n\n" +
                "1. Executive Summary & Scope\n" +
                "2. Technical Specifications & Operational Parameters\n" +
                "3. Verification & Compliance Appendix\n\n" +
                "This multi-page document (${file.name}) is stored on ${file.storageLocation.displayName} (${file.volumeLabel}).",
            "PAGE 2 — TECHNICAL SPECIFICATIONS & KEY DETAILS\n\n" +
                "Detailed Record Contents:\n" +
                "• Full Phone Path: ${file.fullPath}\n" +
                "• Exact File Size: ${file.formattedSize()} (${file.formattedExactBytes()})\n" +
                "• Created Timestamp: ${file.formattedCreatedDate()}\n" +
                "• Modified Timestamp: ${file.formattedModifiedDate()}",
            "PAGE 3 — APPENDIX & VERIFICATION NOTES\n\n" +
                "• Scroll vertically in Page View to inspect all pages continuously.\n" +
                "• Use the zoom controls or double-tap any page to enlarge fine print.\n" +
                "• Use the bottom navigation bar to jump directly to any page."
        )
    }
}
