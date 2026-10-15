# Native PowerPoint compatibility — 0.6.0

The Android app parses and renders the document locally. The parser does not write to the input file and does not execute macros. Support is tracked per feature; there is no Office-perfect fidelity claim.

| Feature | Current implementation | Remaining limits |
| --- | --- | --- |
| Slides | Order, proportions, physical coordinates, inherited placeholders; master/layout decorations with their own image relationships, grouped affine transforms, rotation and reflection | Custom geometry and complex drawing effects |
| Text | Per-run sizes/colors/font names, bold/italic, underline/strike-through, paragraph alignment/direction and point spacing; cached fields and breaks; character/number bullets, hanging indents, line spacing and body margins/vertical anchors | Advanced text fitting, vertical text, exact bullet tabs and document-embedded fonts |
| Theme | Direct/system palette colors, color-map and theme overrides, major/minor Latin/complex font references, ordered tint/shade/lumMod/lumOff transforms; style-matrix fills/bgRef, linear gradient stops and fill alpha | Pattern/path gradients, picture backgrounds and other transforms |
| Images | Embedded raster images, bounded decode, resolution increases at zoom thresholds, nonnegative source cropping and stretch | Vector images, outsets/negative cropping and image effects |
| Shapes | Rectangle, rounded rectangle, ellipse, triangle/right triangle, diamond and straight connector; direct outlines and affine transforms | Other preset/custom geometry, connector routing and exact effects |
| Tables | Original row/column proportions and RTL order | Theme table styles and diagonal borders |
| Merges | Horizontal, vertical and rectangular merges with one rendered origin cell | Invalid or inconsistent merge grids are rejected |
| Cell appearance | Direct/theme colors, mixed text styles, paragraph alignment, vertical alignment and margins | Table-style templates and conditional table formatting |
| Borders | Per-edge color/width, solid/dash/dot, explicit no-fill | Compound strokes and exact dash-dot patterns |
| Interaction | Pinch, zoom buttons, double tap, fit, bounded pan at 1×–5× | Wider device/API coverage remains pending |
| Other graphics | Explicit element placeholder | Charts, SmartArt, equations, media and animations |

## Reading limits

- 250 MB input file and 32 MB per XML part.
- 256 slides, 5,000 drawing nodes per slide and 25,000 rendered shapes total; group nesting is limited to 32. Extreme/degenerate transforms are bounded; zero-area objects do not offer meaningful visual content.
- 100 rows and 64 columns per table; 25,000 table grid cells per presentation.
- Every merge must fit within its grid and have consistent continuation markers. Orphan markers, overlaps and nonfinite shape coordinates are rejected before rendering.
- Embedded images are limited to 10 MB each and 50 MB total. Decoded bitmaps retain the 6-megapixel cap.
- Cell margins are capped at 1,000 points and border widths at 72 points.
- Cached style/relationship XML is capped at 8 MB and 512 parts; style references use the same protected package-path and XML rules as other parts.
- Text is capped at 25,000 paragraphs, 100,000 runs and 4 million characters per presentation. One text body is limited to 2,000 paragraphs, 10,000 runs and 100,000 characters.

## Reference fixture

`app/src/test/resources/fixtures/merged-arabic-table.pptx` is generated independently using python-pptx 1.0.2. It contains a 3×3 RTL table with unequal columns/rows, an Arabic 2×2 merged cell, explicit styles, margins, vertical centering and borders. The Android engine does not depend on python-pptx.

`theme-rich-text.pptx` adds two slides with inherited title geometry/defaults, different backgrounds, a slide-specific theme override, mixed Arabic/English runs, explicit RTL/LTR paragraphs, cached field text, point spacing, and themed cell/border colors. It verifies that one slide's overrides do not leak into another slide.

The offline font bundle contains **100 distinct families: 30 Arabic-script and 70 Latin families**. Regular, available italic/bold faces and variable weights are packaged with licenses. Sources are pinned by commit and SHA-256, fetched only when building. See [font catalog and substitution policy](FONTS.md). Proprietary Office faces receive documented substitutes. Wrapping can differ from the original; the catalog is a curated collection, not a measured top-100 popularity ranking.

`drawing-fonts-white.pptx` is generated independently with python-pptx and explicit DrawingML fixtures for white bgRef over a yellow master, inherited decorations, scaled grouped/rotated shapes, mixed Cairo/Lato runs, bullets, margins, reflection and gradients. Android pixel checks cover white PPTX/Word/PDF paper under a dark UI, Arabic/English glyphs, decorations, and integrity/loading of all bundled font families. These tests do not establish fidelity for every Office file.

Document colors are rendered independently from Material UI colors. Android Force Dark is disabled for this app; PDF paper has an explicit white surface. The test emulator can verify app output pixels, but it cannot reproduce a physical phone's display color temperature, eye-comfort filter or vendor color processing. The reported universal yellow tint has not been reproduced on the emulator yet.

Document font sizes use the same point-to-dp ratio as slide geometry, converted through Android's density API. System text scaling applies to app controls; the fixed slide layout uses document zoom instead.

To regenerate this developer fixture:

```bash
python tools/generate_pptx_fixtures.py
python tools/generate_pptx_drawing_fixture.py
```

The tests check expected cell positions and proportions, one visible cell per merge, border values, malformed merges, nonfinite coordinates, chart classification and input-file preservation. Separate viewport tests check zoom/pan limits and resize handling. This fixture is a regression case, not a broad Office compatibility corpus.

## Source references

- [Microsoft DrawingML TableCell](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.tablecell): `gridSpan`, `rowSpan`, `hMerge` and `vMerge` are cell attributes.
- [Android multitouch gestures](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch).
- [Microsoft slide masters](https://learn.microsoft.com/en-us/office/open-xml/presentation/working-with-slide-masters).
- [Microsoft DrawingML run properties](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.runproperties).
