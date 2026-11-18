package com.example.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.example.model.FileCategory
import com.example.model.PhoneFileItem
import com.example.model.StorageLocation
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhoneFileScannerRepository(private val context: Context) {

    private data class SeedFileSpec(
        val virtualMountRoot: String,
        val relativePath: String,
        val storageLocation: StorageLocation,
        val volumeLabel: String,
        val logicalSizeBytes: Long,
        val createdDaysAgo: Int,
        val modifiedHoursAgo: Int,
        val mimeType: String
    )

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (_: Throwable) {
                false
            }
        } else {
            true
        }
    }

    fun persistFolderTreeAccess(treeUri: Uri) {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
        } catch (_: Throwable) {
            // Ignore if URI does not support persistable permission
        }
    }

    fun getPersistedFolderUris(): List<Uri> {
        return try {
            context.contentResolver.persistedUriPermissions
                .filter { it.isReadPermission }
                .map { it.uri }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun scanPhoneFiles(
        additionalTreeUris: List<Uri> = emptyList(),
        includeSampleIndex: Boolean = true
    ): List<PhoneFileItem> = withContext(Dispatchers.IO) {
        val discoveredByPath = LinkedHashMap<String, PhoneFileItem>()
        val fullAccessGranted = hasAllFilesAccess()

        // 1. Detect real storage volumes from StorageManager
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        var detectedSdVolumeUuid = "4F9A-18C2"
        var detectedSdVolumeName = "Removable SD Card (4F9A-18C2)"
        val mountedVolumeDirs = mutableListOf<Pair<File, Boolean>>()

        if (storageManager != null) {
            try {
                for (volume in storageManager.storageVolumes) {
                    val isRemovable = volume.isRemovable
                    if (isRemovable) {
                        val uuid = volume.uuid ?: "4F9A-18C2"
                        detectedSdVolumeUuid = uuid
                        val desc = volume.getDescription(context) ?: "SD Card"
                        detectedSdVolumeName = "$desc ($uuid)"
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory?.let { dir ->
                            if (dir.exists()) {
                                mountedVolumeDirs.add(dir to isRemovable)
                            }
                        }
                    } else if (isRemovable && volume.uuid != null) {
                        val sdDir = File("/storage/${volume.uuid}")
                        if (sdDir.exists()) {
                            mountedVolumeDirs.add(sdDir to true)
                        }
                    }
                }
            } catch (_: Throwable) {
                // Fallback to standard volume identifiers
            }
        }

        // Also ensure primary external storage root (/storage/emulated/0) is in mountedVolumeDirs
        try {
            val primaryRoot = Environment.getExternalStorageDirectory()
            if (primaryRoot != null && primaryRoot.exists() && mountedVolumeDirs.none { it.first.absolutePath == primaryRoot.absolutePath }) {
                mountedVolumeDirs.add(0, primaryRoot to false)
            }
        } catch (_: Throwable) {
            // Ignore
        }

        // 2. Walk readable directories across Internal Storage & SD Card (deduplicated & capped)
        val directFsItems = walkAccessibleDirectories(
            mountedVolumeDirs = mountedVolumeDirs,
            sdVolumeLabel = detectedSdVolumeName
        )
        for (item in directFsItems) {
            discoveredByPath.putIfAbsent(item.fullPath, item)
        }

        // 3. Query MediaStore (Files, Downloads, Images, Video, Audio) with safe row cap
        val mediaStoreItems = queryMediaStoreFiles(
            contentResolver = context.contentResolver,
            sdVolumeLabel = detectedSdVolumeName
        )
        for (item in mediaStoreItems) {
            val existing = discoveredByPath[item.fullPath]
            if (existing == null) {
                discoveredByPath[item.fullPath] = item
            } else if (existing.contentUri == null && item.contentUri != null) {
                discoveredByPath[item.fullPath] = existing.copy(contentUri = item.contentUri)
            }
        }

        // 4. Scan any user-authorized SAF Document Trees
        val allTreeUris = (getPersistedFolderUris() + additionalTreeUris).distinct()
        for (treeUri in allTreeUris) {
            val safItems = scanSafDocumentTree(
                treeUri = treeUri,
                sdVolumeLabel = detectedSdVolumeName
            )
            for (item in safItems) {
                val existing = discoveredByPath[item.fullPath]
                if (existing == null) {
                    discoveredByPath[item.fullPath] = item
                } else if (existing.contentUri == null && item.contentUri != null) {
                    discoveredByPath[item.fullPath] = existing.copy(contentUri = item.contentUri)
                }
            }
        }

        // 5. Include built-in sample index ONLY if requested OR if no real device files were found
        val shouldIncludeSamples = if (fullAccessGranted && discoveredByPath.isNotEmpty()) {
            includeSampleIndex
        } else {
            includeSampleIndex || discoveredByPath.isEmpty()
        }

        if (shouldIncludeSamples) {
            val seededItems = buildSampleStorageFiles(
                sdVolumeUuid = detectedSdVolumeUuid,
                sdVolumeLabel = detectedSdVolumeName
            )
            for (item in seededItems) {
                discoveredByPath.putIfAbsent(item.fullPath, item)
            }
        }

        discoveredByPath.values.toList()
    }

    private fun walkAccessibleDirectories(
        mountedVolumeDirs: List<Pair<File, Boolean>>,
        sdVolumeLabel: String
    ): List<PhoneFileItem> {
        val found = LinkedHashMap<String, PhoneFileItem>()
        val visitedDirs = HashSet<String>()
        val candidateRoots = mutableListOf<Pair<File, Boolean>>()

        // Prioritize standard user-facing public directories first so Downloads, Documents, DCIM, etc. are always scanned first
        val publicDirs = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_DCIM,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_MOVIES,
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_PODCASTS,
            Environment.DIRECTORY_RINGTONES,
            Environment.DIRECTORY_NOTIFICATIONS
        )
        for (dirType in publicDirs) {
            try {
                val pubDir = Environment.getExternalStoragePublicDirectory(dirType)
                if (pubDir != null && pubDir.exists()) {
                    candidateRoots.add(pubDir to false)
                }
            } catch (_: Throwable) {
                // Ignore
            }
        }

        // Then add full storage roots (/storage/emulated/0 and removable SD card roots)
        candidateRoots.addAll(mountedVolumeDirs)

        // Add app external dirs
        val externalDirs = context.getExternalFilesDirs(null).filterNotNull()
        externalDirs.forEachIndexed { index, rootDir ->
            val isRemovable = try {
                Environment.isExternalStorageRemovable(rootDir)
            } catch (_: Throwable) {
                index > 0
            }
            candidateRoots.add(rootDir to isRemovable)
        }

        val nowMs = System.currentTimeMillis()

        for ((rootDir, isRemovable) in candidateRoots) {
            if (found.size >= MAX_WALKED_FILES) break
            val storageLocation = if (isRemovable) StorageLocation.SD_CARD else StorageLocation.INTERNAL
            val volumeLabel = if (isRemovable) sdVolumeLabel else "Internal Shared Storage"

            try {
                rootDir.walkTopDown()
                    .onEnter { dir ->
                        if (found.size >= MAX_WALKED_FILES) return@onEnter false
                        val name = dir.name
                        if (name.startsWith(".")) return@onEnter false
                        val lowerName = name.lowercase(Locale.ROOT)
                        if (lowerName in SKIPPED_DIR_NAMES) return@onEnter false

                        val path = dir.absolutePath
                        if (path.endsWith("/Android/data") ||
                            path.endsWith("/Android/obb") ||
                            path.endsWith("/Android/media")
                        ) {
                            return@onEnter false
                        }
                        // Deduplicate directory traversal across overlapping candidateRoots
                        visitedDirs.add(path)
                    }
                    .maxDepth(8)
                    .forEach { file ->
                        if (found.size >= MAX_WALKED_FILES) return@forEach
                        if (!file.isFile || file.name.startsWith(".")) return@forEach
                        val fullPath = file.absolutePath
                        if (found.containsKey(fullPath)) return@forEach

                        val sizeBytes = file.length()
                        if (sizeBytes <= 0L) return@forEach

                        val ext = resolveExtension(file.name, null)
                        if (ext.isNotEmpty()) {
                            val modifiedMs = file.lastModified().takeIf { it > 0L } ?: nowMs
                            val mime = MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(ext.lowercase(Locale.ROOT))
                                ?: "application/octet-stream"

                            found[fullPath] = PhoneFileItem(
                                id = fullPath,
                                name = file.name,
                                extension = ext,
                                fullPath = fullPath,
                                sizeBytes = sizeBytes,
                                createdAtMillis = modifiedMs,
                                modifiedAtMillis = modifiedMs,
                                storageLocation = storageLocation,
                                volumeLabel = volumeLabel,
                                category = FileCategory.fromExtension(ext, mime),
                                mimeType = mime
                            )
                        }
                    }
            } catch (_: Throwable) {
                // Skip unreadable directory trees
            }
        }
        return found.values.toList()
    }

    private fun scanSafDocumentTree(
        treeUri: Uri,
        sdVolumeLabel: String
    ): List<PhoneFileItem> {
        val results = mutableListOf<PhoneFileItem>()
        val contentResolver = context.contentResolver
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Throwable) {
            return emptyList()
        }

        val queue = ArrayDeque<String>()
        val visitedDirs = HashSet<String>()
        queue.add(rootDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        while (queue.isNotEmpty() && visitedDirs.size < 400 && results.size < 1500) {
            val parentDocId = queue.removeFirst()
            if (!visitedDirs.add(parentDocId)) continue

            val childrenUri = try {
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            } catch (_: Throwable) {
                continue
            }

            try {
                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext() && results.size < 1500) {
                        val docId = if (idIdx >= 0) cursor.getString(idIdx) else null ?: continue
                        val displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null ?: continue
                        if (displayName.startsWith(".")) continue

                        val mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.addLast(docId)
                            continue
                        }

                        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                        val lastMod = if (modIdx >= 0 && !cursor.isNull(modIdx)) {
                            cursor.getLong(modIdx)
                        } else {
                            System.currentTimeMillis()
                        }

                        val (fullPath, storageLoc, volumeLabel) = resolveSafDocPath(
                            docId = docId,
                            displayName = displayName,
                            sdVolumeLabel = sdVolumeLabel
                        )

                        val ext = resolveExtension(displayName, mimeType)
                        val resolvedMime = mimeType ?: MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(ext.lowercase(Locale.ROOT))
                            ?: "application/octet-stream"

                        val docUriStr = try {
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)?.toString()
                        } catch (_: Throwable) {
                            null
                        }

                        results.add(
                            PhoneFileItem(
                                id = fullPath,
                                name = displayName,
                                extension = ext.ifEmpty { "file" },
                                fullPath = fullPath,
                                sizeBytes = size.coerceAtLeast(1L),
                                createdAtMillis = lastMod,
                                modifiedAtMillis = lastMod,
                                storageLocation = storageLoc,
                                volumeLabel = volumeLabel,
                                category = FileCategory.fromExtension(ext, resolvedMime),
                                mimeType = resolvedMime,
                                contentUri = docUriStr,
                                isSampleFile = false
                            )
                        )
                    }
                }
            } catch (_: Throwable) {
                // Continue scanning remaining folders
            }
        }

        return results
    }

    private fun resolveSafDocPath(
        docId: String,
        displayName: String,
        sdVolumeLabel: String
    ): Triple<String, StorageLocation, String> {
        return when {
            docId.startsWith("raw:") -> {
                val rawPath = docId.removePrefix("raw:")
                val isInternal = rawPath.startsWith("/storage/emulated") || rawPath.startsWith("/sdcard")
                Triple(
                    rawPath,
                    if (isInternal) StorageLocation.INTERNAL else StorageLocation.SD_CARD,
                    if (isInternal) "Internal Shared Storage" else sdVolumeLabel
                )
            }
            docId.startsWith("primary:") -> {
                val rel = docId.removePrefix("primary:").trimStart('/')
                val full = if (rel.isNotEmpty()) "/storage/emulated/0/$rel" else "/storage/emulated/0/$displayName"
                Triple(full, StorageLocation.INTERNAL, "Internal Shared Storage")
            }
            docId.startsWith("home:") -> {
                val rel = docId.removePrefix("home:").trimStart('/')
                val full = if (rel.isNotEmpty()) "/storage/emulated/0/Documents/$rel" else "/storage/emulated/0/Documents/$displayName"
                Triple(full, StorageLocation.INTERNAL, "Internal Shared Storage")
            }
            docId.contains(":") -> {
                val parts = docId.split(":", limit = 2)
                val prefix = parts[0]
                val rel = parts.getOrElse(1) { "" }.trimStart('/')
                if (prefix.equals("msf", ignoreCase = true) || prefix.equals("downloads", ignoreCase = true)) {
                    Triple(
                        "/storage/emulated/0/Download/$displayName",
                        StorageLocation.INTERNAL,
                        "Internal Shared Storage"
                    )
                } else {
                    val full = if (rel.isNotEmpty()) "/storage/$prefix/$rel" else "/storage/$prefix/$displayName"
                    Triple(full, StorageLocation.SD_CARD, "SD Card ($prefix)")
                }
            }
            else -> {
                Triple(
                    "/storage/emulated/0/Download/$displayName",
                    StorageLocation.INTERNAL,
                    "Internal Shared Storage"
                )
            }
        }
    }

    private fun queryMediaStoreFiles(
        contentResolver: ContentResolver,
        sdVolumeLabel: String
    ): List<PhoneFileItem> {
        val items = LinkedHashMap<String, PhoneFileItem>()
        val volumeNames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                MediaStore.getExternalVolumeNames(context).ifEmpty { setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) }
            } catch (_: Throwable) {
                setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        } else {
            setOf("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATA,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Files.FileColumns.RELATIVE_PATH
            } else {
                MediaStore.Files.FileColumns.DATA
            }
        )

        for (volumeName in volumeNames) {
            val isPrimary = volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY || volumeName == "external"
            val storageLoc = if (isPrimary) StorageLocation.INTERNAL else StorageLocation.SD_CARD
            val mountPrefix = if (isPrimary) "/storage/emulated/0" else "/storage/${volumeName.uppercase(Locale.ROOT)}"
            val volumeLabel = if (isPrimary) "Internal Shared Storage" else sdVolumeLabel

            val urisToQuery = mutableListOf<Uri>()
            urisToQuery.add(MediaStore.Files.getContentUri(volumeName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                urisToQuery.add(MediaStore.Downloads.getContentUri(volumeName))
            }
            urisToQuery.add(MediaStore.Images.Media.getContentUri(volumeName))
            urisToQuery.add(MediaStore.Video.Media.getContentUri(volumeName))
            urisToQuery.add(MediaStore.Audio.Media.getContentUri(volumeName))

            for (uri in urisToQuery) {
                if (items.size >= MAX_MEDIASTORE_FILES) break
                try {
                    contentResolver.query(
                        uri,
                        projection,
                        null,
                        null,
                        "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                    )?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                        val nameIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                        val addedIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                        val modIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                        val mimeIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                        val dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                        val relIdx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                        } else -1

                        var rowCount = 0
                        while (cursor.moveToNext() && rowCount < 1200 && items.size < MAX_MEDIASTORE_FILES) {
                            rowCount++
                            val displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                            if (displayName.isNullOrBlank()) continue
                            val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                            if (size <= 0L) continue

                            val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                            val ext = resolveExtension(displayName, mime)
                            if (ext.isEmpty()) continue

                            val rowId = if (idIdx >= 0 && !cursor.isNull(idIdx)) cursor.getLong(idIdx) else -1L
                            val rowContentUri = if (rowId >= 0L) {
                                try {
                                    ContentUris.withAppendedId(uri, rowId).toString()
                                } catch (_: Throwable) {
                                    null
                                }
                            } else null

                            val addedSec = if (addedIdx >= 0) cursor.getLong(addedIdx) else 0L
                            val modSec = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                            val rawDataPath = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                            val relPath = if (relIdx >= 0) cursor.getString(relIdx) else null

                            val fullPath = when {
                                !rawDataPath.isNullOrBlank() && rawDataPath.startsWith("/") -> rawDataPath
                                !relPath.isNullOrBlank() -> "$mountPrefix/${relPath.trimStart('/')}$displayName"
                                else -> "$mountPrefix/Download/$displayName"
                            }

                            if (items.containsKey(fullPath)) continue

                            val resolvedMime = mime ?: MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(ext.lowercase(Locale.ROOT))
                                ?: "application/octet-stream"

                            val createdMs = if (addedSec > 0L) addedSec * 1000L else System.currentTimeMillis()
                            val modifiedMs = if (modSec > 0L) modSec * 1000L else createdMs

                            items[fullPath] = PhoneFileItem(
                                id = fullPath,
                                name = displayName,
                                extension = ext,
                                fullPath = fullPath,
                                sizeBytes = size,
                                createdAtMillis = createdMs,
                                modifiedAtMillis = modifiedMs,
                                storageLocation = storageLoc,
                                volumeLabel = volumeLabel,
                                category = FileCategory.fromExtension(ext, resolvedMime),
                                mimeType = resolvedMime,
                                contentUri = rowContentUri,
                                isSampleFile = false
                            )
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore inaccessible URIs
                }
            }
        }
        return items.values.toList()
    }

    private fun buildSampleStorageFiles(
        sdVolumeUuid: String,
        sdVolumeLabel: String
    ): List<PhoneFileItem> {
        val now = System.currentTimeMillis()
        val dayMs = 24L * 60L * 60L * 1000L
        val hourMs = 60L * 60L * 1000L

        val internalMount = "/storage/emulated/0"
        val sdMount = "/storage/$sdVolumeUuid"
        val internalLabel = "Internal Shared Storage"

        val specs = listOf(
            // ================= PDF DOCUMENTS (Internal Storage & SD Card) =================
            SeedFileSpec(internalMount, "Download/CBC_Lipid_Blood_Report_Nov2026.pdf", StorageLocation.INTERNAL, internalLabel, 684_032L, 3, 9, "application/pdf"),
            SeedFileSpec(internalMount, "Documents/Medical/Annual_Pathology_Blood_Test_Panel.pdf", StorageLocation.INTERNAL, internalLabel, 1_245_184L, 21, 140, "application/pdf"),
            SeedFileSpec(internalMount, "Download/Q4_Financial_Audit_2026.pdf", StorageLocation.INTERNAL, internalLabel, 4_852_190L, 14, 6, "application/pdf"),
            SeedFileSpec(internalMount, "Download/Monthly_Bank_Statement_Oct2026.pdf", StorageLocation.INTERNAL, internalLabel, 1_128_448L, 18, 40, "application/pdf"),
            SeedFileSpec(internalMount, "Download/Monthly_Bank_Statement_Sep2026.pdf", StorageLocation.INTERNAL, internalLabel, 1_094_656L, 48, 210, "application/pdf"),
            SeedFileSpec(internalMount, "Download/Health_Insurance_Policy_Summary.pdf", StorageLocation.INTERNAL, internalLabel, 3_412_992L, 32, 180, "application/pdf"),
            SeedFileSpec(internalMount, "Download/Flight_Itinerary_SFO_to_NRT.pdf", StorageLocation.INTERNAL, internalLabel, 428_032L, 4, 15, "application/pdf"),
            SeedFileSpec(internalMount, "Download/Apartment_Lease_Agreement_Signed.pdf", StorageLocation.INTERNAL, internalLabel, 8_912_384L, 120, 840, "application/pdf"),
            SeedFileSpec(internalMount, "Download/Solar_Inverter_Installation_Manual.pdf", StorageLocation.INTERNAL, internalLabel, 15_624_192L, 25, 110, "application/pdf"),
            SeedFileSpec(internalMount, "Documents/Invoices/Cloud_Infrastructure_Invoice_Nov2026.pdf", StorageLocation.INTERNAL, internalLabel, 642_880L, 5, 18, "application/pdf"),
            SeedFileSpec(internalMount, "Documents/Invoices/Domain_Renewal_Receipt_2026.pdf", StorageLocation.INTERNAL, internalLabel, 214_528L, 19, 95, "application/pdf"),
            SeedFileSpec(internalMount, "Documents/Invoices/Consulting_Services_Statement_Q3.pdf", StorageLocation.INTERNAL, internalLabel, 892_416L, 62, 420, "application/pdf"),
            SeedFileSpec(internalMount, "Documents/Tax/Federal_Tax_Return_Transcript_2025.pdf", StorageLocation.INTERNAL, internalLabel, 5_742_080L, 150, 960, "application/pdf"),
            SeedFileSpec(internalMount, "Documents/Tax/Property_Tax_Assessment_2026.pdf", StorageLocation.INTERNAL, internalLabel, 1_845_248L, 40, 260, "application/pdf"),
            SeedFileSpec(internalMount, "Documents/Manuals/Optical_Sensor_Calibration_Guide.pdf", StorageLocation.INTERNAL, internalLabel, 12_480_512L, 42, 120, "application/pdf"),
            SeedFileSpec(internalMount, "Documents/Manuals/Mechanical_Keyboard_PCB_Schematics.pdf", StorageLocation.INTERNAL, internalLabel, 7_318_528L, 55, 310, "application/pdf"),
            SeedFileSpec(internalMount, "Documents/Work/Q1_Engineering_OKR_Presentation.pdf", StorageLocation.INTERNAL, internalLabel, 9_456_640L, 8, 22, "application/pdf"),
            SeedFileSpec(internalMount, "WhatsApp/Media/WhatsApp Documents/Project_Proposal_Final_v4.pdf", StorageLocation.INTERNAL, internalLabel, 2_764_800L, 2, 7, "application/pdf"),

            // SD Card PDFs
            SeedFileSpec(sdMount, "Books/Clean_Architecture_Systems_Handbook.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 28_940_160L, 90, 480, "application/pdf"),
            SeedFileSpec(sdMount, "Books/Designing_Data_Intensive_Applications.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 36_412_416L, 140, 1100, "application/pdf"),
            SeedFileSpec(sdMount, "Books/Kotlin_Coroutines_Deep_Dive_2026.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 19_845_120L, 74, 520, "application/pdf"),
            SeedFileSpec(sdMount, "Books/Linux_Kernel_Internals_Reference.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 44_195_840L, 180, 1400, "application/pdf"),
            SeedFileSpec(sdMount, "Travel/Boarding_Pass_Tokyo_HND_2026.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 318_464L, 3, 12, "application/pdf"),
            SeedFileSpec(sdMount, "Travel/JR_Rail_Pass_Exchange_Order.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 512_000L, 6, 36, "application/pdf"),
            SeedFileSpec(sdMount, "Travel/Kyoto_Ryokan_Booking_Confirmation.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 784_384L, 11, 68, "application/pdf"),
            SeedFileSpec(sdMount, "ResearchPapers/Transformer_Attention_Optimization.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 4_194_304L, 28, 195, "application/pdf"),
            SeedFileSpec(sdMount, "ResearchPapers/Mobile_NPU_Quantization_Survey.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 6_815_744L, 34, 240, "application/pdf"),
            SeedFileSpec(sdMount, "ScannedDocs/Passport_And_Visa_Archive_Scan.pdf", StorageLocation.SD_CARD, sdVolumeLabel, 11_272_192L, 200, 1600, "application/pdf"),

            // ================= IMAGES & PHOTOS =================
            SeedFileSpec(internalMount, "DCIM/Camera/IMG_20261114_GoldenGate_Sunset.jpg", StorageLocation.INTERNAL, internalLabel, 6_412_890L, 4, 72, "image/jpeg"),
            SeedFileSpec(internalMount, "DCIM/Camera/IMG_20261116_Whiteboard_Architecture.jpg", StorageLocation.INTERNAL, internalLabel, 5_124_608L, 2, 28, "image/jpeg"),
            SeedFileSpec(internalMount, "Pictures/Screenshots/Screenshot_20261117_SystemMonitor.png", StorageLocation.INTERNAL, internalLabel, 1_924_096L, 1, 2, "image/png"),
            SeedFileSpec(internalMount, "Pictures/Screenshots/Screenshot_20261115_BatteryGraph.png", StorageLocation.INTERNAL, internalLabel, 1_412_096L, 3, 50, "image/png"),
            SeedFileSpec(internalMount, "Pictures/Design/Design_Tokens_Spec_v3.webp", StorageLocation.INTERNAL, internalLabel, 845_312L, 11, 48, "image/webp"),
            SeedFileSpec(sdMount, "DCIM/RAW_Captures/DSC_0842_MilkyWay_LongExposure.dng", StorageLocation.SD_CARD, sdVolumeLabel, 46_137_344L, 27, 240, "image/x-adobe-dng"),
            SeedFileSpec(sdMount, "DCIM/RAW_Captures/DSC_0849_Aurora_Borealis_ISO800.dng", StorageLocation.SD_CARD, sdVolumeLabel, 48_912_384L, 27, 238, "image/x-adobe-dng"),
            SeedFileSpec(sdMount, "Pictures/Panoramas/Nordic_Fjord_Panorama_8K.png", StorageLocation.SD_CARD, sdVolumeLabel, 19_784_192L, 64, 600, "image/png"),

            // ================= VIDEOS & CLIPS =================
            SeedFileSpec(internalMount, "DCIM/Camera/VID_20261116_Keynote_Rehearsal_1080p.mp4", StorageLocation.INTERNAL, internalLabel, 184_549_376L, 2, 20, "video/mp4"),
            SeedFileSpec(internalMount, "Movies/ScreenCaptures/UI_Latency_Benchmark_60fps.webm", StorageLocation.INTERNAL, internalLabel, 34_812_720L, 7, 36, "video/webm"),
            SeedFileSpec(sdMount, "DCIM/Drone_Footage/Alps_Glacier_Flyover_4K_HDR.mp4", StorageLocation.SD_CARD, sdVolumeLabel, 1_426_063_360L, 19, 160, "video/mp4"),
            SeedFileSpec(sdMount, "DCIM/Drone_Footage/Coastal_Cliffs_Sunset_60fps.mov", StorageLocation.SD_CARD, sdVolumeLabel, 912_680_550L, 21, 175, "video/quicktime"),
            SeedFileSpec(sdMount, "Movies/Archive/Deep_Ocean_ROV_Expedition.mkv", StorageLocation.SD_CARD, sdVolumeLabel, 892_338_176L, 110, 920, "video/x-matroska"),

            // ================= AUDIO & RECORDINGS =================
            SeedFileSpec(internalMount, "Recordings/VoiceNotes/Kernel_Architecture_Sync_Note.m4a", StorageLocation.INTERNAL, internalLabel, 8_912_896L, 6, 30, "audio/mp4"),
            SeedFileSpec(internalMount, "Music/Podcasts/Systems_Engineering_Weekly_Ep142.mp3", StorageLocation.INTERNAL, internalLabel, 54_525_952L, 9, 84, "audio/mpeg"),
            SeedFileSpec(sdMount, "Music/Lossless_FLAC/Symphony_No9_Studio_Master_24bit.flac", StorageLocation.SD_CARD, sdVolumeLabel, 148_897_792L, 75, 720, "audio/flac"),
            SeedFileSpec(sdMount, "Audio/FieldSamples/Rainforest_Thunder_Binaural_96kHz.wav", StorageLocation.SD_CARD, sdVolumeLabel, 96_468_992L, 38, 310, "audio/wav"),

            // ================= OFFICE & TEXT DOCS =================
            SeedFileSpec(internalMount, "Documents/Strategy/Platform_Architecture_Roadmap_2027.docx", StorageLocation.INTERNAL, internalLabel, 2_384_512L, 8, 14, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            SeedFileSpec(internalMount, "Documents/Finance/Hardware_Inventory_Ledger_Q4.xlsx", StorageLocation.INTERNAL, internalLabel, 1_548_288L, 16, 52, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            SeedFileSpec(internalMount, "Download/Release_Changelog_v4_2_0.md", StorageLocation.INTERNAL, internalLabel, 48_128L, 2, 4, "text/markdown"),
            SeedFileSpec(sdMount, "Datasets/Telemetry/Battery_Thermal_Sensor_Log_2026.csv", StorageLocation.SD_CARD, sdVolumeLabel, 15_728_640L, 22, 190, "text/csv"),

            // ================= COMPRESSED ARCHIVES =================
            SeedFileSpec(internalMount, "Download/Android_Platform_Tools_r36_Linux.zip", StorageLocation.INTERNAL, internalLabel, 16_842_752L, 12, 96, "application/zip"),
            SeedFileSpec(sdMount, "Backups/Archives/Workstation_Dotfiles_Snapshot_2026.tar.gz", StorageLocation.SD_CARD, sdVolumeLabel, 312_475_648L, 45, 400, "application/gzip"),
            SeedFileSpec(sdMount, "Backups/Projects/Embedded_Firmware_Source_v2.7z", StorageLocation.SD_CARD, sdVolumeLabel, 78_643_200L, 58, 510, "application/x-7z-compressed"),

            // ================= ANDROID PACKAGES (APK) =================
            SeedFileSpec(internalMount, "Download/Network_Packet_Analyzer_v2.4.1_release.apk", StorageLocation.INTERNAL, internalLabel, 24_117_248L, 10, 64, "application/vnd.android.package-archive"),
            SeedFileSpec(sdMount, "Apps/Offline_Topographic_Maps_v8.4.apk", StorageLocation.SD_CARD, sdVolumeLabel, 68_157_440L, 33, 280, "application/vnd.android.package-archive"),

            // ================= CODE & CONFIG DATA =================
            SeedFileSpec(internalMount, "Android/data/system_storage_indexer_trace.log", StorageLocation.INTERNAL, internalLabel, 286_720L, 1, 1, "text/plain"),
            SeedFileSpec(sdMount, "Configs/wireguard_mesh_routing_table.json", StorageLocation.SD_CARD, sdVolumeLabel, 18_432L, 29, 215, "application/json")
        )

        val results = ArrayList<PhoneFileItem>(specs.size)

        for (spec in specs) {
            val createdMillis = now - (spec.createdDaysAgo * dayMs)
            val modifiedMillis = now - (spec.modifiedHoursAgo * hourMs)
            val fileName = spec.relativePath.substringAfterLast('/')
            val ext = resolveExtension(fileName, spec.mimeType)
            val canonicalPhonePath = "${spec.virtualMountRoot}/${spec.relativePath}"

            results.add(
                PhoneFileItem(
                    id = canonicalPhonePath,
                    name = fileName,
                    extension = ext,
                    fullPath = canonicalPhonePath,
                    sizeBytes = spec.logicalSizeBytes,
                    createdAtMillis = createdMillis,
                    modifiedAtMillis = modifiedMillis,
                    storageLocation = spec.storageLocation,
                    volumeLabel = spec.volumeLabel,
                    category = FileCategory.fromExtension(ext, spec.mimeType),
                    mimeType = spec.mimeType,
                    contentUri = null,
                    isSampleFile = true
                )
            )
        }

        return results
    }

    private fun resolveExtension(fileName: String, mimeType: String?): String {
        val lower = fileName.lowercase(Locale.ROOT)
        if (lower.endsWith(".tar.gz")) return "gz"
        val dotIndex = fileName.lastIndexOf('.')
        if (dotIndex in 1 until fileName.length - 1) {
            return fileName.substring(dotIndex + 1).lowercase(Locale.ROOT)
        }
        if (mimeType != null) {
            val fromMime = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType.lowercase(Locale.ROOT))
            if (!fromMime.isNullOrBlank()) return fromMime.lowercase(Locale.ROOT)
            if (mimeType.equals("application/pdf", ignoreCase = true)) return "pdf"
        }
        return ""
    }

    companion object {
        private const val MAX_WALKED_FILES = 2500
        private const val MAX_MEDIASTORE_FILES = 2500

        private val SKIPPED_DIR_NAMES = setOf(
            "cache",
            "code_cache",
            "thumbnails",
            ".thumbnails",
            "node_modules",
            "temp",
            "tmp"
        )
    }
}
