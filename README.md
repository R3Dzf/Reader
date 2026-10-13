# Reader for Android

Reader is being rebuilt as an offline, read-only document reader. It does not launch Office apps, convert Office files through a cloud service, or bundle a third-party Office rendering engine.

## Current milestone

- PDF files open inside Reader with Android's `PdfRenderer`.
- The native DOCX reader parses paragraphs, Arabic text direction, common run formatting, numbered lists, basic page settings, page breaks, simple tables, and embedded raster images using Kotlin code in this app. Images render beneath their paragraph or table cell text.
- The native XLSX reader parses worksheets, shared and inline strings, cached cell values, right-to-left sheet direction, basic cell font/fill styles (bold, italic, size, text color, and solid fill), and horizontal merged cells. It displays a native scrollable grid.
- The native PPTX reader displays slides in presentation order with slide proportions, shape positions/fills, Arabic text, and embedded raster images decoded locally. Tables preserve column and row proportions, rectangular horizontal/vertical merges, cell margins, vertical alignment, direct/theme colors, mixed text styles, and per-edge solid/dashed/dotted borders. Right-to-left tables are positioned independently of the phone's interface language.
- PowerPoint text keeps each run's size, color, font name, bold/italic, underline and strike-through, with paragraph direction/alignment and point spacing. The reader resolves theme palettes, color-map/theme overrides, major/minor font references and basic tint/shade/luminance transforms. Placeholder geometry, basic text defaults and solid backgrounds inherit from layout/master parts. Font names use Android's available fonts and fallback; embedded fonts and exact Office font metrics remain future work.
- PowerPoint slides support pinch zoom up to 5×, panning while zoomed, double tap, zoom buttons, and a fit control. Embedded images are decoded at higher resolution when zooming within the existing bitmap memory cap. Unsupported graphic frames, such as charts, receive an element placeholder.
- Template and macro-enabled OOXML variants are routed through the same readers. VBA/macros are never executed.
- The readers use Android's native text rendering and never modify the selected file.
- DOCX automatic pagination, headers, and footers remain future work. XLSX vertical merged cells, number formats, borders, formula calculation, and rich drawing content are not implemented yet. PPTX table-style templates, master decorations, grouped/rotated shapes, chart rendering, animations, and transitions remain future work. Tables are limited to 100 rows and 64 columns each, and 25,000 cells per presentation. Legacy DOC/DOT, XLS/XLT/XLSB, and PPT/PPS/POT remain on the roadmap. The reader caps an individual embedded image at 10 MB and total images per document at 50 MB. See [ENGINE_ROADMAP.md](ENGINE_ROADMAP.md) and [PPTX compatibility](docs/PPTX_COMPATIBILITY.md).
- No network permission or Office viewer dependency is included.

These readers are foundations, not a claim of Office-perfect layout. Compatibility will be measured with Arabic and English test documents as those features are added.

## Build

Open the project in Android Studio and run the `app` configuration. With JDK 17 and Android SDK 35 configured, run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The project includes the Gradle 8.11.1 wrapper with the official distribution SHA-256 pinned. On Windows use `gradlew.bat`.
The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

Version 0.3.0 passes 32 JVM unit tests, including independent Arabic merged-table and themed rich-text PPTX fixtures, Unicode text direction, fixed-page font scaling, damaged merge/style rejection, text budgets, invalid coordinates, viewport limits, and input-file preservation. Android device rendering and gesture tests remain part of the compatibility roadmap.

## Project stack

- Kotlin and Jetpack Compose
- Android `PdfRenderer` for PDF
- An in-app OOXML package parser and native Compose document view, built in stages
- Android system text shaping for Arabic and other scripts
