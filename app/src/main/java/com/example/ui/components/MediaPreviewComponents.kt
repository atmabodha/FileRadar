package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.model.FileCategory
import com.example.model.PhoneFileItem
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

object MediaThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024) {
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

suspend fun loadMediaBitmap(
    context: Context,
    file: PhoneFileItem,
    maxDimensionPx: Int
): Bitmap? = withContext(Dispatchers.IO) {
    val cacheKey = "${file.fullPath}_${maxDimensionPx}"
    MediaThumbnailCache.get(cacheKey)?.let { return@withContext it }

    val realFile = File(file.fullPath)
    var decoded: Bitmap? = null

    if (realFile.exists() && realFile.canRead() && realFile.length() > 256L) {
        decoded = when (file.category) {
            FileCategory.IMAGE -> decodeSampledBitmapFromFile(realFile, maxDimensionPx)
            FileCategory.VIDEO -> extractVideoFrame(realFile, maxDimensionPx)
            else -> null
        }
    }

    // Fallback procedural preview artwork for sample index items or unreadable formats (e.g. .dng raw)
    if (decoded == null) {
        decoded = generateSamplePreviewBitmap(file, maxDimensionPx.coerceAtMost(960))
    }

    if (decoded != null) {
        MediaThumbnailCache.put(cacheKey, decoded)
    }
    decoded
}

private fun decodeSampledBitmapFromFile(file: File, maxDimensionPx: Int): Bitmap? {
    return try {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
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
        BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    } catch (_: Throwable) {
        null
    }
}

private fun extractVideoFrame(file: File, maxDimensionPx: Int): Bitmap? {
    // 1. Try MediaMetadataRetriever first for accurate frame extraction
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                maxDimensionPx,
                maxDimensionPx
            ) ?: retriever.frameAtTime
        } else {
            retriever.frameAtTime
        }
        if (frame != null) return frame
    } catch (_: Throwable) {
        // Fallback to ThumbnailUtils below
    } finally {
        try {
            retriever.release()
        } catch (_: Throwable) {
            // Ignore
        }
    }

    // 2. Fallback to ThumbnailUtils
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(
                file,
                Size(maxDimensionPx, maxDimensionPx),
                null
            )
        } else {
            @Suppress("DEPRECATION")
            ThumbnailUtils.createVideoThumbnail(
                file.absolutePath,
                MediaStore.Video.Thumbnails.MINI_KIND
            )
        }
    } catch (_: Throwable) {
        null
    }
}

private fun generateSamplePreviewBitmap(file: PhoneFileItem, sizePx: Int): Bitmap {
    val width = sizePx.coerceIn(160, 960)
    val height = (width * 0.75f).toInt().coerceAtLeast(120)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val hash = abs(file.name.hashCode())
    val palettes = listOf(
        intArrayOf(0xFF0F172A.toInt(), 0xFF1E3A8A.toInt(), 0xFF06B6D4.toInt()),
        intArrayOf(0xFF1E1B4B.toInt(), 0xFF7C2D12.toInt(), 0xFFF59E0B.toInt()),
        intArrayOf(0xFF052E16.toInt(), 0xFF065F46.toInt(), 0xFF34D399.toInt()),
        intArrayOf(0xFF1F1235.toInt(), 0xFF581C87.toInt(), 0xFFEC4899.toInt()),
        intArrayOf(0xFF0C192C.toInt(), 0xFF0369A1.toInt(), 0xFF38BDF8.toInt())
    )
    val colors = palettes[hash % palettes.size]

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            colors[0],
            colors[1],
            Shader.TileMode.CLAMP
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

    // Draw glowing sun/orb and mountain/wave geometry
    val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colors[2]
        alpha = 185
    }
    val sunX = width * (0.3f + (hash % 40) / 100f)
    val sunY = height * 0.36f
    canvas.drawCircle(sunX, sunY, width * 0.14f, orbPaint)

    // Mountain / landscape silhouette
    val mountainPath = Path().apply {
        moveTo(0f, height.toFloat())
        lineTo(0f, height * 0.65f)
        lineTo(width * 0.28f, height * 0.42f)
        lineTo(width * 0.55f, height * 0.60f)
        lineTo(width * 0.80f, height * 0.38f)
        lineTo(width.toFloat(), height * 0.56f)
        lineTo(width.toFloat(), height.toFloat())
        close()
    }
    val mountainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA090D16.toInt()
    }
    canvas.drawPath(mountainPath, mountainPaint)

    // Subtle technical grid overlay for high-res view
    if (width >= 400) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xEEFFFFFF.toInt()
            textSize = width * 0.042f
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC94A3B8.toInt()
            textSize = width * 0.030f
        }
        val badgeRect = RectF(width * 0.05f, height * 0.74f, width * 0.95f, height * 0.93f)
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xBB0A101F.toInt()
        }
        canvas.drawRoundRect(badgeRect, 18f, 18f, badgePaint)
        canvas.drawText(
            file.name.take(34),
            width * 0.08f,
            height * 0.82f,
            textPaint
        )
        canvas.drawText(
            "${file.extension.uppercase(Locale.ROOT)} • ${file.formattedSize()}",
            width * 0.08f,
            height * 0.89f,
            subPaint
        )
    }

    return bitmap
}

