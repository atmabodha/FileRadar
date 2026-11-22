# File Radar

**File Radar** (`com.aistudio.fileradar.vqxkpl`) is a privacy-first, strictly **read-only** Android file finder, **Smart Hybrid PDF Parser & Summarizer**, **Camera/Image OCR & Linguistic POS Genre Analyzer**, and **On-Device AI Semantic Indexer** built with **Kotlin** and **Jetpack Compose (Material 3)**.

It discovers files across **Internal Shared Storage** (`/storage/emulated/0`, `DCIM`, `Pictures`, `Download`, `Documents`, `Movies`, `Music`, and in-app `ocr_captures`) and **Removable SD Cards** without ever modifying or deleting user files, and provides built-in media viewers, an in-app PDF reader with a 6-part structured AI summarizer, dual-engine PDF chat (`Local Gemma E2B` + keyless `Gemini 3.8 Flash`), a live Camera/Gallery OCR & Linguistic Analyzer, one-tap file sharing, and a dedicated **On-Device AI Search** suite.

---

## Key Features

### 1. Standard File Finder (`Standard` Tab)
- **Full Phone & SD Card Discovery (Real Device Files & Camera Photos Prioritized)**:
  - Combines direct filesystem traversal (with canonical directory deduplication) and multi-volume `MediaStore` queries (`Images`, `Files`, `Downloads`, `Video`, `Audio`) to locate all files on the device.
  - Prioritizes `DCIM/Camera`, `Pictures`, `Screenshots`, and in-app `ocr_captures` first so newly captured or downloaded photos and documents always surface immediately ahead of sample/demo files.
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
  - **In-App Image Viewer**: Full-screen pinch-to-zoom (`1x`–`5x`), pan, double-tap zoom, and direct launch into the **Image OCR Scanner**.
  - **In-App Video Player**: Full-screen playback with play/pause, `-10s` / `+10s` seek, progress scrubber, and metadata access.
  - **File Metadata Inspector & In-App PDF Reader**:
    - Clicking a PDF file opens the **File Metadata Inspector** showing its full path, exact byte size, MIME type, storage volume, creation date, and modification date.
    - Clicking **Open** launches the **In-App PDF Reader**, featuring high-resolution native `PdfRenderer` page rendering, pinch-to-zoom, page scrubber navigation, a reflowable **Text View** mode with adjustable font sizing (`A-` / `A+`), a **1-Tap Force Visual OCR Toggle**, and an inline **AI Structured Document Summary** card.
- **One-Tap File Sharing & Path Copy**:
  - Share any file directly to WhatsApp, Email, Drive, or other apps via Android `FileProvider` (`content://` URIs), or copy its full phone path to the clipboard.

---

### 2. Smart Hybrid PDF Parser, 6-Part Summarizer & Dual-Engine PDF Chat

#### A. 3-Stage Smart Hybrid PDF Extraction Pipeline (`HybridPdfExtractor`)
1. **Stage 1 — Digital Text Layer (`PdfBox-Android`)**:
   - Extracts embedded PDF text per page via `PDFTextStripper` (`sortByPosition = true`, `spacingTolerance = 0.5f`, `averageCharTolerance = 0.3f`).
   - Evaluates lexical and structural coherence (`hasSufficientText` + `isDigitalTextLikelyIncompleteOrDegraded`) to detect hybrid scanned PDFs, broken subset `/ToUnicode` font mappings, or fragmented multi-column layouts.
2. **Stage 2 — Visual ML Kit OCR Fallback & Smart Hybrid Fusion**:
   - Whenever a page has insufficient or degraded digital text—or when the user taps **"Force Visual OCR" / "Visual OCR: ON"** in the PDF Reader or PDF Chat—renders the page to a clean white-backed `Bitmap` via Android's native `PdfRenderer` at **220 DPI** (clamped to `2480px`) and runs **Google ML Kit Text Recognition**.
   - **Spatial Table Row Reconstruction**: Clusters ML Kit `Text.Line` bounding boxes by vertical Y-center overlap into horizontal rows, sorts cells left-to-right, and inserts ` | ` column delimiters so multi-column tables (clinical lab panels, invoices, financial statements) remain intact row-by-row.
   - **Smart Hybrid Fusion (`selectOrFuseDigitalAndVisualOcrText`)**: Scores both the digital text and Visual OCR text and fuses unique visual OCR lines (such as text inside embedded image banners, stamps, or scanned tables) with the digital text layer (`Hybrid Fused (PDFBox + Visual OCR)`).
   - **Zero-OOM Memory Safety**: Recycles every page `Bitmap` immediately inside a `finally` block and optionally applies a non-destructive midpoint contrast curve (`translate = 128f * (1f - contrast)`).
