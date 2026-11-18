package com.example.model

import java.text.DecimalFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class FileCategory(
    val displayName: String,
    val extensionsHint: String,
    val extensions: Set<String>
) {
    ALL(
        displayName = "All File Types",
        extensionsHint = "PDF, Image, Video, Audio, Docs, Archives & more",
        extensions = emptySet()
    ),
    PDF(
        displayName = "PDF Documents",
        extensionsHint = ".pdf",
        extensions = setOf("pdf")
    ),
    IMAGE(
        displayName = "Images & Photos",
        extensionsHint = ".jpg, .jpeg, .png, .webp, .heic, .gif, .svg",
        extensions = setOf("jpg", "jpeg", "png", "webp", "heic", "gif", "bmp", "svg", "raw", "dng")
    ),
    VIDEO(
        displayName = "Videos & Clips",
        extensionsHint = ".mp4, .mkv, .mov, .avi, .webm",
        extensions = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v")
    ),
    AUDIO(
        displayName = "Audio & Recordings",
        extensionsHint = ".mp3, .wav, .flac, .m4a, .aac, .ogg",
        extensions = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus")
    ),
    DOCUMENT(
        displayName = "Office & Text Docs",
        extensionsHint = ".docx, .xlsx, .pptx, .txt, .csv, .md",
        extensions = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "md", "rtf", "odt")
    ),
    ARCHIVE(
        displayName = "Compressed Archives",
        extensionsHint = ".zip, .rar, .7z, .tar, .gz",
        extensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    ),
    APK(
        displayName = "Android Packages (APK)",
        extensionsHint = ".apk, .aab",
        extensions = setOf("apk", "aab", "xapk")
    ),
    CODE_DATA(
        displayName = "Code & Config Data",
        extensionsHint = ".json, .xml, .sql, .yaml, .log",
        extensions = setOf("json", "xml", "html", "htm", "sql", "yaml", "yml", "log", "ini")
    );

    companion object {
        fun fromExtension(ext: String, mimeType: String? = null): FileCategory {
            val cleanExt = ext.lowercase(Locale.ROOT).trimStart('.')
            entries.forEach { category ->
                if (category != ALL && cleanExt in category.extensions) {
                    return category
                }
            }
            val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
            return when {
                mime == "application/pdf" -> PDF
                mime.startsWith("image/") -> IMAGE
                mime.startsWith("video/") -> VIDEO
                mime.startsWith("audio/") -> AUDIO
                mime.contains("zip") || mime.contains("compressed") || mime.contains("tar") -> ARCHIVE
                mime.contains("android.package-archive") -> APK
                mime.startsWith("text/") || mime.contains("word") || mime.contains("spreadsheet") || mime.contains("presentation") -> DOCUMENT
                else -> CODE_DATA
            }
        }
    }
}

enum class StorageLocation(
    val displayName: String,
    val mountPrefixHint: String
) {
    ALL("All Storage", "Internal + SD Card"),
    INTERNAL("Internal Storage", "/storage/emulated/0"),
    SD_CARD("SD Card", "/storage/SD-Card")
}

enum class SortField(
    val displayName: String,
    val shortLabel: String,
    val description: String
) {
    NAME("File Name", "Name", "Alphabetical order by file name"),
    SIZE("File Size", "Size", "Order by file size in bytes"),
    CREATED_DATE("Creation Date", "Created", "Order by date the file was created"),
    MODIFIED_DATE("Modification Date", "Modified", "Order by date the file was last modified"),
    EXTENSION("File Type / Extension", "Ext", "Order by file format extension (.pdf, .jpg, etc.)")
}

enum class SortOrder(val displayName: String, val symbol: String) {
    ASCENDING("Ascending", "↑"),
    DESCENDING("Descending", "↓");

    fun toggle(): SortOrder = if (this == ASCENDING) DESCENDING else ASCENDING
}

data class PhoneFileItem(
    val id: String,
    val name: String,
    val extension: String,
    val fullPath: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val modifiedAtMillis: Long,
    val storageLocation: StorageLocation,
    val volumeLabel: String,
    val category: FileCategory,
    val mimeType: String
) {
    fun formattedSize(): String = FileFormatters.formatFileSize(sizeBytes)
    fun formattedExactBytes(): String = FileFormatters.formatExactBytes(sizeBytes)
    fun formattedCreatedDate(): String = FileFormatters.formatDateTime(createdAtMillis)
    fun formattedModifiedDate(): String = FileFormatters.formatDateTime(modifiedAtMillis)
    fun formattedShortDate(millis: Long): String = FileFormatters.formatShortDate(millis)
}

object FileFormatters {
    private val sizeFormat = DecimalFormat("#,##0.##")

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> "${sizeFormat.format(bytes / gb)} GB"
            bytes >= mb -> "${sizeFormat.format(bytes / mb)} MB"
            bytes >= kb -> "${sizeFormat.format(bytes / kb)} KB"
            else -> "$bytes B"
        }
    }

    fun formatExactBytes(bytes: Long): String {
        val numberFormat = NumberFormat.getIntegerInstance(Locale.US)
        return "${numberFormat.format(bytes)} bytes"
    }

    fun formatDateTime(millis: Long): String {
        val formatter = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US)
        return formatter.format(Date(millis))
    }

    fun formatShortDate(millis: Long): String {
        val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        return formatter.format(Date(millis))
    }
}
