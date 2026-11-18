# File Radar

**File Radar** (`com.aistudio.fileradar.vqxkpl`) is a privacy-first, strictly **read-only** Android file finder and on-device AI semantic indexer built with **Kotlin** and **Jetpack Compose (Material 3)**.

It discovers files across **Internal Shared Storage** (`/storage/emulated/0`, `Download`, `Documents`, `DCIM`, `Pictures`, `Movies`, `Music`) and **Removable SD Cards** without ever modifying or deleting user files, and provides built-in media viewers, an in-app PDF reader, one-tap file sharing, and a dedicated **On-Device AI Search** suite.

---

## Key Features

### 1. Standard File Finder (`Standard` Tab)
- **Full Phone & SD Card Discovery**:
  - Combines direct filesystem traversal (with directory deduplication) and multi-volume `MediaStore` queries (`Files`, `Downloads`, `Images`, `Video`, `Audio`) to locate all files on the device.
  - Supports Android 11+ **All Files Access** (`MANAGE_EXTERNAL_STORAGE`) so PDFs and documents inside `Download/`, `Documents/`, `WhatsApp/`, and SD card folders are indexed alongside media files.
- **File Type Dropdown Filter**:
  - Filter instantly across 9 categories with live file counts:
    - `All File Types`
    - `PDF Documents` (`.pdf`)
    - `Images & Photos` (`.jpg`, `.jpeg`, `.png`, `.webp`, `.heic`, `.gif`, `.svg`, `.raw`, `.dng`)
    - `Videos & Clips` (`.mp4`, `.mkv`, `.mov`, `.avi`, `.webm`)
    - `Audio & Recordings` (`.mp3`, `.wav`, `.flac`, `.m4a`, `.aac`, `.ogg`)
    - `Office & Text Docs` (`.docx`, `.xlsx`, `.pptx`, `.txt`, `.csv`, `.md`)
    - `Compressed Archives` (`.zip`, `.rar`, `.7z`, `.tar`, `.gz`)
    - `Android Packages (APK)` (`.apk`, `.aab`)
    - `Code & Config Data` (`.json`, `.xml`, `.sql`, `.yaml`, `.log`)
- **Storage Volume Filter**:
  - Switch between **All**, **Internal Storage**, and **SD Card** with per-volume item counts.
- **Multi-Field Sorting**:
  - Sort by **File Name**, **File Size**, **Creation Date**, **Modification Date**, or **File Type / Extension** in **Ascending** or **Descending** order.
- **Built-In Media & Document Viewers**:
  - **Image & Video Thumbnails**: Asynchronous, memory-cached (`LruCache`) thumbnails rendered directly in file rows.
  - **In-App Image Viewer**: Full-screen pinch-to-zoom (`1x`–`5x`), pan, and double-tap zoom.
  - **In-App Video Player**: Full-screen playback with play/pause, `-10s` / `+10s` seek, progress scrubber, and metadata access.
  - **File Metadata Inspector & In-App PDF Reader**:
    - Clicking a PDF file opens the **File Metadata Inspector** showing its full path, exact byte size, MIME type, storage volume, creation date, and modification date.
    - Clicking **Open** inside the inspector launches the **In-App PDF Reader**, featuring high-resolution native `PdfRenderer` page rendering, pinch-to-zoom, page scrubber navigation, and a reflowable **Text View** mode with adjustable font sizing (`A-` / `A+`).
- **One-Tap File Sharing & Path Copy**:
  - Share any file directly to WhatsApp, Email, Drive, or other apps via Android `FileProvider` (`content://` URIs), or copy its full phone path to the clipboard.

---

### 2. On-Device AI Search (`AI Search` Tab)
All AI capabilities live in the dedicated **AI Search** tab while keeping the **Standard** screen fast and untouched:

1. **Semantic Search**:
   - Query files using natural language descriptions (e.g., *"blood report hemoglobin"*, *"receipt for monitor"*, *"flight boarding pass to Tokyo"*).
   - Splits documents on sentence boundaries (`BreakIterator`) using a `~4 chars/token` heuristic with configurable token overlap, and ranks results via 64-dimensional vector cosine similarity + lexical boosting.
2. **Dynamic Lenses (Smart Collections)**:
   - **Stage 1 (User Pre-Set Lenses)**: Matches files against user-defined lens names configured in Settings (such as `Blood Report`, `Bank Records`, `Tax & Invoices`, `Travel & Boarding Passes`).
   - **Stage 2 (Automatic AI Grouping)**: Automatically categorizes and names any remaining files that do not fit into user pre-set labels without duplicating files on disk.