3. **Stage 3 — Cross-Page Header/Footer Deduplication & Section-Aware Chunking**:
   - `TextNormalizer` strips running headers, footers, and watermarks repeated across `>= 40%` of pages, preserves pipe-delimited table rows (`|`), scientific/clinical units (`mL/min/1.73m2`, `x10^3/uL`), and hyphenated serial/invoice IDs (`INV-2026-04B`), detects section headings, and splits pages into sentence-aware `800`-character chunks with `100`-character overlap (`TextChunker`).

#### B. 6-Part Structured PDF Summarizer
Available both as an inline **AI Structured Document Summary** card at the top of **PDF Reader (Text View)** and in **PDF Chat** (`"Summarize this PDF"`):
1. **Automatic Document Category & Core Purpose**: Classifies the PDF into *Medical / Clinical Lab Report*, *Financial Invoice / Billing Statement*, *Technical / Academic Paper & Specification*, *Legal Agreement / Policy Document*, *Resume / Curriculum Vitae*, *Travel / Booking Itinerary*, or *General Document*, with word count and estimated reading time.
2. **Structured Fields & Identifiers**: Extracts `Key → Value` pairs and spatial table parameters with `[Page X]` citations.
3. **Key Highlights & Main Findings**: Extractive TextRank / centroid sentence selection with Maximal Marginal Relevance (MMR) trigram deduplication.
4. **Notable Numbers, Dates, Tables & Measurements**: Extracts currency totals, percentages, dates, and reference ranges.
5. **Action Items, Recommendations & Flagged Alerts**: Highlights deadlines, required follow-ups, conclusions, and elevated/abnormal values.
6. **Page-by-Page Breakdown & Linguistic Genre Profile**: Summarizes each page with its extraction provenance badge (`[Visual OCR]`, `[Hybrid Fused]`, or `Digital Text`) and computes the document's POS distribution, `RADJPRON`, `RADVADJ`, and **Probability of Fiction**.

#### C. Dual-Engine Conversational PDF Chat (`FullScreenPdfChatDialog`)
- **Engine 1 — `Local Gemma E2B` (100% Offline On-Device Hybrid RAG)**:
  - Uses **BM25 (`k1 = 1.5, b = 0.75`) + Character 3-Gram Cosine Similarity + Numeric/Section-Heading Boost + MMR Diversity (`λ = 0.72`)** and conversational coreference query expansion to answer initial and follow-up questions offline with `[Page X]` citations.
- **Engine 2 — `Gemini (Keyless Free Tier)`**:
  - Uses **Firebase AI Logic (`gemini-3.8-flash`)** authenticated transparently via Firebase App Check (no API key or paid billing required) with real-time streaming responses.

---

### 3. Camera & Image OCR Scanner + POS & Genre Classifier (`Scan Text (OCR)`)
Accessible from the **"Scan Text (OCR)"** button on both the **Standard** and **AI Search** tabs (or from any image card):
- **Camera Capture & Zero-Permission Gallery Upload**:
  - Snap a live photo of any printed/written text via `TakePicture` (using `CAMERA` runtime permission when needed) or pick an existing image via the zero-permission Android Photo Picker (`PickVisualMedia`).
- **On-Device ML Kit Vision OCR & Spatial Layout Reconstruction**:
  - Extracts text blocks, reconstructs multi-column tables (` | `), and offers an optional **High-Contrast Filter** toggle for faint receipts or low-light captures.
- **Interactive POS Tagging, Color-Matched Bar Graph & Word Highlighting**:
  - Computes total **Word Count** and classifies words into 5 major Part-of-Speech (POS) categories with a color-coded horizontal bar chart and synchronized word-level highlighting (`PosHighlightVisualTransformation`) inside a single clean text container:
    - **Nouns** (Yellow)
    - **Verbs** (Cyan)
    - **Adjectives** (Emerald)
    - **Adverbs** (Violet)
    - **Pronouns** (Rose)
  - Tap any POS bar or filter chip to isolate and highlight only that grammatical category in the extracted text.
