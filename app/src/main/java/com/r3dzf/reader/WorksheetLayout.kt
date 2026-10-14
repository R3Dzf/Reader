package com.r3dzf.reader

import kotlin.math.floor

/** SpreadsheetML width conversion using Calibri 11's reference maximum digit width (7 px at 96 dpi). */
internal fun xlsxColumnPoints(width: Float): Float = (floor((256 * width + floor(128.0 / 7)) / 256 * 7) * 0.75).toFloat()

/** Sparse prefix sums retain empty/hidden rows without allocating a million-row array. */
internal class SheetAxis(val count: Int, private val defaultSize: Float, overrides: Map<Int, Float>) {
    private val indices = overrides.keys.sorted().toIntArray()
    private val changes = DoubleArray(indices.size + 1)
    init {
        if (count < 0 || !defaultSize.isFinite() || defaultSize < 0f || overrides.any { (i, size) -> i !in 0 until count || !size.isFinite() || size < 0f }) {
            throw InvalidOfficeDocumentException("A worksheet axis has invalid dimensions.")
        }
        indices.forEachIndexed { i, index -> changes[i + 1] = changes[i] + overrides.getValue(index) - defaultSize }
    }
    fun offset(index: Int): Double {
        require(index in 0..count)
        var low = 0; var high = indices.size
        while (low < high) { val mid = (low + high) ushr 1; if (indices[mid] < index) low = mid + 1 else high = mid }
        return (index.toDouble() * defaultSize + changes[low]).coerceAtLeast(0.0)
    }
    val total: Double get() = offset(count)
    fun indexAt(position: Double): Int {
        if (!position.isFinite() || position < 0) return 0
        var low = 0; var high = count
        while (low < high) { val mid = (low + high) ushr 1; if (offset(mid + 1) <= position) low = mid + 1 else high = mid }
        return low
    }
    fun visible(start: Double, end: Double, limit: Int = 256): List<Int> = buildList {
        var i = indexAt(start.coerceAtLeast(0.0))
        while (i < count && offset(i) < end && size < limit) { add(i); i = indexAt(offset(i + 1)) }
    }
}

/** Disjoint intervals indexed per column; no merge area is expanded into individual cells. */
internal class SheetMergeIndex(ranges: List<NativeMergedRange>, columns: Int, rows: Int) {
    private val byColumn = Array(columns) { mutableListOf<NativeMergedRange>() }
    init {
        var entries = 0
        for (range in ranges) {
            if (range.row < 0 || range.lastRow < range.row || range.lastRow >= rows || range.firstColumn < 0 || range.lastColumn < range.firstColumn || range.lastColumn >= columns) {
                throw InvalidOfficeDocumentException("A worksheet merge has invalid boundaries.")
            }
            entries += range.lastColumn - range.firstColumn + 1
            if (entries > 250_000) throw InvalidOfficeDocumentException("Worksheet merge indexing exceeds the reading limit.")
            for (column in range.firstColumn..range.lastColumn) byColumn[column] += range
        }
        byColumn.forEach { list ->
            list.sortBy { it.row }
            for (i in 1 until list.size) if (list[i].row <= list[i - 1].lastRow) {
                throw InvalidOfficeDocumentException("The worksheet contains overlapping merged cells.")
            }
        }
    }
    fun at(row: Int, column: Int): NativeMergedRange? {
        val ranges = byColumn.getOrNull(column) ?: return null
        var low = 0; var high = ranges.size
        while (low < high) { val mid = (low + high) ushr 1; if (ranges[mid].row <= row) low = mid + 1 else high = mid }
        return ranges.getOrNull(low - 1)?.takeIf { row <= it.lastRow }
    }
}

internal data class SheetPlacement(val range: NativeMergedRange, val cell: NativeSheetCell, val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val address: String get() {
        val first = sheetColumnLabel(range.firstColumn) + (range.row + 1)
        val last = sheetColumnLabel(range.lastColumn) + (range.lastRow + 1)
        return if (first == last) first else "$first:$last"
    }
}
internal data class WorksheetWindow(val cells: List<SheetPlacement>, val limited: Boolean)

