# Offline document font catalog — Reader 0.6.0

Exactly **100 distinct font families** are packaged in the APK: 30 supporting Arabic script and 70 Latin families. This is a curated practical collection; there is no universal verified ranking of the “100 most popular” Arabic/English fonts. A family is counted once regardless of its weights/styles.

The bundle contains 198 font files: regular, available italic/bold/bold-italic files and variable weights. Missing styles use Android synthesis. All original glyphs, shaping tables and licenses are retained; files are not subsetted or modified. Arabic shaping and bidirectional layout use Android's native text engine. Arabic missing-font fallback uses Noto Naskh Arabic; Latin fallback uses Carlito. A requested Latin-only family on an Arabic run receives the Arabic fallback.

Sources: [google/fonts](https://github.com/google/fonts/tree/67bc647ce1910e92f461bcc82aae30f21d21a9cc). Every file has a pinned SHA-256 and size in `tools/fonts-manifest.json`. `python3 tools/prepare_fonts.py` builds/verifies the APK asset folder; it stops on missing/corrupted assets. Font and license downloads happen only during the first build. The installed app does not request internet permission. CI caches verified sources and verifies the catalog on an Android emulator.

99 families use SIL OFL 1.1; Roboto Slab uses Apache 2.0 with its license/copyright notice. Each license is included alongside the font inside the APK. The source manifest records original repository paths. This distribution does not bundle proprietary Microsoft font binaries or fonts embedded in a user's presentation.

## Proprietary names and substitutes

Substitutions help preserve readability, but exact widths, line wrapping and appearance can differ. These are substitutes, not redistributed originals.

| Requested name | Bundled substitute |
| --- | --- |
| Arial / Helvetica | Arimo |
| Calibri / Calibri Light / Aptos | Carlito |
| Times New Roman | Gelasio |
| Cambria | Caladea |
| Courier New / Consolas | Cousine |
| Segoe UI | Open Sans |
| Tahoma / Verdana | Noto Sans |
| Traditional Arabic / Arabic Typesetting | Amiri |
| Simplified Arabic / Arabic Transparent / Sakkal Majalla | Noto Naskh Arabic |
| Andalus | Aref Ruqaa |

Arabic text requests for a Latin-only substitute use Noto Naskh Arabic. Name matching ignores spaces/punctuation/case, but does not invent arbitrary legacy font-name aliases.

## Packaged families

| # | Family | Script | License |
| --- | --- | --- | --- |
| 1 | Cairo | Arabic | OFL-1.1 |
| 2 | Tajawal | Arabic | OFL-1.1 |
| 3 | Amiri | Arabic | OFL-1.1 |
| 4 | Noto Naskh Arabic | Arabic | OFL-1.1 |
| 5 | Noto Sans Arabic | Arabic | OFL-1.1 |
| 6 | Noto Kufi Arabic | Arabic | OFL-1.1 |
| 7 | Almarai | Arabic | OFL-1.1 |
| 8 | Changa | Arabic | OFL-1.1 |
| 9 | El Messiri | Arabic | OFL-1.1 |
| 10 | Reem Kufi | Arabic | OFL-1.1 |
| 11 | Lateef | Arabic | OFL-1.1 |
| 12 | Scheherazade New | Arabic | OFL-1.1 |
| 13 | Harmattan | Arabic | OFL-1.1 |
| 14 | Mada | Arabic | OFL-1.1 |
| 15 | Mirza | Arabic | OFL-1.1 |
| 16 | Markazi Text | Arabic | OFL-1.1 |
| 17 | Lemonada | Arabic | OFL-1.1 |
| 18 | Aref Ruqaa | Arabic | OFL-1.1 |
| 19 | Katibeh | Arabic | OFL-1.1 |
| 20 | Lalezar | Arabic | OFL-1.1 |
| 21 | Rakkas | Arabic | OFL-1.1 |
| 22 | Marhey | Arabic | OFL-1.1 |
| 23 | Readex Pro | Arabic | OFL-1.1 |
| 24 | Alexandria | Arabic | OFL-1.1 |
| 25 | IBM Plex Sans Arabic | Arabic | OFL-1.1 |
| 26 | Fustat | Arabic | OFL-1.1 |
| 27 | Vazirmatn | Arabic | OFL-1.1 |
| 28 | Gulzar | Arabic | OFL-1.1 |
| 29 | Reem Kufi Fun | Arabic | OFL-1.1 |
| 30 | Ruwudu | Arabic | OFL-1.1 |
| 31 | Roboto | Latin | OFL-1.1 |
| 32 | Open Sans | Latin | OFL-1.1 |
| 33 | Roboto Condensed | Latin | OFL-1.1 |
| 34 | Merriweather | Latin | OFL-1.1 |
| 35 | Montserrat | Latin | OFL-1.1 |
| 36 | Lato | Latin | OFL-1.1 |
| 37 | Poppins | Latin | OFL-1.1 |
| 38 | Inter | Latin | OFL-1.1 |
| 39 | Raleway | Latin | OFL-1.1 |
| 40 | Nunito | Latin | OFL-1.1 |
| 41 | Nunito Sans | Latin | OFL-1.1 |
| 42 | Source Sans 3 | Latin | OFL-1.1 |
| 43 | Source Serif 4 | Latin | OFL-1.1 |
| 44 | Oswald | Latin | OFL-1.1 |
| 45 | Roboto Slab | Latin | Apache-2.0 |
| 46 | PT Sans | Latin | OFL-1.1 |
| 47 | PT Serif | Latin | OFL-1.1 |
| 48 | Lora | Latin | OFL-1.1 |
| 49 | Playfair Display | Latin | OFL-1.1 |
| 50 | Rubik | Latin | OFL-1.1 |
| 51 | Work Sans | Latin | OFL-1.1 |
| 52 | Fira Sans | Latin | OFL-1.1 |
| 53 | Fira Code | Latin | OFL-1.1 |
| 54 | Inconsolata | Latin | OFL-1.1 |
| 55 | JetBrains Mono | Latin | OFL-1.1 |
| 56 | IBM Plex Sans | Latin | OFL-1.1 |
| 57 | IBM Plex Serif | Latin | OFL-1.1 |
| 58 | IBM Plex Mono | Latin | OFL-1.1 |
| 59 | DM Sans | Latin | OFL-1.1 |
| 60 | DM Serif Display | Latin | OFL-1.1 |
| 61 | Manrope | Latin | OFL-1.1 |
| 62 | Outfit | Latin | OFL-1.1 |
| 63 | Barlow | Latin | OFL-1.1 |
| 64 | Barlow Condensed | Latin | OFL-1.1 |
| 65 | Cabin | Latin | OFL-1.1 |
| 66 | Karla | Latin | OFL-1.1 |
| 67 | Arimo | Latin | OFL-1.1 |
| 68 | Cousine | Latin | OFL-1.1 |
| 69 | Gelasio | Latin | OFL-1.1 |
| 70 | Carlito | Latin | OFL-1.1 |
| 71 | Caladea | Latin | OFL-1.1 |
| 72 | Noto Sans | Latin | OFL-1.1 |
| 73 | Noto Serif | Latin | OFL-1.1 |
| 74 | Noto Sans Mono | Latin | OFL-1.1 |
| 75 | Libre Baskerville | Latin | OFL-1.1 |
| 76 | Libre Franklin | Latin | OFL-1.1 |
| 77 | Crimson Text | Latin | OFL-1.1 |
| 78 | Crimson Pro | Latin | OFL-1.1 |
| 79 | EB Garamond | Latin | OFL-1.1 |
| 80 | Quicksand | Latin | OFL-1.1 |
| 81 | Josefin Sans | Latin | OFL-1.1 |
| 82 | Josefin Slab | Latin | OFL-1.1 |
| 83 | Bitter | Latin | OFL-1.1 |
| 84 | Arvo | Latin | OFL-1.1 |
| 85 | Archivo | Latin | OFL-1.1 |
| 86 | Archivo Narrow | Latin | OFL-1.1 |
| 87 | Exo 2 | Latin | OFL-1.1 |
| 88 | Exo | Latin | OFL-1.1 |
| 89 | Oxygen | Latin | OFL-1.1 |
| 90 | Asap | Latin | OFL-1.1 |
| 91 | Asap Condensed | Latin | OFL-1.1 |
| 92 | Comfortaa | Latin | OFL-1.1 |
| 93 | Anton | Latin | OFL-1.1 |
| 94 | Bebas Neue | Latin | OFL-1.1 |
| 95 | Titillium Web | Latin | OFL-1.1 |
| 96 | Yanone Kaffeesatz | Latin | OFL-1.1 |
| 97 | Abel | Latin | OFL-1.1 |
| 98 | Teko | Latin | OFL-1.1 |
| 99 | Signika | Latin | OFL-1.1 |
| 100 | Dancing Script | Latin | OFL-1.1 |