- **Fiction vs. Non-Fiction Logistic Regression Predictor**:
  - Computes the two stylistic POS ratios from the extracted counts:
    - $\text{RADJPRON} = \frac{\text{Adjectives}}{\text{Pronouns}}$
    - $\text{RADVADJ} = \frac{\text{Adverbs}}{\text{Adjectives}}$
  - Evaluates the linear predictor and logistic probability:
    $$z = 1.7501 + (-4.3818 \times \text{RADJPRON}) + (2.0998 \times \text{RADVADJ})$$
    $$\text{Probability of Fiction} = \frac{1}{1 + e^{-z}}$$
  - Displays the step-by-step substitution, visual probability gauge (`0% Non-Fiction` ↔ `100% Fiction`), and final genre classification badge.

---

### 4. On-Device AI Search (`AI Search` Tab)
All AI discovery capabilities live in the dedicated **AI Search** tab while keeping the **Standard** screen fast and untouched:

1. **Direct Sync Button & Live Indexing Banner**:
   - Includes a dedicated **Sync** (`ai_rescan_button`) button and permission banner right inside the **AI Search** header so recent camera shots, screenshots, and downloads can be re-scanned and re-indexed with one tap.
2. **Semantic Search (Real Phone Images & Documents Prioritized)**:
   - Query files using natural language descriptions (e.g., *"recent phone images & photos"*, *"blood report hemoglobin"*, *"receipt for monitor"*, *"flight boarding pass to Tokyo"*).
   - Indexes up to 300 real device files on empty/browse queries (prioritizing real phone files and newest timestamps ahead of sample PDFs) and ranks queries via 64-dimensional vector cosine similarity + lexical & recency boosting.
3. **Dynamic Lenses (Smart Collections)**:
   - **Stage 1 (User Pre-Set Lenses)**: Matches files against user-defined lens names configured in Settings (such as `Blood Report`, `Bank Records`, `Tax & Invoices`, `Travel & Boarding Passes`).
   - **Stage 2 (Automatic AI Grouping)**: Automatically categorizes and names remaining files without duplicating files on disk.
4. **Content-Aware Auto-Renaming**:
   - Suggests structured `YYYY_MMM_Context_Topic.ext` filenames for generic camera/download names (`IMG_...`, `DSC_...`, `VID_...`, `Screenshot_...`) with non-destructive virtual preview toggles.
5. **Intelligent Junk Cleanup**:
   - Flags burst-shot near-duplicates, blurry captures, and superseded statement revisions while highlighting the sharpest/newest counterpart to retain.
6. **Natural Language File Operations (`NL Ops`)**:
   - Parses plain-English commands (e.g., *"Move PDFs older than 30 days to Documents/Old"*) into structured, non-destructive staging plans.
7. **On-Device Document Q&A (`Doc Q&A` / Local RAG)**:
   - Interactive local chat over any indexed document with chunk-level citations.
8. **Unified AI Filtering & Sorting**:
   - Filter AI results by any of the 9 file type categories and sort by **AI Relevance (Cosine)**, **File Name**, **File Size**, **Creation Date**, **Modification Date**, or **File Type / Extension**.

---

### 5. AI Control Center (`Settings` Tab)
- **Master AI Toggle (Kill Switch)**:
  - Immediately halts background indexing, clears the in-memory vector store, and degrades gracefully to lightweight filename search to conserve battery.
- **Custom Pre-Set Lens Labels**:
  - Add, remove, or reset custom grouping labels (e.g., `Blood Report`, `Bank Records`) persisted in `SharedPreferences`.
- **Hardware & Thermal Pacing Controls**:
  - Displays live device RAM and battery telemetry (`ActivityManager` & `BatteryManager`), `requiresBatteryNotLow` / `requiresCharging` constraints, thermal pacing delay (`250ms`–`1000ms`), chunk token window (`256` / `384` / `512` tokens), and model RAM flush controls.

---

## Phone System Requirements & Hardware Target

