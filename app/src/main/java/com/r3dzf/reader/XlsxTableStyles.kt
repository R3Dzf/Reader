package com.r3dzf.reader

import org.w3c.dom.Element

/** Sparse table regions: styling never materializes the cells in a large table. */
data class NativeSheetTable(
    val range: NativeMergedRange,
    val headerRows: Int = 1,
    val totalRows: Int = 0,
    val rowStripes: Boolean = true,
    val columnStripes: Boolean = false,
    val firstColumn: Boolean = false,
    val lastColumn: Boolean = false,
    val elements: Map<String, NativeTableStyleElement> = emptyMap(),
    val approximate: Boolean = false
) {
    fun contains(row: Int, column: Int) = row in range.row..range.lastRow && column in range.firstColumn..range.lastColumn
    fun styled(row: Int, column: Int, original: NativeSheetCell): NativeSheetCell {
        if (!contains(row, column)) return original
        var fill: Long? = null; var text: Long? = null; var bold = false
        var left: NativeSheetBorder? = null; var right: NativeSheetBorder? = null
        var top: NativeSheetBorder? = null; var bottom: NativeSheetBorder? = null
        fun apply(name: String) { elements[name]?.let { e ->
            val c = e.cell; fill = c.fillRgb ?: fill; text = c.textRgb ?: text; bold = bold || c.bold
            left = c.borderLeft ?: left; right = c.borderRight ?: right; top = c.borderTop ?: top; bottom = c.borderBottom ?: bottom
        } }
        fun stripe(position: Int, first: String, second: String) {
            val a = elements[first]?.size ?: 1; val b = elements[second]?.size ?: 1
            apply(if (position % (a + b) < a) first else second)
        }
        apply("wholeTable")
        val header = row < range.row + headerRows
        val total = row > range.lastRow - totalRows
        if (!header && !total) {
            if (columnStripes) stripe(column - range.firstColumn, "firstColumnStripe", "secondColumnStripe")
            if (rowStripes) stripe(row - range.row - headerRows, "firstRowStripe", "secondRowStripe")
        }
        if (firstColumn && column == range.firstColumn) apply("firstColumn")
        if (lastColumn && column == range.lastColumn) apply("lastColumn")
        if (header) { apply("headerRow"); if (column == range.firstColumn) apply("firstHeaderCell"); if (column == range.lastColumn) apply("lastHeaderCell") }
        if (total) { apply("totalRow"); if (column == range.firstColumn) apply("firstTotalCell"); if (column == range.lastColumn) apply("lastTotalCell") }
        return original.copy(fillRgb = original.fillRgb ?: fill, textRgb = if (original.hasExplicitFont) original.textRgb else text ?: original.textRgb,
            bold = original.bold || bold, borderLeft = original.borderLeft ?: left, borderRight = original.borderRight ?: right,
            borderTop = original.borderTop ?: top, borderBottom = original.borderBottom ?: bottom)
    }
}
data class NativeTableStyleElement(val cell: NativeSheetCell, val size: Int = 1)

internal fun readXlsxFrozenPane(pane: Element?): Pair<Int, Int> {
    if (pane?.attributeByLocalName("state") !in listOf("frozen", "frozenSplit")) return 0 to 0
    fun count(name: String, limit: Int): Int {
        val value = pane?.attributeByLocalName(name) ?: return 0
        val n = value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 && it <= limit && it % 1.0 == 0.0 }
            ?: throw InvalidOfficeDocumentException("A worksheet frozen pane has invalid boundaries.")
        return n.toInt()
    }
    return count("ySplit", 1_048_576) to count("xSplit", 512)
}