3. **Content-Aware Auto-Renaming**:
   - Suggests structured `YYYY_MMM_Context_Topic.ext` filenames for generic camera/download names (`IMG_...`, `DSC_...`, `VID_...`, `Screenshot_...`) with non-destructive virtual preview toggles.
4. **Intelligent Junk Cleanup**:
   - Flags burst-shot near-duplicates, blurry captures, and superseded statement revisions while highlighting the sharpest/newest counterpart to retain.
5. **Natural Language File Operations (`NL Ops`)**:
   - Parses plain-English commands (e.g., *"Move PDFs older than 30 days to Documents/Old"*) into structured, non-destructive staging plans.
6. **On-Device Document Q&A (`Doc Q&A` / Local RAG)**:
   - Interactive local chat over any indexed document with chunk-level citations.
7. **Unified AI Filtering & Sorting**:
   - Filter AI results by any of the 9 file type categories and sort by **AI Relevance (Cosine)**, **File Name**, **File Size**, **Creation Date**, **Modification Date**, or **File Type / Extension**.

---

### 3. AI Control Center (`Settings` Tab)
- **Master AI Toggle (Kill Switch)**:
  - Immediately halts background indexing, clears the in-memory vector store, and degrades gracefully to lightweight filename search to conserve battery.
- **Custom Pre-Set Lens Labels**:
  - Add, remove, or reset custom grouping labels (e.g., `Blood Report`, `Bank Records`) persisted in `SharedPreferences`.
- **Hardware & Thermal Pacing Controls**:
  - Displays live device RAM and battery telemetry (`ActivityManager` & `BatteryManager`), `requiresBatteryNotLow` / `requiresCharging` constraints, thermal pacing delay (`250ms`–`1000ms`), chunk token window (`256` / `384` / `512` tokens), and model RAM flush controls.

---

## Project Architecture

```text
app/src/main/java/com/example/
├── MainActivity.kt                         # Edge-to-edge Compose host activity
├── ai/
│   ├── OnDeviceAiModels.kt                 # AI state models, Sort fields, Lens & RAG data classes
│   └── OnDeviceAiEngine.kt                 # Sentence chunker, 64-dim embeddings, Pre-Set + Auto Lenses, RAG
├── data/
│   └── PhoneFileScannerRepository.kt       # Multi-volume storage walker & MediaStore scanner
├── model/
│   └── FileModels.kt                       # PhoneFileItem, FileCategory, StorageLocation, SortField
├── ui/
│   ├── FileFinderScreen.kt                 # Scaffold, Bottom Navigation (Standard | AI Search | Settings)
│   ├── AiSearchScreen.kt                   # Dedicated AI Search tab (6 AI modes + Category & Sort bars)
│   ├── AiSettingsScreen.kt                 # Master AI Kill Switch, Pre-Set Lens labels & thermal settings
│   ├── components/
│   │   ├── FileControlHeader.kt            # Standard search bar, category dropdown, storage & sort pills
│   │   ├── FileResultCard.kt               # File row card, actions & FileInspectorBottomSheet
│   │   ├── MediaPreviewComponents.kt       # LruCache thumbnails, Zoomable Image Viewer & Video Player
│   │   └── PdfReaderDialog.kt              # Native PdfRenderer in-app PDF reader + Text View mode
│   └── theme/
│       ├── Color.kt                        # High-contrast Deep Slate & Electric Cyan palette
│       ├── Theme.kt                        # Material 3 color scheme
│       └── Type.kt                         # Bundled Space Grotesk, Inter & JetBrains Mono typography
└── viewmodel/
    └── FileFinderViewModel.kt              # Off-main-thread scan & AI state management
```

---

## Building & Testing

- **Compile Debug APK**:
  ```bash
  gradle assembleDebug
  ```
- **Run Unit & Robolectric UI Tests**:
  ```bash
  gradle :app:testDebugUnitTest
  ```

---

## License

This project is licensed under the **GNU General Public License v3.0 or later (`GPL-3.0-or-later`)**. See the [LICENSE](LICENSE) file for details or visit [https://www.gnu.org/licenses/gpl-3.0.html](https://www.gnu.org/licenses/gpl-3.0.html).
