package com.r3dzf.reader

import org.w3c.dom.Element

internal data class XlsxDimensions(val defaultColumnWidth: Float, val defaultRowHeight: Float, val normalRowHeight: Float,
    val columnWidths: Map<Int, Float>, val columnStyleIds: Map<Int, Int>, val showGridLines: Boolean)

internal fun xlsxDimension(value: String, maximum: Float): Float = value.toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..maximum }
    ?: throw InvalidOfficeDocumentException("An Excel row or column has an invalid size.")

internal fun readXlsxDimensions(root: Element): XlsxDimensions {
    fun enabled(element: Element, name: String, default: Boolean = false) = element.attributeByLocalName(name)?.let { it != "0" && it != "false" } ?: default
    val format = root.directChild("sheetFormatPr")
    val defaultWidth = format?.attributeByLocalName("defaultColWidth")?.let { xlsxColumnPoints(xlsxDimension(it, 255f)) } ?: 48f
    val normalHeight = format?.attributeByLocalName("defaultRowHeight")?.let { xlsxDimension(it, 409f) } ?: 15f
    val defaultHeight = if (format != null && enabled(format, "zeroHeight")) 0f else normalHeight
    val widths = mutableMapOf<Int, Float>(); val styles = mutableMapOf<Int, Int>()
    val columns = root.directChild("cols")?.elementChildren()?.filter { it.localName() == "col" }.orEmpty()
    if (columns.size > 512) throw InvalidOfficeDocumentException("Too many Excel column definitions.")
    for (column in columns) {
        val first = column.attributeByLocalName("min")?.toIntOrNull() ?: 0
        val last = column.attributeByLocalName("max")?.toIntOrNull() ?: 0
        if (first !in 1..512 || last !in first..512) throw InvalidOfficeDocumentException("An Excel column range exceeds the supported limits.")
        val size = column.attributeByLocalName("width")?.let { xlsxColumnPoints(xlsxDimension(it, 255f)) } ?: defaultWidth
        val style = column.attributeByLocalName("style")?.let { it.toIntOrNull()?.takeIf { id -> id >= 0 }
            ?: throw InvalidOfficeDocumentException("An Excel column has an invalid style.") }
        for (index in first - 1 until last) {
            if (widths.put(index, if (enabled(column, "hidden")) 0f else size) != null) throw InvalidOfficeDocumentException("Excel column definitions overlap.")
            if (style != null) styles[index] = style
        }
    }
    val view = root.directChild("sheetViews")?.elementChildren()?.firstOrNull { it.localName() == "sheetView" }
    return XlsxDimensions(defaultWidth, defaultHeight, normalHeight, widths, styles, view?.let { enabled(it, "showGridLines", true) } ?: true)
}

internal fun readXlsxBorder(edge: Element?,colors: XlsxColors = XlsxColors()): NativeSheetBorder? {
    val style = edge?.attributeByLocalName("style") ?: return null
    if (style == "none") return null
    val width = when (style) { "hair" -> 0.25f; "medium", "mediumDashed", "mediumDashDot", "mediumDashDotDot", "double" -> 1.5f; "thick" -> 2.25f; else -> 0.75f }
    val dash = when (style) {
        "dashed", "mediumDashed" -> SheetBorderDash.DASH
        "dotted" -> SheetBorderDash.DOT
        "dashDot", "mediumDashDot", "slantDashDot" -> SheetBorderDash.DASH_DOT
        "dashDotDot", "mediumDashDotDot" -> SheetBorderDash.DASH_DOT_DOT
        "double" -> SheetBorderDash.DOUBLE
        else -> SheetBorderDash.SOLID
    }
    val rgb = colors.read(edge.directChild("color"))
    return NativeSheetBorder(width, rgb, dash)
}

internal fun readXlsxAppearance(xf: Element?, style: NativeSheetCell): NativeSheetCell {
    val alignment = xf?.directChild("alignment") ?: return style
    val horizontal = alignment.attributeByLocalName("horizontal") ?: "general"
    val vertical = alignment.attributeByLocalName("vertical") ?: "bottom"
    val order = alignment.attributeByLocalName("readingOrder")?.toIntOrNull()?.takeIf { it in 0..2 } ?: 0
    val rotation = alignment.attributeByLocalName("textRotation")?.toIntOrNull() ?: 0
    val indent = alignment.attributeByLocalName("indent")?.toIntOrNull()?.coerceIn(0, 250) ?: 0
    val unsupported = horizontal !in listOf("general", "left", "center", "right", "justify") || vertical !in listOf("top", "center", "bottom") ||
        rotation !in 0..180
    return style.copy(horizontalAlignment = when (horizontal) { "left" -> SheetHorizontalAlignment.LEFT; "center" -> SheetHorizontalAlignment.CENTER;
        "right" -> SheetHorizontalAlignment.RIGHT; "justify" -> SheetHorizontalAlignment.JUSTIFY; else -> SheetHorizontalAlignment.GENERAL },
        verticalAlignment = when (vertical) { "top" -> SheetVerticalAlignment.TOP; "center" -> SheetVerticalAlignment.CENTER; else -> SheetVerticalAlignment.BOTTOM },
        indent = indent, shrinkToFit = alignment.attributeByLocalName("shrinkToFit") in listOf("1", "true"),
        textRotation = rotation.takeIf { it in 0..180 } ?: 0,
        readingOrder = order, wrapText = alignment.attributeByLocalName("wrapText") in listOf("1", "true"), appearanceUnsupported = style.appearanceUnsupported || unsupported)
}
