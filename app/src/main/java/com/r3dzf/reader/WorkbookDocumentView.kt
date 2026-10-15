package com.r3dzf.reader

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun WorkbookDocumentView(workbook: NativeWorkbook,initialProgress: ReadingProgress = ReadingProgress(),onProgress: (ReadingProgress) -> Unit = {}) {
    var selectedSheet by remember(workbook) { mutableIntStateOf(initialProgress.index.coerceIn(0,workbook.sheets.lastIndex.coerceAtLeast(0))) }
    LaunchedEffect(selectedSheet) { onProgress(ReadingProgress(selectedSheet,0,workbook.sheets.size)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            workbook.sheets.forEachIndexed { index, sheet ->
                TextButton(onClick = { selectedSheet = index }) {
                    Text(sheet.name, fontWeight = if (index == selectedSheet) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        workbook.sheets.getOrNull(selectedSheet)?.let { sheet -> key(sheet) { WorksheetView(sheet, Modifier.weight(1f)) } }
    }
}

@Composable
internal fun WorksheetView(sheet: NativeWorksheet, modifier: Modifier = Modifier) {
    val layout = remember(sheet) { WorksheetLayout(sheet) }
    val density = LocalDensity.current
    var viewport by remember(sheet) { mutableStateOf(SheetViewport()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember(sheet) { mutableStateOf<SheetPlacement?>(null) }
    var showCell by remember { mutableStateOf(false) }
    var showJump by remember { mutableStateOf(false) }
    var jumpAddress by remember { mutableStateOf("") }
    var jumpInvalid by remember { mutableStateOf(false) }
    val headerWidth = with(density) { 44.dp.toPx() }
    val headerHeight = with(density) { 30.dp.toPx() }
    val pointsToPixels = density.density * 4f / 3f * viewport.zoom
    val bodyWidth = (size.width - headerWidth).coerceAtLeast(0f)
    val bodyHeight = (size.height - headerHeight).coerceAtLeast(0f)
    fun transform(zoom: Float = 1f, pan: Offset = Offset.Zero) {
        val oldScale = density.density * 4f / 3f * viewport.zoom
        viewport = viewport.transformed(zoom,
            (if (sheet.rightToLeft) -pan.x else pan.x).toDouble() / oldScale,
            pan.y.toDouble() / oldScale, bodyWidth / oldScale.toDouble(), bodyHeight / oldScale.toDouble(), layout.columns.total, layout.rows.total)
    }
    LaunchedEffect(sheet, size) { transform() }
    val window = remember(layout, viewport, size, pointsToPixels) {
        layout.window(viewport.x, viewport.y, bodyWidth / pointsToPixels.toDouble(), bodyHeight / pointsToPixels.toDouble())
    }
    val cells = window.cells
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val families = remember { mutableMapOf<String, FontFamily>() }
    val hasFallbacks = remember(sheet) { sheet.rows.any { row -> row.cells.values.any { it.numberFormatUnsupported || it.appearanceUnsupported } } }
    val headerColor = MaterialTheme.colorScheme.surfaceVariant
    val headerTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = Color(0xFFD7DCE2)
    val selectedColor = MaterialTheme.colorScheme.primary
    val bodyLeft = if (sheet.rightToLeft) 0f else headerWidth
    val bodyRight = if (sheet.rightToLeft) bodyWidth else size.width.toFloat()
    fun xLeft(cell: SheetPlacement): Float = if (sheet.rightToLeft) bodyRight - ((cell.right - viewport.x) * pointsToPixels).toFloat()
        else bodyLeft + ((cell.left - viewport.x) * pointsToPixels).toFloat()
    fun xRight(cell: SheetPlacement): Float = if (sheet.rightToLeft) bodyRight - ((cell.left - viewport.x) * pointsToPixels).toFloat()
        else bodyLeft + ((cell.right - viewport.x) * pointsToPixels).toFloat()
    fun yTop(cell: SheetPlacement) = headerHeight + ((cell.top - viewport.y) * pointsToPixels).toFloat()
    fun yBottom(cell: SheetPlacement) = headerHeight + ((cell.bottom - viewport.y) * pointsToPixels).toFloat()

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = { transform(1f / 1.25f) }, modifier = Modifier.testTag("sheet-zoom-out")) { Text("−") }
            Text(stringResource(R.string.zoom_percent, (viewport.zoom * 100).roundToInt()), Modifier.padding(top = 12.dp).testTag("sheet-zoom-level"))
            TextButton(onClick = { transform(1.25f) }, modifier = Modifier.testTag("sheet-zoom-in")) { Text("+") }
            TextButton(onClick = { viewport = SheetViewport() }, modifier = Modifier.testTag("sheet-reset")) { Text(stringResource(R.string.sheet_reset)) }
            TextButton(onClick = { jumpInvalid = false; showJump = true }, modifier = Modifier.testTag("sheet-jump")) { Text(stringResource(R.string.sheet_go_to)) }
        }
        if (hasFallbacks) Text(stringResource(R.string.workbook_format_fallback), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.sheet_gesture_hint), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        if (window.limited) Text(stringResource(R.string.sheet_zoom_for_small_cells), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.White)
            .onSizeChanged { size = it }
            .pointerInput(sheet, size) { detectTransformGestures { _, pan, zoom, _ -> transform(zoom, pan) } }
            .pointerInput(sheet, size, viewport) {
                detectTapGestures(onTap = { position ->
                    if (position.x in bodyLeft..bodyRight && position.y >= headerHeight) {
                        val columnPoint = viewport.x + (if (sheet.rightToLeft) bodyRight - position.x else position.x - bodyLeft) / pointsToPixels
                        val rowPoint = viewport.y + (position.y - headerHeight) / pointsToPixels
                        selected = layout.placement(layout.rows.indexAt(rowPoint), layout.columns.indexAt(columnPoint))
                    }
                }, onDoubleTap = { transform(if (viewport.zoom > 1f) 1f / viewport.zoom else 2f) })
            }.testTag("worksheet-grid")) {
            Canvas(Modifier.fillMaxSize()) {
                clipRect(bodyLeft, headerHeight, bodyRight, size.height.toFloat()) {
                    for (placement in cells) {
                        val left = xLeft(placement); val right = xRight(placement)
                        val top = yTop(placement); val bottom = yBottom(placement)
                        val cell = placement.cell
                        drawRect(cell.fillRgb?.let(::sheetColor) ?: Color.White, Offset(left, top), Size(right - left, bottom - top))
                        if (sheet.showGridLines && cell.fillRgb == null) {
                            drawRect(gridColor, Offset(left, top), Size(right - left, bottom - top), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5.dp.toPx()))
                        }
                        drawSheetEdges(cell, left, top, right, bottom, pointsToPixels)
                        if (cell.value.isNotEmpty()) {
                            val padding = (2f * pointsToPixels).coerceAtMost((right - left) / 4)
                            val width = (right - left - 2 * padding).coerceIn(1f, 16_384f).toInt()
                            val family = cell.fontFamilyName?.let { name ->
                                families.getOrPut(name) { if (families.size >= 128) families.clear(); FontFamily(Typeface.create(name, Typeface.NORMAL)) }
                            }
                            val horizontal = sheetTextAlignment(cell)
                            val style = TextStyle(color = cell.textRgb?.let(::sheetColor) ?: Color(0xFF111827),
                                fontSize = slideFontSize(cell.fontSizePoints ?: 11f, viewport.zoom * 4f / 3f, density),
                                fontFamily = family, fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal,
                                textDecoration = TextDecoration.combine(listOfNotNull(TextDecoration.Underline.takeIf { cell.underline }, TextDecoration.LineThrough.takeIf { cell.strikeThrough })),
                                textDirection = if (sheetCellIsRtl(cell)) TextDirection.Rtl else TextDirection.Ltr,
                                textAlign = horizontal)
                            val text = textMeasurer.measure(AnnotatedString(cell.value), style,
                                overflow = TextOverflow.Clip, softWrap = cell.wrapText,
                                maxLines = if (cell.wrapText || '\n' in cell.value) 256 else 1,
                                constraints = Constraints(maxWidth = width, maxHeight = 16_384))
                            val textX = when (horizontal) { TextAlign.Right -> right - padding - width; TextAlign.Center -> (left + right - width) / 2; else -> left + padding }
                            val textY = when (cell.verticalAlignment) {
                                SheetVerticalAlignment.TOP -> top + padding
                                SheetVerticalAlignment.CENTER -> (top + bottom - text.size.height) / 2
                                SheetVerticalAlignment.BOTTOM -> bottom - padding - text.size.height
                            }
                            clipRect(left.coerceAtLeast(bodyLeft), top.coerceAtLeast(headerHeight), right.coerceAtMost(bodyRight), bottom.coerceAtMost(size.height.toFloat())) {
                                drawText(text, topLeft = Offset(textX, textY))
                            }
                        }
                        if (selected?.range == placement.range) drawRect(selectedColor, Offset(left, top), Size(right - left, bottom - top), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    }
                }
                // Headers stay visible while document coordinates pan underneath them.
                drawRect(headerColor, Offset(0f, 0f), Size(size.width.toFloat(), headerHeight))
                val rowHeaderX = if (sheet.rightToLeft) bodyRight else 0f
                drawRect(headerColor, Offset(rowHeaderX, 0f), Size(headerWidth, size.height.toFloat()))
                val labelStyle = TextStyle(color = headerTextColor, fontSize = with(density) { 12.dp.toSp() }, textAlign = TextAlign.Center)
                for (column in layout.columns.visible(viewport.x, viewport.x + bodyWidth / pointsToPixels)) {
                    val dummy = SheetPlacement(NativeMergedRange(0, column, column), sheet.defaultCell, layout.columns.offset(column), 0.0, layout.columns.offset(column + 1), 0.0)
                    val left = xLeft(dummy); val right = xRight(dummy)
                    val label = textMeasurer.measure(AnnotatedString(sheetColumnLabel(column)), labelStyle, constraints = Constraints(maxWidth = (right - left).coerceIn(1f, 16_384f).toInt()), maxLines = 1)
                    clipRect(bodyLeft, 0f, bodyRight, headerHeight) { drawText(label, topLeft = Offset(left, (headerHeight - label.size.height) / 2)) }
                }
                for (row in layout.rows.visible(viewport.y, viewport.y + bodyHeight / pointsToPixels)) {
                    val top = headerHeight + ((layout.rows.offset(row) - viewport.y) * pointsToPixels).toFloat()
                    val bottom = headerHeight + ((layout.rows.offset(row + 1) - viewport.y) * pointsToPixels).toFloat()
                    val label = textMeasurer.measure(AnnotatedString((row + 1).toString()), labelStyle, constraints = Constraints(maxWidth = headerWidth.toInt()), maxLines = 1)
                    clipRect(rowHeaderX, headerHeight, rowHeaderX + headerWidth, size.height.toFloat()) { drawText(label, topLeft = Offset(rowHeaderX, (top + bottom - label.size.height) / 2)) }
                }
            }
            // Expose every visible nonempty cell to accessibility, including merged origins offscreen.
            cells.filter { it.cell.value.isNotEmpty() }.forEach { placement ->
                val left = xLeft(placement).coerceAtLeast(bodyLeft); val top = yTop(placement).coerceAtLeast(headerHeight)
                val right = xRight(placement).coerceAtMost(bodyRight); val bottom = yBottom(placement).coerceAtMost(size.height.toFloat())
                if (right > left && bottom > top) Box(Modifier.absoluteOffset(with(density) { left.toDp() }, with(density) { top.toDp() })
                    .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
                    .semantics { contentDescription = "${placement.address}: ${placement.cell.value}"; onClick { selected = placement; showCell = true; true } })
            }
        }
        TextButton(onClick = { showCell = true }, enabled = selected != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("sheet-selected-cell")) {
            Text(selected?.let { "${it.address}: ${it.cell.value}" } ?: stringResource(R.string.sheet_select_cell),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (showCell && selected != null) AlertDialog(onDismissRequest = { showCell = false },
        title = { Text(selected!!.address) }, text = { Text(selected!!.cell.value, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showCell = false }) { Text(stringResource(R.string.close)) } })
    if (showJump) AlertDialog(onDismissRequest = { showJump = false }, title = { Text(stringResource(R.string.sheet_go_to)) },
        text = { OutlinedTextField(jumpAddress, onValueChange = { jumpAddress = it.take(16); jumpInvalid = false },
            modifier = Modifier.testTag("sheet-jump-address"), singleLine = true, isError = jumpInvalid,
            label = { Text("A1") }, supportingText = { if (jumpInvalid) Text(stringResource(R.string.sheet_bad_address)) }) },
        confirmButton = { TextButton(onClick = {
            val address = sheetAddress(jumpAddress)
            val destination = address?.let { layout.placement(it.first, it.second) }
            if (destination == null) jumpInvalid = true else {
                selected = destination
                viewport = SheetViewport(viewport.zoom, destination.left, destination.top)
                transform()
                showJump = false
            }
        }, modifier = Modifier.testTag("sheet-jump-confirm")) { Text(stringResource(R.string.sheet_go)) } },
        dismissButton = { TextButton(onClick = { showJump = false }) { Text(stringResource(R.string.close)) } })
}

internal fun sheetTextAlignment(cell: NativeSheetCell): TextAlign = when (cell.horizontalAlignment) {
    SheetHorizontalAlignment.LEFT -> TextAlign.Left
    SheetHorizontalAlignment.CENTER -> TextAlign.Center
    SheetHorizontalAlignment.RIGHT -> TextAlign.Right
    SheetHorizontalAlignment.JUSTIFY -> TextAlign.Justify
    SheetHorizontalAlignment.GENERAL -> when (cell.valueKind) { SheetValueKind.NUMBER -> TextAlign.Right; SheetValueKind.BOOLEAN, SheetValueKind.ERROR -> TextAlign.Center; else -> if (sheetCellIsRtl(cell)) TextAlign.Right else TextAlign.Left }
}
private fun sheetColor(rgb: Long) = Color((0xFF000000L or rgb).toInt())

private fun DrawScope.drawSheetEdges(cell: NativeSheetCell, left: Float, top: Float, right: Float, bottom: Float, scale: Float) {
    fun edge(border: NativeSheetBorder?, side: Int) {
        border ?: return
        val width = (border.widthPoints * scale).coerceAtMost(minOf(right - left, bottom - top)).coerceAtLeast(0.1f)
        val inset = width / 2
        val start = when (side) { 0 -> Offset(left + inset, top); 1 -> Offset(right - inset, top); 2 -> Offset(left, top + inset); else -> Offset(left, bottom - inset) }
        val end = when (side) { 0 -> Offset(left + inset, bottom); 1 -> Offset(right - inset, bottom); 2 -> Offset(right, top + inset); else -> Offset(right, bottom - inset) }
        val dash = when (border.dash) {
            SheetBorderDash.DASH -> floatArrayOf(width * 4, width * 3)
            SheetBorderDash.DOT -> floatArrayOf(width, width * 2)
            SheetBorderDash.DASH_DOT -> floatArrayOf(width * 4, width * 2, width, width * 2)
            SheetBorderDash.DASH_DOT_DOT -> floatArrayOf(width * 4, width * 2, width, width * 2, width, width * 2)
            else -> null
        }
        if (border.dash == SheetBorderDash.DOUBLE) {
            val perpendicular = if (side < 2) Offset(width / 3, 0f) else Offset(0f, width / 3)
            drawLine(border.rgb?.let(::sheetColor) ?: Color.Black, start - perpendicular, end - perpendicular, width / 3)
            drawLine(border.rgb?.let(::sheetColor) ?: Color.Black, start + perpendicular, end + perpendicular, width / 3)
        } else drawLine(border.rgb?.let(::sheetColor) ?: Color.Black, start, end, width, pathEffect = dash?.let { PathEffect.dashPathEffect(it) })
    }
    edge(cell.borderLeft, 0); edge(cell.borderRight, 1); edge(cell.borderTop, 2); edge(cell.borderBottom, 3)
}
