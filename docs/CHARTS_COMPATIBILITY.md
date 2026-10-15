# Native PowerPoint charts — Reader 0.7.0

The reader draws charts locally from OOXML caches or literals. It does not launch Excel, evaluate workbook formulas, fetch external data, convert through a service or change the source file. Original cached strings are retained for the chart-data reading dialog. Stale caches remain stale; this reader does not recalculate them.

| Feature | Implemented | Limits |
| --- | --- | --- |
| 2D columns/bars | Clustered, stacked, percent-stacked; positive/negative stacks; saved gap width; series/point colors | Negative percent stacks are explicitly unavailable; exact overlap/style templates are future work |
| Line/area | Cached points, markers, standard/stacked/percent groups, gap/zero/span blanks | Smooth curves, effects and exact area overlap are future work |
| Pie/doughnut | Saved values, per-sector colors, first-slice angle, real doughnut hole, optional values/percent/category labels | Negative values, multiple concentric series, exploded sectors and 3D require more work |
| Scatter | Numeric X/Y caches and bounds; marker/straight-line modes, unequal X intervals | Smooth/bubble modes are unavailable |
| Axes | Linear bounds, major units, reversal, axis titles, supported saved number formats | Date/logarithmic axes are explicitly unavailable; custom crossing/label rotations and exact ticks are future work |
| Text | Arabic/English title, categories and legend; native bidi/shaping with offline font registry | Generic chart typography/layout; rich title runs and style/color override packages are future work |
| Colors | Theme accents, direct series/line/point colors, chart and plot background | Pattern/gradient/effect style fidelity is future work |
| Reading | Full saved values in an accessible scrollable dialog; notices for unsupported or missing caches | No external workbook loading or cache regeneration |

Combined/secondary-axis, radar, stock, surface, 3D, bubble and newer chart extensions receive an unsupported-layout notice. Available cached values can still be read. No zero values are invented for an absent cache. Blank/error values preserve gaps unless the file requests zero/span behavior. Zero has its own value and is never treated as blank.

Chart geometry uses a native layout with readable labels; this is not an Office-reference screenshot match. Dense category labels are sampled, legends are capped at 12 visible items, and value labels are limited to 64 points (24 sectors). All cached rows remain available in the data dialog. Data labels can overlap in tight layouts. User zoom enlarges the same document drawing.

## Resource limits

- 256 chart instances per presentation, 32 series and 512 points per series/cache.
- 8,192 numeric X/Y values per chart; 50,000 cached points across the presentation, including label caches.
- Eight levels for multi-level categories; 4,096 characters per label and 200,000 chart-text characters per presentation.
- Nonfinite/oversized numbers, duplicate or out-of-range indices and inconsistent point counts are rejected.
- Chart XML uses the protected ZIP/XML and relationship rules. Theme/relationship/chart XML share the existing 8 MB/512-part cache. Embedded chart workbooks and external data relationships are never evaluated.

## Independent fixture and validation

`tools/generate_pptx_chart_fixture.py` creates `charts-native-arabic.pptx` with python-pptx: nine real chart parts, embedded workbooks and cached data, Arabic labels, negative values, blank points, explicit axes/colors, pie/doughnut and numeric scatter X intervals. Python-pptx is test tooling only. Tests remove the embedded workbook to prove cached reading, mutate malformed inputs, check stack/range/sector geometry, and verify native Android pixels and accessible values. Android exports an actual chart gallery for visual review.

Primary references: [Microsoft NumberingCache](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.charts.numberingcache), [chart grouping](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.charts.bargroupingvalues), [ScatterChart](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.charts.scatterchart), [CategoryAxis](https://learn.microsoft.com/en-us/dotnet/api/documentformat.openxml.drawing.charts.categoryaxis).
