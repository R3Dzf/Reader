# Rafolio 0.8.0 — identity and local reading library

The user-facing name is **Rafolio / رافوليو**, inspired by a shelf (رف) and folio. This is a creative product name, not a trademark-registration claim. The Android package and repository stay `com.r3dzf.reader` / `R3Dzf/Reader`.

## Visual identity

A custom vector page-fold R mark appears on the home screen and adaptive launcher, with a separate Android monochrome icon. The editable source is `docs/brand/rafolio-logo.svg`. Forest green, soft mint, paper white and a small warm accent form the palette. Dark mode uses green charcoal surfaces with light mint accents. Manrope/Noto Sans Arabic come from the existing bundled licensed fonts. Document paper retains its file-defined colors.

The native Compose home offers Home / History / Favorites, search including normalized Arabic, file-type filters, a real continue-reading card and contextual favorite/removal actions. Empty, loading, missing-copy, filtered and storage-limit states have explicit messages. Appearance follows the device or a saved light/dark selection. The viewer uses one shared back/title toolbar.

## Storage and history

- Selecting or receiving a supported file reads it into app-private `files/library`, with serialized I/O and durable SQLite metadata; there is no provider write or broad storage permission.
- Each copy has an identifier derived from the original bytes and format. Reopening an identical import updates recency/name without duplicating the file, favorite or saved position. Changed source bytes create a separate snapshot. Already-saved copies do not refresh themselves from their sources.
- Private snapshots survive cache clearing and provider permission changes. They also use device storage: maximum 200 entries / 1 GiB total, 250 MiB per file. Unsuccessful imports remove temporary files. Since imports are serialized in one app process, copies from interrupted pending imports are collected before the next import.
- Remove/clear controls explain that they delete Rafolio copies, favorites and reading positions; original files are retained. Uninstalling or clearing app data removes the library. Backup is disabled in the manifest.
- Missing copies remain identifiable and report an actionable error. Progress writes cannot recreate a removed entry. Viewer intents are not exported and file paths must resolve inside the allowed private directory.
- PDF and PPTX restore the first visible page/slide plus scroll offset. DOCX restores its rendered list position, not a physical Word page number. XLSX restores the selected worksheet; zoom/cell viewport restoration remains future work. Positions are bounded to the current document length. PDF placeholders reserve page height before images load; bitmaps are capped at 2600 pixels per dimension and exceptionally tall displayed pages at 4000 dp. They are checkpointed after scrolling pauses and when leaving the activity.
- Macro/legacy format engine limits are unchanged; listing a file in history does not add a missing format implementation. Failed native parsing still reports the existing reader error.

## Verification

JVM tests cover Arabic search, combined filters, recency, file families, sanitized names and bounded progress. Android tests exercise SQLite recreation/deduplication, favorites/progress, cache eviction, unchanged sources, missing copies, oversized-import cleanup and viewer path protection. UI tests cover search/filter/favorite/removal, Arabic RTL, light/dark/empty/settings screens. Navigation tests open real PDF/PPTX copies through MainActivity and restore reading positions after returning/recreating. Actual Android screenshots are retained by CI for review; no document thumbnails or cloud previews are used.

Builds are development APKs. Production distribution still needs a securely retained release signing identity.