internal class XlsxTableStyles(styles: Element?, private val colors: XlsxColors) {
    private val custom: Map<String, Map<String, NativeTableStyleElement>>
    init {
        val dxfs = styles?.directChild("dxfs")?.elementChildren().orEmpty()
        val tableStyles = styles?.directChild("tableStyles")?.elementChildren().orEmpty()
        if (dxfs.size > 4096 || tableStyles.size > 4096) throw InvalidOfficeDocumentException("Too many Excel differential styles.")
        val patches = dxfs.map { dxf ->
            val font = dxf.directChild("font"); val border = dxf.directChild("border")
            val pattern = dxf.directChild("fill")?.directChild("patternFill")
            NativeSheetCell("", bold = font?.directChild("b")?.attributeByLocalName("val") != "0" && font?.directChild("b") != null,
                textRgb = colors.read(font?.directChild("color")),
                fillRgb = if (pattern?.attributeByLocalName("patternType") == "solid") colors.read(pattern.directChild("fgColor")) else null,
                borderLeft = readXlsxBorder(border?.directChild("left"), colors), borderRight = readXlsxBorder(border?.directChild("right"), colors),
                borderTop = readXlsxBorder(border?.directChild("top"), colors), borderBottom = readXlsxBorder(border?.directChild("bottom"), colors))
        }
        custom = tableStyles.associate { style ->
            val children = style.elementChildren()
            if (children.size > 64) throw InvalidOfficeDocumentException("Too many table style elements.")
            style.attributeByLocalName("name").orEmpty() to children.associate { element ->
                val id = element.attributeByLocalName("dxfId")?.toIntOrNull()
                val cell = if (element.attributeByLocalName("dxfId") == null) NativeSheetCell("") else id?.let { patches.getOrNull(it) } ?: throw InvalidOfficeDocumentException("A table style references a missing differential style.")
                val size = element.attributeByLocalName("size")?.toIntOrNull()?.coerceIn(1, 1000) ?: 1
                element.attributeByLocalName("type").orEmpty() to NativeTableStyleElement(cell, size)
            }
        }
    }
    fun readTable(table: Element): NativeSheetTable {
        if (table.localName() != "table") throw InvalidOfficeDocumentException("Invalid worksheet table definition.")
        val endpoints = table.attributeByLocalName("ref")?.split(':').orEmpty()
        val first = endpoints.firstOrNull()?.let(::sheetAddress)
        val last = endpoints.lastOrNull()?.let(::sheetAddress)
        if (endpoints.size !in 1..2 || first == null || last == null || first.first > last.first || first.second > last.second) {
            throw InvalidOfficeDocumentException("A worksheet table has invalid boundaries.")
        }
        val info = table.directChild("tableStyleInfo")
        val name = info?.attributeByLocalName("name").orEmpty()
        fun flag(name: String, default: Boolean = false) = info?.attributeByLocalName(name)?.let { it in listOf("1", "true") } ?: default
        val elements = custom[name] ?: builtIn(name)
        return NativeSheetTable(NativeMergedRange(first.first, first.second, last.second, last.first),
            headerRows = table.attributeByLocalName("headerRowCount")?.toIntOrNull()?.coerceIn(0, 1) ?: 1,
            totalRows = table.attributeByLocalName("totalsRowCount")?.toIntOrNull()?.coerceIn(0, 1) ?: 0,
            rowStripes = flag("showRowStripes"), columnStripes = flag("showColumnStripes"),
            firstColumn = flag("showFirstColumn"), lastColumn = flag("showLastColumn"), elements = elements,
            approximate = name.isNotEmpty() && custom[name] == null)
    }
    /** Built-in style families use the workbook accent palette. Custom DXFs above retain saved colors. */
    private fun builtIn(name: String): Map<String, NativeTableStyleElement> {
        val match = Regex("TableStyle(Light|Medium|Dark)([0-9]+)").matchEntire(name) ?: return emptyMap()
        val family = match.groupValues[1]; val index = match.groupValues[2].toIntOrNull() ?: return emptyMap()
        val accent = if (index % 7 == 1) colors.theme(1) else colors.theme(4 + ((index - 2).mod(7)).coerceAtMost(5))
        val dark = family != "Light"
        fun e(fill: Long? = null, text: Long? = null, bold: Boolean = false, top: NativeSheetBorder? = null) = NativeTableStyleElement(NativeSheetCell("", fillRgb = fill, textRgb = text, bold = bold, borderTop = top))
        return mapOf("headerRow" to e(if (dark) accent else colors.theme(0), if (dark) colors.theme(0) else accent, true),
            "firstRowStripe" to e(XlsxColors.tint(accent, if (family == "Dark") .65 else .85)),
            "firstColumn" to e(bold = true), "lastColumn" to e(bold = true),
            "totalRow" to e(bold = true, top = NativeSheetBorder(1.5f, accent)))
    }
}
