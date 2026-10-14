# Native Excel compatibility — 0.4.0

Reader parses and displays workbooks offline using its own Kotlin code. It reads saved formula results and does not calculate formulas or modify the selected workbook.

| Feature | Current support | Remaining limits |
| --- | --- | --- |
| Content | Worksheets, shared/inline strings, saved numeric/text/formula values, booleans, errors, RTL | Rich text within a cell, drawings and chart rendering |
| Cell styles | Basic saved font/fill properties | Theme colors, borders, full alignment, conditional formatting |
| Merges/layout | Horizontal merges, native scrollable grid | Rectangular/vertical merges, saved row/column dimensions, sheet zoom and print-page layout |
| Numbers | Fixed/optional decimal places, grouping, zero padding, percent, trailing comma scaling, basic scientific notation | Fractions, engineering notation, accounting fill/alignment, conditions and locale directives |
| Sections | Positive/negative/zero sections, quoted/escaped literals and eight named colors | Custom indexed colors and conditional sections; text values stay unchanged |
| Dates | Saved 1900/1904 epoch, Excel's synthetic 1900 leap day, day/month/year names and common clock formats | Locale-specific calendars and localized built-in format variants |
| Time | AM/PM, month/minute distinction, elapsed hours/minutes/seconds, 1–3 subsecond digits | Other fractional precision and negative dates/durations |
| Fallback | Raw saved values retained in the model; active unsupported number formats show saved values and a sheet notice | General format preserves saved numeric spelling rather than Excel's width-sensitive display |

Number punctuation and month/weekday names are deterministic (English). Arabic currency labels can be preserved as quoted literals. Locale-dependent number formats are marked unsupported rather than guessed. ISO-string cells (`t="d"`) stay as saved strings.

The reader honors custom `numFmt` records and resolves the common built-in IDs. A cell's number format inherits from `cellStyleXfs` when local application is disabled or absent. Formats longer than 512 characters, duplicate/invalid format IDs, and more than 4,096 style/format records are rejected. Formatting itself is bounded to 128 input characters, finite numbers with bounded decimal exponents, 15 decimal places and 1,024 output characters. Unknown constructs retain the original value.

`app/src/test/resources/fixtures/number-formats-arabic.xlsx` was generated with openpyxl 3.1.5. It includes Arabic labels/currency, grouping, percentages, dates, an elapsed duration, the synthetic leap day, numeric-looking text, booleans, error text, unsupported fractions, saved/unsaved formula results and scientific notation. openpyxl is developer tooling and is not an app dependency.

Regenerate it with `python tools/generate_xlsx_fixtures.py`. Parser tests verify expected values, inherited/unknown formats, the 1904 epoch, duplicate/oversized codes and preservation of the input bytes. Actual Android visual verification remains pending.

Reference specifications:

- [Microsoft NumberingFormat](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.spreadsheet.numberingformat): implied built-in formats and General behavior.
- [Microsoft date systems in Excel](https://support.microsoft.com/en-us/excel/date-systems-in-excel).
