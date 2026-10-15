# Rafolio for Android

Reader is being rebuilt as an offline, read-only document reader. It does not launch Office apps, convert Office files through a cloud service, or bundle a third-party Office rendering engine.

## Rafolio 0.8.0: your local reading shelf

Rafolio (رافوليو) is the new app identity. A custom vector page-fold R mark, coordinated light/dark themes and Arabic/English home screens bring search, file-type filters, favorites and reading history into one native interface. The package remains `com.r3dzf.reader`.

Opening a file keeps a private, read-only snapshot in app files storage, with durable SQLite metadata. Repeated identical imports of the same format reuse one entry. Reading position is restored for PDF pages, PPTX slides and DOCX scroll positions; XLSX restores the selected sheet (not its cell viewport). Favorites and positions survive activity recreation and cache eviction. The library is limited to 200 documents/1 GiB, with 250 MiB per file; removal/clear controls delete private copies and metadata. Uninstalling clears this app data. No broad storage permission or internet permission is required. See [library behavior](docs/LIBRARY_AND_DESIGN.md).

## Current engine milestone

- PDF files open inside Reader with Android's `PdfRenderer`.
- The native DOCX reader parses paragraphs, Arabic text direction, common run formatting, numbered lists, basic page settings, page breaks, simple tables, and embedded raster images using Kotlin code in this app. Images render beneath their paragraph or table cell text.
- The native XLSX reader parses worksheets, shared/inline strings and cached values, with native viewport rendering of horizontal, vertical and rectangular merges. Saved row heights, estimated column widths, hidden rows/columns, empty-row spacing, RGB fills/borders, basic font/paragraph styles and row/column style inheritance are supported. Sheets offer bounded 50%–500% pinch/button zoom, two-axis pan, pinned headers, direct cell-address navigation, accessibility descriptions and a full-cell reading dialog. Supported number formats include decimal rounding/grouping, percentages, literal currencies, scientific notation, positive/negative/zero sections and colors, dates/times and elapsed time. Both 1900 and 1904 date systems are respected. Saved values remain available and unsupported formatting receives a notice. See [XLSX compatibility](docs/XLSX_COMPATIBILITY.md).
- The native PPTX reader displays slides in presentation order with slide proportions, shape positions/fills, Arabic text, and embedded raster images decoded locally. Tables preserve column and row proportions, rectangular horizontal/vertical merges, cell margins, vertical alignment, direct/theme colors, mixed text styles, and per-edge solid/dashed/dotted borders. Right-to-left tables are positioned independently of the phone's interface language.
- PowerPoint text keeps each run's size, color, font name, bold/italic, underline and strike-through, with paragraph direction/alignment and point spacing. The reader resolves theme palettes, color-map/theme overrides, major/minor font references and basic tint/shade/luminance transforms. Placeholder geometry, basic text defaults and solid backgrounds inherit from layout/master parts. PowerPoint resolves names through a bundled catalog of 100 licensed Arabic/Latin font families, including documented substitutes for proprietary Office fonts; document-embedded fonts and exact Office metrics remain future work. See [font catalog](docs/FONTS.md). Master/layout decorations, nested groups with DrawingML transforms, rotation/reflection, common shape geometry/outlines, fill transparency/linear gradients, theme background references, picture crop, bullets/indents and text-box margins/vertical anchors are now supported.
- PowerPoint slides support pinch zoom up to 5×, panning while zoomed, double tap, zoom buttons, and a fit control. Embedded images are decoded at higher resolution when zooming within the existing bitmap memory cap. Common 2D charts render locally from saved caches with Arabic titles/labels, numeric scatter X values, stacks and a full-data reading dialog. Unsupported chart layouts receive a notice while available values remain readable. See [chart compatibility](docs/CHARTS_COMPATIBILITY.md).
- Template and macro-enabled OOXML variants are routed through the same readers. VBA/macros are never executed.
- The readers use Android's native text rendering and never modify the selected file.
- DOCX automatic pagination, headers, and footers remain future work. XLSX exact font-based column metrics, theme/conditional formats, advanced number formats, formula calculation, charts and print-page layout remain future work. PPTX table-style templates, arbitrary/custom shape geometry, advanced text fitting, combined/3D/newer chart rendering, animations, and transitions remain future work. Tables are limited to 100 rows and 64 columns each, and 25,000 cells per presentation. Legacy DOC/DOT, XLS/XLT/XLSB, and PPT/PPS/POT remain on the roadmap. The reader caps an individual embedded image at 10 MB and total images per document at 50 MB. See [ENGINE_ROADMAP.md](ENGINE_ROADMAP.md) and [PPTX compatibility](docs/PPTX_COMPATIBILITY.md).
- No network permission or Office viewer dependency is included.

These readers are foundations, not a claim of Office-perfect layout. Compatibility will be measured with Arabic and English test documents as those features are added.

## Build

Open the project in Android Studio and run the `app` configuration. With Python 3, JDK 17 and Android SDK 35 configured, run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The first build downloads hash-verified font assets from pinned upstream sources and bundles them into the APK. Later builds reuse the verified cache; reading on the phone needs no internet. Font files/licenses are generated by `tools/prepare_fonts.py` from `tools/fonts-manifest.json`, rather than copied into git.

The project includes the Gradle 8.11.1 wrapper with the official distribution SHA-256 pinned. On Windows use `gradlew.bat`.
The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Version 0.8.0 adds the Rafolio interface, persistent library and reading-position tests to the native engine regression suite. The completed CI results are recorded in the release pull request. Independent Arabic PPTX/XLSX fixtures cover styles, geometry, cell text direction, hidden/sparse axes, merge validation, viewports, saved values, extraction limits and input-file preservation. Android tests exercise merged Arabic text, accessibility/full-cell reading, zoom/reset, pan, RTL fill pixels and a workbook with a million-row address range. GitHub Actions runs JVM tests/lint/build, then an Android 35 emulator. Wider device/API compatibility and visual comparisons with Office remain future work.

## Project stack

- Kotlin and Jetpack Compose
- Android `PdfRenderer` for PDF
- An in-app OOXML package parser and native Compose document view, built in stages
- Android system text shaping for Arabic and other scripts