@Composable
fun MediaThumbnailBox(
    file: PhoneFileItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val thumbnailBitmap by produceState<Bitmap?>(initialValue = null, key1 = file.fullPath) {
        value = loadMediaBitmap(context, file, maxDimensionPx = 180)
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("media_thumbnail_${file.name}"),
        contentAlignment = Alignment.Center
    ) {
        val bmp = thumbnailBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = if (file.category == FileCategory.VIDEO) {
                    Icons.Default.VideoFile
                } else {
                    Icons.Default.Image
                },
                contentDescription = file.category.displayName,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }

        // Play overlay icon on Video thumbnails
        if (file.category == FileCategory.VIDEO) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun FullScreenImageViewerDialog(
    file: PhoneFileItem,
    onCopyPath: (String) -> Unit,
    onShareFile: (PhoneFileItem) -> Unit,
    onOpenMetadata: (PhoneFileItem) -> Unit,
    onDismiss: () -> Unit,
    onExtractImageOcr: ((PhoneFileItem) -> Unit)? = null
) {
    val context = LocalContext.current
    val fullBitmap by produceState<Bitmap?>(initialValue = null, key1 = file.fullPath) {
        value = loadMediaBitmap(context, file, maxDimensionPx = 2048)
    }

    var scale by remember(file.fullPath) { mutableFloatStateOf(1f) }
    var offset by remember(file.fullPath) { mutableStateOf(Offset.Zero) }

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
                .testTag("fullscreen_image_viewer"),
            color = Color(0xFF060A12)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Interactive Pinch-to-Zoom & Double-Tap Image Canvas
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(file.fullPath) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        }
                        .pointerInput(file.fullPath) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = newScale
                                offset = if (newScale > 1f) {
                                    Offset(
                                        x = (offset.x + pan.x).coerceIn(-1200f, 1200f),
                                        y = (offset.y + pan.y).coerceIn(-1200f, 1200f)
                                    )
                                } else {
                                    Offset.Zero
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = fullBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = file.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 72.dp)
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    } else {
                        Text(
                            text = "Loading image…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White
                        )
                    }
                }

                // Top Bar: File Name, Size, Info, Share, Close
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                    color = Color(0xCC0A101F)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${file.formattedSize()} • ${file.storageLocation.displayName} • Pinch or double-tap to zoom",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (scale > 1.05f) {
                                IconButton(
                                    onClick = {
                                        scale = 1f
                                        offset = Offset.Zero
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ZoomOutMap,
                                        contentDescription = "Reset zoom",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (onExtractImageOcr != null) {
                                IconButton(
                                    onClick = { onExtractImageOcr(file) },
                                    modifier = Modifier.testTag("viewer_extract_image_ocr_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DocumentScanner,
                                        contentDescription = stringResource(R.string.scan_text_ocr_button),
                                        tint = Color(0xFF38BDF8)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { onOpenMetadata(file) },
                                modifier = Modifier.testTag("viewer_info_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "File Metadata",
                                    tint = Color.White
                                )
                            }

                            IconButton(
                                onClick = { onShareFile(file) },
                                modifier = Modifier.testTag("viewer_share_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = stringResource(R.string.share_file_action),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("close_media_viewer_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close viewer",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                // Bottom Bar: Full Path on Phone + Copy Path Button
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    color = Color(0xCC0A101F)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.label_full_path),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = file.fullPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { onCopyPath(file.fullPath) }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.copy_path_action),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FullScreenVideoPlayerDialog(
    file: PhoneFileItem,
    onCopyPath: (String) -> Unit,
    onShareFile: (PhoneFileItem) -> Unit,
    onOpenMetadata: (PhoneFileItem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val realFile = remember(file.fullPath) { File(file.fullPath) }
    val isRealPlayableVideo = remember(file.fullPath) {
        realFile.exists() && realFile.canRead() && realFile.length() > 1024L
    }

    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var durationMs by remember { mutableIntStateOf(45_000) }
    var videoLoadError by remember { mutableStateOf(false) }

    val posterBitmap by produceState<Bitmap?>(initialValue = null, key1 = file.fullPath) {
        value = loadMediaBitmap(context, file, maxDimensionPx = 1280)
    }

    // Poll playback progress while open
    LaunchedEffect(isPlaying, isRealPlayableVideo, videoLoadError) {
        while (isActive) {
            val vv = videoViewRef
            if (isRealPlayableVideo && !videoLoadError && vv != null) {
                try {
                    if (vv.isPlaying) {
                        currentPositionMs = vv.currentPosition
                        val dur = vv.duration
                        if (dur > 0) durationMs = dur
                    }
                    isPlaying = vv.isPlaying
                } catch (_: Throwable) {
                    // Ignore state transitions
                }
            } else if (isPlaying) {
                // Smooth preview progress for sample mirror entries in emulator
                currentPositionMs = (currentPositionMs + 250) % durationMs.coerceAtLeast(1000)
            }
            delay(250L)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                videoViewRef?.stopPlayback()
            } catch (_: Throwable) {
                // Ignore
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
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("fullscreen_video_player"),
            color = Color(0xFF060A12)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xDD0A101F)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp)
                        ) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${file.formattedSize()} • ${file.storageLocation.displayName} • In-App Video Player",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onOpenMetadata(file) },
                                modifier = Modifier.testTag("video_info_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "File Metadata",
                                    tint = Color.White
                                )
                            }

                            IconButton(
                                onClick = { onShareFile(file) },
                                modifier = Modifier.testTag("video_share_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = stringResource(R.string.share_file_action),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.testTag("close_video_player_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close video player",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                // Center Video Surface
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (isRealPlayableVideo && !videoLoadError) {
                        AndroidView(
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    videoViewRef = this
                                    val mediaController = MediaController(ctx)
                                    mediaController.setAnchorView(this)
                                    setMediaController(mediaController)
                                    setVideoURI(Uri.fromFile(realFile))
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = true
                                        durationMs = mp.duration.coerceAtLeast(1000)
                                        start()
                                        isPlaying = true
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        videoLoadError = true
                                        true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Fallback visual frame for sample preview stubs in browser emulator
                        val bmp = posterBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = file.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            )
                        }
                    }
                }

                // Bottom Transport Controls + Full Path Bar
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xEE0A101F),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Seekbar + Elapsed / Total Time
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = formatMillisToMinSec(currentPositionMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )

                            Slider(
                                value = currentPositionMs.toFloat(),
                                onValueChange = { newPos ->
                                    val target = newPos.toInt()
                                    currentPositionMs = target
                                    try {
                                        videoViewRef?.seekTo(target)
                                    } catch (_: Throwable) {
                                        // Ignore
                                    }
                                },
                                valueRange = 0f..durationMs.coerceAtLeast(1000).toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )

                            Text(
                                text = formatMillisToMinSec(durationMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Play / Pause / Skip Controls + Full Path
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(
                                    onClick = {
                                        val target = (currentPositionMs - 10_000).coerceAtLeast(0)
                                        currentPositionMs = target
                                        try {
                                            videoViewRef?.seekTo(target)
                                        } catch (_: Throwable) {
                                            // Ignore
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Replay10,
                                        contentDescription = "Rewind 10 seconds",
                                        tint = Color.White
                                    )
                                }

                                FilledIconButton(
                                    onClick = {
                                        val vv = videoViewRef
                                        if (isRealPlayableVideo && !videoLoadError && vv != null) {
                                            if (vv.isPlaying) {
                                                vv.pause()
                                                isPlaying = false
                                            } else {
                                                vv.start()
                                                isPlaying = true
                                            }
                                        } else {
                                            isPlaying = !isPlaying
                                        }
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    modifier = Modifier
                                        .size(44.dp)
                                        .testTag("video_play_pause_button")
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play"
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val target = (currentPositionMs + 10_000).coerceAtMost(durationMs)
                                        currentPositionMs = target
                                        try {
                                            videoViewRef?.seekTo(target)
                                        } catch (_: Throwable) {
                                            // Ignore
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Forward10,
                                        contentDescription = "Forward 10 seconds",
                                        tint = Color.White
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.End
                            ) {
                                Text(
                                    text = file.fullPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { onCopyPath(file.fullPath) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.copy_path_action),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatMillisToMinSec(millis: Int): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
