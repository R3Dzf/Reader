# Native PowerPoint compatibility — 0.2.0

The Android app parses and renders the document locally. The parser does not write to the input file and does not execute macros. Support is tracked per feature; there is no Office-perfect fidelity claim.

| Feature | Current implementation | Remaining limits |
| --- | --- | --- |
| Slides | Presentation order, proportions, physical shape coordinates | Master/layout inheritance, groups, rotation and arbitrary shape geometry |
| Text | Arabic direction, first run's font size, bold/italic and direct RGB color | Mixed formatting, theme fonts, paragraph layout and embedded fonts |
| Images | Embedded raster images, bounded decode, resolution increases at zoom thresholds | Vector images, cropping and image effects |
| Tables | Original row/column proportions and RTL order | Theme table styles and diagonal borders |
| Merges | Horizontal, vertical and rectangular merges with one rendered origin cell | Invalid or inconsistent merge grids are rejected |
| Cell appearance | Direct RGB fills, basic font styles, centering, vertical alignment and margins | Text uses the first run's style; theme colors are unresolved |
| Borders | Per-edge color/width, solid/dash/dot, explicit no-fill | Compound strokes and exact dash-dot patterns |
| Interaction | Pinch, zoom buttons, double tap, fit, bounded pan at 1×–5× | Android gesture and screenshot verification remains pending |
| Other graphics | Explicit element placeholder | Charts, SmartArt, equations, media and animations |

## Reading limits

- 250 MB input file and 32 MB per XML part.
- 256 slides, 5,000 shapes per slide and 25,000 total shapes.
- 100 rows and 64 columns per table; 25,000 table grid cells per presentation.
- Every merge must fit within its grid and have consistent continuation markers. Orphan markers, overlaps and nonfinite shape coordinates are rejected before rendering.
- Embedded images are limited to 10 MB each and 50 MB total. Decoded bitmaps retain the 6-megapixel cap.
- Cell margins are capped at 1,000 points and border widths at 72 points.

## Reference fixture

`app/src/test/resources/fixtures/merged-arabic-table.pptx` is generated independently using python-pptx 1.0.2. It contains a 3×3 RTL table with unequal columns/rows, an Arabic 2×2 merged cell, explicit styles, margins, vertical centering and borders. The Android engine does not depend on python-pptx.

To regenerate this developer fixture:

```bash
python tools/generate_pptx_fixtures.py
```

The tests check expected cell positions and proportions, one visible cell per merge, border values, malformed merges, nonfinite coordinates, chart classification and input-file preservation. Separate viewport tests check zoom/pan limits and resize handling. This fixture is a regression case, not a broad Office compatibility corpus.

## Source references

- [Microsoft DrawingML TableCell](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.tablecell): `gridSpan`, `rowSpan`, `hMerge` and `vMerge` are cell attributes.
- [Android multitouch gestures](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch).