internal class WorksheetLayout(val sheet: NativeWorksheet) {
    val columns = SheetAxis(maxOf(sheet.columnCount, 1), sheet.defaultColumnWidthPoints, sheet.columnWidthsPoints)
    val rows = SheetAxis(maxOf(sheet.rowCount, 1), sheet.defaultRowHeightPoints,
        sheet.rows.mapNotNull { row -> if (row.hidden || row.heightPoints != null) row.rowIndex to if (row.hidden) 0f else row.heightPoints!! else null }.toMap())
    private val cells = sheet.rows.associate { it.rowIndex to it.cells }
    private val rowDefaults = sheet.rows.mapNotNull { it.defaultCell?.let { cell -> it.rowIndex to cell } }.toMap()
    private val merges = SheetMergeIndex(sheet.mergedRanges, columns.count, rows.count)
    fun placement(row: Int, column: Int): SheetPlacement? {
        if (row !in 0 until rows.count || column !in 0 until columns.count) return null
        val range = merges.at(row, column) ?: NativeMergedRange(row, column, column)
        val origin = cells[range.row]?.get(range.firstColumn) ?: rowDefaults[range.row] ?: sheet.columnStyles[range.firstColumn] ?: sheet.defaultCell
        // Saved end-cell edges are common in merged ranges; explicit origin edges take precedence.
        val end = cells[range.lastRow]?.get(range.lastColumn)
        val cell = if (end == null) origin else origin.copy(borderRight = origin.borderRight ?: end.borderRight, borderBottom = origin.borderBottom ?: end.borderBottom)
        return SheetPlacement(range, cell, columns.offset(range.firstColumn), rows.offset(range.row), columns.offset(range.lastColumn + 1), rows.offset(range.lastRow + 1))
    }
    fun visible(left: Double, top: Double, width: Double, height: Double): List<SheetPlacement> = window(left, top, width, height).cells
    fun window(left: Double, top: Double, width: Double, height: Double): WorksheetWindow {
        val visibleColumns = columns.visible(left, left + width)
        val visibleRows = rows.visible(top, top + height, (4096 / maxOf(visibleColumns.size, 1)).coerceAtMost(256))
        val placements = linkedMapOf<Pair<Int, Int>, SheetPlacement>()
        for (row in visibleRows) for (column in visibleColumns) {
            val placement = placement(row, column) ?: continue
            placements.putIfAbsent(placement.range.row to placement.range.firstColumn, placement)
        }
        val limited = visibleColumns.lastOrNull()?.let { columns.offset(it + 1) < minOf(columns.total, left + width) } == true ||
            visibleRows.lastOrNull()?.let { rows.offset(it + 1) < minOf(rows.total, top + height) } == true
        return WorksheetWindow(placements.values.toList(), limited)
    }
}

internal data class SheetViewport(val zoom: Float = 1f, val x: Double = 0.0, val y: Double = 0.0) {
    fun transformed(zoomChange: Float = 1f, panX: Double = 0.0, panY: Double = 0.0, width: Double, height: Double, contentWidth: Double, contentHeight: Double): SheetViewport {
        if (!zoomChange.isFinite() || zoomChange <= 0f || !panX.isFinite() || !panY.isFinite()) return this
        if (listOf(width, height, contentWidth, contentHeight).any { !it.isFinite() || it < 0.0 }) return this
        val next = (zoom * zoomChange).coerceIn(0.5f, 5f)
        // Anchor zoom to the visible center in document points, then apply pan in document points.
        val ratio = zoom / next
        return SheetViewport(next,
            (x + width / 2 * (1 - ratio) - panX).coerceIn(0.0, (contentWidth - width * ratio).coerceAtLeast(0.0)),
            (y + height / 2 * (1 - ratio) - panY).coerceIn(0.0, (contentHeight - height * ratio).coerceAtLeast(0.0)))
    }
}

internal fun sheetColumnLabel(index: Int): String {
    var value = index + 1; val label = StringBuilder()
    while (value > 0) { value--; label.append(('A'.code + value % 26).toChar()); value /= 26 }
    return label.reverse().toString()
}

/** SpreadsheetML context reading order uses the first non-whitespace character, independent of sheet direction. */
internal fun sheetCellIsRtl(cell: NativeSheetCell): Boolean {
    if (cell.readingOrder != 0) return cell.readingOrder == 2
    var index = 0
    while (index < cell.value.length) {
        val code = cell.value.codePointAt(index)
        index += Character.charCount(code)
        if (Character.isWhitespace(code)) continue
        return Character.getDirectionality(code) in listOf(Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC)
    }
    return false
}

/** User navigation accepts bounded A1 addresses, including optional absolute-reference markers. */
internal fun sheetAddress(value: String): Pair<Int, Int>? {
    val match = Regex("^\\$?([A-Za-z]{1,3})\\$?([1-9][0-9]{0,6})$").matchEntire(value.trim()) ?: return null
    var column = 0
    for (char in match.groupValues[1].uppercase()) column = column * 26 + char.code - 'A'.code + 1
    val row = match.groupValues[2].toIntOrNull() ?: return null
    return (row - 1 to column - 1).takeIf { row in 1..1_048_576 && column in 1..512 }
}