| Component | Minimum Requirement (Standard Mode) | Recommended Target (On-Device AI Mode) |
| :--- | :--- | :--- |
| **Android OS Version** | **Android 7.0 Nougat (`API 24`)** or higher | **Android 11 (`API 30`) – Android 16 (`API 36`)** |
| **Physical RAM** | **4 GB RAM** (with Master AI Kill Switch disabled or lightweight search) | **8 GB+ Physical LPDDR5/LPDDR4X RAM** (without relying on slower virtual RAM / RAM Boost) |
| **Processor (SoC)** | 64-bit ARM (`arm64-v8a`) multi-core CPU | Mid-range to flagship 64-bit SoC with dedicated GPU / NPU acceleration |
| **Free Internal Storage** | **~50 MB** for app installation, thumbnail cache & PDF render cache | **~1.5 GB – 2.0 GB** free internal storage (to accommodate the ~1.3 GB 4-bit quantized Gemma E2B `.bin` weights and vector index) |
| **Battery & Thermal State** | Any battery level | Battery **> 20%** (`requiresBatteryNotLow`) for incremental indexing; **Charging** (`requiresCharging`) recommended for initial backlog indexing |
| **External Storage Support** | Internal Shared Storage (`/storage/emulated/0`) | Internal Shared Storage + Removable microSD / USB OTG volumes (`StorageManager`) |

### Required & Optional Android Permissions
- **All Files Access (`MANAGE_EXTERNAL_STORAGE` on Android 11+ / API 30+)**:
  - Recommended so the scanner can discover non-media files (such as PDFs, Office documents, archives, APKs, and config files) across all folders in `/storage/emulated/0/` and removable SD cards.
- **Camera Permission (`android.permission.CAMERA`)**:
  - Requested at runtime only when the user taps **Take Photo** inside the **Scan Text (OCR)** dialog. Gallery image selection uses the zero-permission Android Photo Picker (`PickVisualMedia`).
- **Scoped Storage & `MediaStore` Fallback**:
  - Even before All Files Access is granted, File Radar queries `MediaStore` (`Images`, `Files`, `Downloads`, `Video`, `Audio`) and opens discovered files via `ContentResolver` `content://` URIs.

---

## Project Architecture

```text
app/src/main/java/com/example/
├── MainActivity.kt                         # Edge-to-edge Compose host activity & Firebase init
├── ai/
│   ├── OnDeviceAiModels.kt                 # AI state models, Sort fields, Lens & RAG data classes
│   └── OnDeviceAiEngine.kt                 # Sentence chunker, 64-dim embeddings, Pre-Set + Auto Lenses, RAG
├── data/
│   └── PhoneFileScannerRepository.kt       # Multi-volume storage walker & MediaStore scanner (DCIM/Pictures prioritized)
├── model/
│   └── FileModels.kt                       # PhoneFileItem, FileCategory, StorageLocation, SortField
├── pdfextractor/
│   ├── HybridPdfExtractor.kt               # 3-stage PDFBox + 220 DPI PdfRenderer + ML Kit Vision OCR + Smart Fusion
│   ├── TextNormalizer.kt                   # Cross-page header/footer stripper, section detector & TextChunker
│   └── model/
│       └── PdfExtractorModels.kt           # ExtractionConfig, ExtractedPage, TextChunk, DocumentExtractionResult
├── ui/
│   ├── FileFinderScreen.kt                 # Scaffold, Bottom Navigation (Standard | AI Search | Settings)
│   ├── AiSearchScreen.kt                   # Dedicated AI Search tab with Sync button, OCR button & 6 AI modes
│   ├── AiSettingsScreen.kt                 # Master AI Kill Switch, Pre-Set Lens labels & thermal settings
│   ├── components/
│   │   ├── FileControlHeader.kt            # Standard search bar, Scan Text (OCR) button, category & sort pills
│   │   ├── FileResultCard.kt               # File row card, actions & FileInspectorBottomSheet
│   │   ├── ImageOcrScannerDialog.kt        # Camera/Gallery ML Kit OCR, POS Bar Graph, Highlighting & Genre Predictor
│   │   ├── MediaPreviewComponents.kt       # LruCache thumbnails, Zoomable Image Viewer & Video Player
│   │   ├── PdfChatDialog.kt                # Dual-engine PDF Chat (Gemma E2B BM25+MMR RAG + Gemini) & 6-part Summarizer
│   │   └── PdfReaderDialog.kt              # Native PdfRenderer reader, Text View, Force Visual OCR & Summary Card
│   └── theme/
│       ├── Color.kt                        # High-contrast Deep Slate, Electric Cyan & 5 POS category palettes
│       ├── Theme.kt                        # Material 3 color scheme
│       └── Type.kt                         # Bundled Space Grotesk, Inter & JetBrains Mono typography
└── viewmodel/
    └── FileFinderViewModel.kt              # Off-main-thread scan & AI indexing state management
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
