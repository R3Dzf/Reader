# Native Excel compatibility — 0.11.0

Reader parses and displays workbooks offline using its own Kotlin code. It reads saved formula results and does not calculate formulas or modify the selected workbook.

| Feature | Current support | Remaining limits |
| --- | --- | --- |
| Content | Worksheets, shared/inline strings, saved numeric/text/formula values, booleans, errors, RTL | Rich text within a cell, drawings and chart rendering |
| Cell styles | Font name/size/bold/italic/underline/strike, Theme/indexed/RGB colors, fills/borders, alignment/wrap/reading order, indentation, shrink-to-fit and rotation and row/column/base-style inheritance | Diagonal borders, conditional formatting and stacked text (rotation 255) |
| Merges/layout | Horizontal/vertical/rectangular merges, saved row heights and estimated column widths, hidden axes, sparse empty-row spacing | Exact original-font width metrics, print-page layout, differing border segments on a merged edge |
| Viewport | Visible cells only, 50%–500% zoom, two-axis pan, fixed axis labels, saved frozen rows/columns, direct A1-address navigation, cell search, fit-to-screen columns, accessible cell descriptions and selectable full-value/formula dialog | No fling/scrollbar yet; up to 4,096 candidate cells and 256 rows/columns per viewport |
| Tables | Table-part relationships, header/totals rows, row/column banding, first/last-column emphasis, custom differential colors/borders and direct cell precedence | Built-in Light/Medium/Dark styles use an approximate theme-palette rendering; custom styles support colors, bold and outer borders, not every differential property |
| Numbers | Fixed/optional decimal places, grouping, zero padding, percent, trailing comma scaling, basic scientific notation | Fractions, engineering notation, accounting fill/alignment, conditions and locale directives |
| Sections | Positive/negative/zero sections, quoted/escaped literals and eight named colors | Custom indexed colors and conditional sections; text values stay unchanged |
| Dates | Saved 1900/1904 epoch, Excel's synthetic 1900 leap day, day/month/year names and common clock formats | Locale-specific calendars and localized built-in format variants |
| Time | AM/PM, month/minute distinction, elapsed hours/minutes/seconds, 1–3 subsecond digits | Other fractional precision and negative dates/durations |
| Fallback | Raw saved values retained in the model; active unsupported number formats show saved values and a sheet notice | General format preserves saved numeric spelling rather than Excel's width-sensitive display |

Number punctuation and month/weekday names are deterministic (English). Arabic currency labels can be preserved as quoted literals. Locale-dependent number formats are marked unsupported rather than guessed. ISO-string cells (`t="d"`) stay as saved strings.

Column widths use SpreadsheetML's conversion with a reference maximum digit width of 7 pixels at 96 dpi (Calibri 11), converted into points. Missing Office fonts can change actual metrics and wrapping. Row/column dimensions honor hidden/zero-height settings. The sheet uses sparse prefix sums and binary searches instead of allocating every row up to the last address. Merge intervals are indexed by column, with overlapping/reversed merges rejected; an offscreen origin still supplies the visible merged cell. Origin borders take precedence, with right/bottom end-cell edges used when missing at the origin.

Text is measured and shaped locally by Compose/Android. Document fonts keep fixed proportions as system font scale changes, while app controls retain accessibility scaling. Visible text layout is capped at 16,384 pixels per axis and 256 lines; the full stored value is available through the cell dialog. Extremely tiny saved cells can hit the viewport candidate limit and require zooming in. Colors/dashes/double borders are basic approximations, with compound/diagonal strokes and segmented merge edges remaining future work.

The reader honors custom `numFmt` records and resolves the common built-in IDs. A cell's number format inherits from `cellStyleXfs` when local application is disabled or absent. Formats longer than 512 characters, duplicate/invalid format IDs, and more than 4,096 style/format records are rejected. Formatting itself is bounded to 128 input characters, finite numbers with bounded decimal exponents, 15 decimal places and 1,024 output characters. Unknown constructs retain the original value.

Limits remain 512 columns, 100,000 stored row entries and 250,000 cell entries per workbook. Addresses can reach row 1,048,576 without expanding missing rows. Dimensions are bounded to 255 character units per column and 409 points per row; duplicate cell addresses and overlapping column definitions are rejected. Merge indexing is capped at 250,000 column interval entries. Cell text is capped at 32,767 characters and 4 million resolved characters per workbook.

`app/src/test/resources/fixtures/number-formats-arabic.xlsx` was generated with openpyxl 3.1.5. It includes Arabic labels/currency, grouping, percentages, dates, an elapsed duration, the synthetic leap day, numeric-looking text, booleans, error text, unsupported fractions, saved/unsaved formula results and scientific notation. openpyxl is developer tooling and is not an app dependency.

`worksheet-layout-arabic.xlsx` adds unequal widths/heights, hidden axes, horizontal/vertical/rectangle merges, Arabic wrapped text, direct fills/borders, alignment, numeric styling and a cell at row 1,000,000. Regenerate both fixtures with `python tools/generate_xlsx_fixtures.py`.

Parser/layout tests verify dimensions and values, style inheritance, sparse/hidden axes, merge overlap and malformed input, resource limits, offscreen origins and preservation of input bytes. Five Android instrumentation tests cover actual native rendering, merged Arabic text, RTL position/fill pixels, zoom/reset, pan and full-cell reading. The workflow runs them on Android 35; physical-device and Office-reference screenshot comparisons remain future work.

Reference specifications:

- [Microsoft NumberingFormat](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.numberingformat): implied built-in formats and General behavior.
- [Microsoft date systems in Excel](https://support.microsoft.com/en-us/excel/date-systems-in-excel).
- [Microsoft SpreadsheetML column width](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.column).
- [Android Compose graphics](https://developer.android.com/develop/ui/compose/graphics/draw/overview).

## Mobile worksheet workspace

The sheet toolbar keeps zoom, cell navigation and search within reach. Worksheet tabs are below the grid. The overflow menu contains Reset view, Fit columns and the frozen-pane toggle. Fit uses the populated/table/merged columns and is bounded to the normal 50%–500% zoom range; very wide workbooks still require horizontal scrolling. Search checks displayed values and saved formula text, skips hidden rows/columns, and caps results at 1,000. Formula results remain the values saved by the document producer.

Saved frozen panes use separate clipped corner, row, column and scrolling regions. RTL reverses their screen projection without changing A1 addressing. If a saved frozen region would occupy 70% or more of an available axis, freezing on that axis is suspended so the phone still has a usable scrolling area. The user can disable panes from the sheet menu. Split panes without a frozen state are not treated as frozen rows or columns.

Unwrapped text may flow into up to 32 adjacent empty, unfilled, unmerged cells. Saved values, fills and vertical borders stop overflow. Backgrounds render before text so empty cells do not paint over an earlier label. Wrapped, rotated, numeric and shrink-to-fit cells retain their own clip. Text rotation is a local rendering approximation; complex rich text and exact Office font metrics remain limitations.

The parser follows workbook relationships for shared strings, styles and themes, including nonstandard part filenames. Table regions remain sparse, including tables ending at row 1,000,000. Custom table definitions and pane coordinates are bounded and validated. New regression tests cover table styling and direct overrides, custom part paths, cached formulas, alignment, invalid panes/ranges, sparse geometry, hidden-axis search and overflow barriers. Native device tests exercise diagonal scrolling with frozen headers, Arabic tables, fit-to-screen and formula search/full-value reading.
