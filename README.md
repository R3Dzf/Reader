<p align="center">
  <img src="docs/brand/rafolio-logo.svg" width="112" alt="Rafolio open book and document icon" />
</p>
<h1 align="center">Rafolio · رافوليو</h1>
<p align="center">Your space to read. مساحتك للقراءة.</p>
<p align="center">An offline document reader for Android, built for Arabic and English.</p>
<p align="center">
  <a href="https://github.com/R3Dzf/Reader/releases">Download for Android</a> ·
  <a href="docs/XLSX_COMPATIBILITY.md">Excel compatibility</a> ·
  <a href="docs/PPTX_COMPATIBILITY.md">PowerPoint compatibility</a> ·
  <a href="docs/FONTS.md">Fonts</a>
</p>

Rafolio brings documents, presentations, spreadsheets and PDFs into one native Android app. Open a file, read it comfortably, and return to it from your history. Documents stay on your device; reading works without an account or an internet connection.

## Made for reading

- **A personal document shelf.** Recent files, favorites, search and saved reading progress.
- **Arabic and English.** Right-to-left layouts, local text shaping and 100 bundled font families, with their licenses included.
- **Comfortable PDF reading.** Pinch to zoom, drag to pan, tap to hide controls, and switch between continuous scrolling and horizontal or vertical page-by-page views.
- **Clean presentations.** One slide at a time, landscape-friendly viewing, slide navigation and accurate reading progress.
- **A spreadsheet workspace.** Two-axis scrolling, zoom, row and column labels, merged cells, saved frozen panes, sheet tabs, cell search and A1 navigation. Fit columns to the screen or select a cell to read and copy its complete value and saved formula.
- **Your preferred appearance.** Light and dark app themes, Arabic and English interface options.
- **Smaller updates.** In-app updates can download a Delta patch when one is available for the installed version. Signed metadata, file hashes and the permanent release certificate protect the update path.

## Supported files

| Format | What Rafolio reads | Details |
| --- | --- | --- |
| PDF | Pages rendered locally with Android's PDF renderer | [Reading controls](docs/READING_EXPERIENCE.md) |
| Excel `.xlsx` | Saved cell values, number/date formats, theme colors, fonts, fills, borders, alignment, merges, dimensions, table styles and frozen panes | [Excel support and limits](docs/XLSX_COMPATIBILITY.md) |
| PowerPoint `.pptx` | Slides, text, shapes, pictures, tables and supported charts | [PowerPoint support](docs/PPTX_COMPATIBILITY.md), [charts](docs/CHARTS_COMPATIBILITY.md) |
| Word `.docx` | Text, paragraphs, lists, tables and supported images | Native reading layout; original pagination can differ |

The Office reader is written in Kotlin and renders inside the app. It does not require Microsoft Office, a conversion service or a second viewer. Reading is read-only: Rafolio preserves the original file and displays saved spreadsheet results without recalculating formulas or running macros.

Office compatibility is growing. Complex conditional formatting, spreadsheet drawings/charts, advanced text effects and some layout features are still outside the supported set. Legacy `.xls`, `.xlsb`, `.doc` and `.ppt` files and encrypted Office files are not currently supported. The linked compatibility documents describe the practical limits; visual identity with every Office document is not guaranteed.

## Install and start reading

1. Download the signed **`Rafolio-<version>.apk`** from [Releases](https://github.com/R3Dzf/Reader/releases). Android 8.0 or newer is required.
2. Open the APK and allow installation from your browser or file manager when Android asks.
3. Open Rafolio and choose a document from the system file picker. Previously opened documents appear in History.
4. For later versions, open **Settings → App updates**. Android asks for confirmation before installing an update.

A `.rfd` file is a Delta patch used by the updater; it is not an installable app. Reading works offline. Checking for and downloading updates requires internet access. Keep the existing app installed to retain its history and receive updates signed with the same certificate.

## بالعربي

رافوليو قارئ مستندات للموبايل: افتح ملفات PDF وWord وPowerPoint وExcel من داخل التطبيق، وارجع لملفاتك من السجل والمفضلة. القراءة تعمل بدون إنترنت، والخطوط موجودة داخل التطبيق.

في Excel تقدر تسحب في الاتجاهين، تكبّر بإصبعين، تبحث داخل الورقة، تنتقل لعنوان خلية، وتقرأ قيمتها والمعادلة المحفوظة كاملة. من قائمة الورقة تقدر تلائم عرض الأعمدة للشاشة أو تلغي تثبيت الأجزاء. التطبيق للقراءة، ولا يغيّر ملفك الأصلي.

للتثبيت حمّل ملف APK من صفحة الإصدارات. التحديثات التالية متاحة من **الإعدادات ← تحديث التطبيق**.

## Build and test

Use **JDK 17**, the **Android SDK Platform 35**, Python 3 and the checked-in Gradle wrapper. Accept the Android SDK licenses through the SDK manager first.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease
python3 -m unittest discover -s tools -p 'test_*.py'
```

The first build downloads the pinned font files and verifies their hashes. Later builds can use the local font cache. Fonts are packaged in the APK; the running reader does not download them.

To run native rendering and interaction checks on an Android emulator or connected device:

```bash
bash tools/run_device_tests.sh
```

The GitHub Actions workflow runs JVM tests, Android lint, packaging, update-tool tests and Android 35 instrumentation tests. Device tests capture reference images of the document viewers and app screens.

Release builds remain unsigned when no release keystore is configured. Never replace the permanent signing identity with a debug key. See [updates and signing](docs/UPDATES_AND_SIGNING.md) for the release process, verification and Delta format.

## Project guide

| Area | Location |
| --- | --- |
| Android app and native readers | `app/src/main/java/com/r3dzf/reader/` |
| Parser, layout and update unit tests | `app/src/test/` |
| Native interaction and rendering tests | `app/src/androidTest/` |
| Compatibility notes and design | `docs/` |
| Font preparation, release packaging and Delta tooling | `tools/` |
| Android build and emulator checks | `.github/workflows/android.yml` |

Found a rendering problem? [Open an issue](https://github.com/R3Dzf/Reader/issues) with the app version, Android version, file format and a screenshot. A small document that reproduces the problem helps us test the exact case; remove private information before sharing it.

Bundled font families retain their individual licenses. See [the font catalog](docs/FONTS.md) and the license files shipped with the application.
