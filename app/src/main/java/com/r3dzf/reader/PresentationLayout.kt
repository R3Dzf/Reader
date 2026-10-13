package com.r3dzf.reader

data class SlideTableCellPlacement(
    val cell: NativeSlideTableCell,
    val row: Int,
    val column: Int,
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val heightFraction: Float
)

/** DrawingML retains every grid cell, including the continuation cells of a merge. */
internal fun layoutSlideTable(table: NativeSlideTable): List<SlideTableCellPlacement> {
    val rowCount = table.rows.size
    val columnCount = table.columnWidths.size
    fun invalid(): Nothing = throw InvalidOfficeDocumentException("A PowerPoint table has invalid dimensions or merged cells.")
    if (rowCount == 0 || columnCount == 0 || table.rowHeights.size != rowCount ||
        table.rows.any { it.size != columnCount }
    ) invalid()

    fun axisOffsets(values: List<Float>): List<Float> {
        if (values.any { !it.isFinite() || it <= 0f }) invalid()
        val total = values.sumOf { it.toDouble() }
        var position = 0.0
        return listOf(0f) + values.map { value ->
            position += value.toDouble()
            (position / total).toFloat()
        }
    }

    val columns = axisOffsets(table.columnWidths)
    val rows = axisOffsets(table.rowHeights)
    val owners = Array(rowCount) { arrayOfNulls<SlideTableCellPlacement>(columnCount) }
    val placements = mutableListOf<SlideTableCellPlacement>()
    table.rows.forEachIndexed { rowIndex, row ->
        row.forEachIndexed { columnIndex, cell ->
            val owner = owners[rowIndex][columnIndex]
            if (cell.horizontalMerge || cell.verticalMerge) {
                if (owner == null ||
                    cell.horizontalMerge != (columnIndex > owner.column) ||
                    cell.verticalMerge != (rowIndex > owner.row)
                ) invalid()
            } else {
                if (owner != null || cell.rowSpan !in 1..(rowCount - rowIndex) ||
                    cell.columnSpan !in 1..(columnCount - columnIndex)
                ) invalid()
                val endColumn = columnIndex + cell.columnSpan
                val endRow = rowIndex + cell.rowSpan
                val placement = SlideTableCellPlacement(
                    cell, rowIndex, columnIndex,
                    if (table.rightToLeft) 1f - columns[endColumn] else columns[columnIndex],
                    rows[rowIndex], columns[endColumn] - columns[columnIndex],
                    rows[endRow] - rows[rowIndex]
                )
                for (coveredRow in rowIndex until endRow) {
                    for (coveredColumn in columnIndex until endColumn) {
                        if (owners[coveredRow][coveredColumn] != null) invalid()
                        owners[coveredRow][coveredColumn] = placement
                    }
                }
                placements += placement
            }
        }
    }
    return placements
}

/** Translation is in viewport pixels, with the slide scaled around its center. */
internal data class SlideViewport(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f
) {
    fun transformed(
        zoom: Float = 1f,
        panX: Float = 0f,
        panY: Float = 0f,
        width: Float,
        height: Float
    ): SlideViewport {
        if (!zoom.isFinite() || zoom <= 0f || !panX.isFinite() || !panY.isFinite()) return this
        val nextScale = (scale * zoom).coerceIn(1f, 5f)
        if (nextScale == 1f || width <= 0f || height <= 0f) return SlideViewport(nextScale)
        val ratio = nextScale / scale
        val maxX = width * (nextScale - 1f) / 2f
        val maxY = height * (nextScale - 1f) / 2f
        return SlideViewport(
            nextScale,
            (translationX * ratio + panX).coerceIn(-maxX, maxX),
            (translationY * ratio + panY).coerceIn(-maxY, maxY)
        )
    }
}
