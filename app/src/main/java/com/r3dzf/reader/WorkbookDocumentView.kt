package com.r3dzf.reader

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
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
    val sheets = remember(workbook) { workbook.visibleSheets }
    var selectedSheet by remember(workbook) { mutableIntStateOf(initialProgress.index.coerceIn(0,sheets.lastIndex.coerceAtLeast(0))) }
    LaunchedEffect(selectedSheet) { onProgress(ReadingProgress(selectedSheet,0,sheets.size)) }
    Column(Modifier.fillMaxSize()) {
        sheets.getOrNull(selectedSheet)?.let { sheet -> key(sheet) { WorksheetView(sheet, Modifier.weight(1f)) } }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 44.dp)) {
            sheets.forEachIndexed { index, sheet ->
                TextButton(onClick = { selectedSheet = index }) {
                    Text(sheet.name, fontWeight = if (index == selectedSheet) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
internal fun WorksheetView(sheet: NativeWorksheet, modifier: Modifier = Modifier) {
    val layout = remember(sheet) { WorksheetLayout(sheet,minimumRows=100,minimumColumns=26) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val origin=remember(sheet) { sheet.rows.asSequence().filterNot {it.hidden}.flatMap { row -> row.cells.asSequence().filter {it.value.value.isNotBlank() && layout.columns.offset(it.key+1)>layout.columns.offset(it.key)}.map {row.rowIndex to it.key} }.firstOrNull() }
    var viewport by remember(sheet) { mutableStateOf(if(origin!=null && (origin.first>=20 || origin.second>=8)) SheetViewport(x=layout.columns.offset(origin.second),y=layout.rows.offset(origin.first)) else SheetViewport()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember(sheet) { mutableStateOf<SheetPlacement?>(null) }
    var freeze by remember(sheet) { mutableStateOf(true) }
    var showOptions by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember(sheet) { mutableStateOf("") }
    var matchIndex by remember(sheet) { mutableIntStateOf(0) }
    val matches = remember(sheet, query) { searchWorksheet(sheet, query) }
    var showCell by remember { mutableStateOf(false) }
    LaunchedEffect(showCell) { if (showCell) { focusManager.clearFocus(); keyboardController?.hide() } }
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
    val panes = remember(layout, viewport, size, freeze) { sheetPanes(layout, viewport, bodyWidth / pointsToPixels.toDouble(), bodyHeight / pointsToPixels.toDouble(), freeze) }
    val paneCells = remember(layout, panes) { panes.map { pane ->
        val left = pane.left + pane.scrollX; val top = pane.top + pane.scrollY
        val width = pane.right - pane.left; val height = pane.bottom - pane.top
        pane to (layout.window(left, top, width, height).cells + layout.overflowOrigins(left, top, width, height)).distinctBy { it.range }
    } }
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val families = remember { mutableMapOf<String, FontFamily>() }
    val hasFallbacks = remember(sheet) { sheet.rows.any { row -> row.cells.values.any { it.numberFormatUnsupported || it.appearanceUnsupported } } || sheet.tables.any { it.approximate } }
    val headerColor = MaterialTheme.colorScheme.surfaceVariant
    val headerTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = Color(0xFFD7DCE2)
    val selectedColor = MaterialTheme.colorScheme.primary
    val bodyLeft = if (sheet.rightToLeft) 0f else headerWidth
    val bodyRight = if (sheet.rightToLeft) bodyWidth else size.width.toFloat()
    fun screenX(point: Double) = if (sheet.rightToLeft) bodyRight - (point * pointsToPixels).toFloat() else bodyLeft + (point * pointsToPixels).toFloat()
    fun xLeft(cell: SheetPlacement, pane: SheetPane) = minOf(screenX(cell.left - pane.scrollX), screenX(cell.right - pane.scrollX))
    fun xRight(cell: SheetPlacement, pane: SheetPane) = maxOf(screenX(cell.left - pane.scrollX), screenX(cell.right - pane.scrollX))
    fun yTop(cell: SheetPlacement, pane: SheetPane) = headerHeight + ((cell.top - pane.scrollY) * pointsToPixels).toFloat()
    fun yBottom(cell: SheetPlacement, pane: SheetPane) = headerHeight + ((cell.bottom - pane.scrollY) * pointsToPixels).toFloat()
    fun jump(row: Int, column: Int) {
        layout.placement(row, column)?.let { destination ->
            selected = destination
            val frozenWidth = panes.filter { it.left == 0.0 && it.scrollX == 0.0 }.minOfOrNull { it.right }?.takeIf { panes.any { p -> p.left > 0 } } ?: 0.0
            val frozenHeight = panes.filter { it.top == 0.0 && it.scrollY == 0.0 }.minOfOrNull { it.bottom }?.takeIf { panes.any { p -> p.top > 0 } } ?: 0.0
            viewport = SheetViewport(viewport.zoom, (destination.left - frozenWidth).coerceAtLeast(0.0), (destination.top - frozenHeight).coerceAtLeast(0.0))
            transform()
        }
    }
    fun fitColumns() {
        val used = sheet.rows.flatMap { it.cells.keys }.maxOrNull()?.plus(1) ?: sheet.columnCount.coerceAtLeast(1)
        val end = maxOf(used, sheet.mergedRanges.maxOfOrNull { it.lastColumn + 1 } ?: 0, sheet.tables.maxOfOrNull { it.range.lastColumn + 1 } ?: 0)
        val width = layout.columns.offset(end.coerceAtMost(layout.columns.count))
        if (width > 0) viewport = SheetViewport((bodyWidth / (density.density * 4f / 3f * width)).toFloat().coerceIn(.5f, 5f))
        transform()
    }

    LaunchedEffect(matches) { if (showSearch && matches.isNotEmpty()) matches.first().let { jump(it.row, it.column) } }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { jumpInvalid = false; showJump = true }, modifier = Modifier.testTag("sheet-jump").weight(1f)) {
                Text(selected?.address ?: "A1", maxLines = 1)
            }
            TextButton(onClick = { transform(1f / 1.25f) }, modifier = Modifier.width(44.dp).testTag("sheet-zoom-out"), contentPadding = PaddingValues(0.dp)) { Text("−") }
            Text(stringResource(R.string.zoom_percent, (viewport.zoom * 100).roundToInt()), Modifier.testTag("sheet-zoom-level"), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { transform(1.25f) }, modifier = Modifier.width(44.dp).testTag("sheet-zoom-in"), contentPadding = PaddingValues(0.dp)) { Text("+") }
            TextButton(onClick = { showSearch = !showSearch }, modifier = Modifier.testTag("sheet-search")) { Text(stringResource(R.string.sheet_find)) }
            Box {
                TextButton(onClick = { showOptions = true }, modifier = Modifier.testTag("sheet-options")) { Text("⋮") }
                DropdownMenu(showOptions, onDismissRequest = { showOptions = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.sheet_fit_columns)) }, onClick = { fitColumns(); showOptions = false }, modifier = Modifier.testTag("sheet-fit"))
                    DropdownMenuItem(text = { Text(stringResource(R.string.sheet_reset)) }, onClick = { viewport = SheetViewport(); showOptions = false }, modifier = Modifier.testTag("sheet-reset"))
                    if (sheet.frozenRows > 0 || sheet.frozenColumns > 0) DropdownMenuItem(text = { Text(stringResource(if (freeze) R.string.sheet_unfreeze else R.string.sheet_freeze)) }, onClick = { freeze = !freeze; showOptions = false }, modifier = Modifier.testTag("sheet-freeze"))
                    if (hasFallbacks) Text(stringResource(R.string.workbook_format_fallback), Modifier.widthIn(max = 280.dp).padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (showSearch) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, onValueChange = { query = it.take(256); matchIndex = 0 }, singleLine = true,
                modifier = Modifier.weight(1f).testTag("sheet-search-input"), label = { Text(stringResource(R.string.sheet_find)) })
            Text(if (matches.isEmpty()) "0" else "${matchIndex + 1}/${matches.size}${if (matches.size == 1000) "+" else ""}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { if (matches.isNotEmpty()) { matchIndex = (matchIndex - 1).mod(matches.size); matches[matchIndex].let { jump(it.row, it.column) } } }, modifier = Modifier.width(40.dp).testTag("sheet-search-previous"), contentPadding = PaddingValues(0.dp)) { Text("‹") }
            TextButton(onClick = { if (matches.isNotEmpty()) { matchIndex = (matchIndex + 1) % matches.size; matches[matchIndex].let { jump(it.row, it.column) } } }, modifier = Modifier.width(40.dp).testTag("sheet-search-next"), contentPadding = PaddingValues(0.dp)) { Text("›") }
        }
        if (window.limited) Text(stringResource(R.string.sheet_zoom_for_small_cells), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(Color.White)
            .onSizeChanged { size = it }
            .pointerInput(sheet, size) { detectTransformGestures { _, pan, zoom, _ -> transform(zoom, pan) } }
            .pointerInput(sheet, size, viewport, freeze) {
                detectTapGestures(onTap = { position ->
                    if (position.x in bodyLeft..bodyRight && position.y >= headerHeight) {
                        val px = (if (sheet.rightToLeft) bodyRight - position.x else position.x - bodyLeft) / pointsToPixels
                        val py = (position.y - headerHeight) / pointsToPixels
                        val pane = panes.lastOrNull { px >= it.left && px <= it.right && py >= it.top && py <= it.bottom } ?: return@detectTapGestures
                        val columnPoint = px + pane.scrollX
                        val rowPoint = py + pane.scrollY
                        selected = layout.placement(layout.rows.indexAt(rowPoint), layout.columns.indexAt(columnPoint))
                    }
                }, onDoubleTap = { transform(if (viewport.zoom > 1f) 1f / viewport.zoom else 2f) })
            }.testTag("worksheet-grid")) {
            Canvas(Modifier.fillMaxSize()) {
                for ((pane, visibleCells) in paneCells) {
                    val paneLeft = minOf(screenX(pane.left), screenX(pane.right))
                    val paneRight = maxOf(screenX(pane.left), screenX(pane.right))
                    val paneTop = headerHeight + (pane.top * pointsToPixels).toFloat()
                    val paneBottom = headerHeight + (pane.bottom * pointsToPixels).toFloat()
                    clipRect(paneLeft, paneTop, paneRight, paneBottom) {
                    // Paint backgrounds first, so a later empty cell cannot erase overflowing text.
                    for (placement in visibleCells) {
                        val left = xLeft(placement, pane); val right = xRight(placement, pane)
                        val top = yTop(placement, pane); val bottom = yBottom(placement, pane)
                        val cell = placement.cell
                        drawRect(cell.fillRgb?.let(::sheetColor) ?: Color.White, Offset(left, top), Size(right - left, bottom - top))
                        if (sheet.showGridLines && cell.fillRgb == null) drawRect(gridColor, Offset(left, top), Size(right - left, bottom - top), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5.dp.toPx()))
                        drawSheetEdges(cell, left, top, right, bottom, pointsToPixels)
                    }
                    for (placement in visibleCells) {
                        val left = xLeft(placement, pane); val right = xRight(placement, pane)
                        val top = yTop(placement, pane); val bottom = yBottom(placement, pane)
                        val cell = placement.cell
                        if (cell.value.isNotEmpty()) {
                            val padding = (2f * pointsToPixels).coerceAtMost((right - left) / 4)
                            val indent = cell.indent * (cell.fontSizePoints ?: 11f) * .9f * pointsToPixels
                            val horizontal = sheetTextAlignment(cell)
                            val mayOverflow = !cell.wrapText && !cell.shrinkToFit && cell.textRotation == 0 && cell.valueKind == SheetValueKind.TEXT && horizontal in listOf(TextAlign.Left, TextAlign.Right)
                            // Logical sheet direction and text direction are independent.
                            val backwards = (horizontal == TextAlign.Left && sheet.rightToLeft) || (horizontal == TextAlign.Right && !sheet.rightToLeft)
                            val end = if (mayOverflow) layout.overflowEnd(placement, backwards) else if (backwards) placement.left else placement.right
                            val extra = if (mayOverflow) (kotlin.math.abs(end - if (backwards) placement.left else placement.right) * pointsToPixels).toFloat() else 0f
                            val textLeft = if (horizontal == TextAlign.Right) left - extra else left
                            val textRight = if (horizontal == TextAlign.Left) right + extra else right
                            val width = (textRight - textLeft - 2 * padding - indent).coerceIn(1f, 16_384f).toInt()
                            val arabic=cell.value.any {Character.UnicodeScript.of(it.code)==Character.UnicodeScript.ARABIC}
                            val fontKey="${cell.fontFamilyName}/${cell.bold}/${cell.italic}/$arabic"
                            val family=families.getOrPut(fontKey) {
                                if(families.size>=128) families.clear()
                                FontFamily(DocumentFonts.typeface(context,cell.fontFamilyName,cell.bold,cell.italic,arabic))
                            }
                            var style = TextStyle(color = cell.textRgb?.let(::sheetColor) ?: Color(0xFF111827),
                                fontSize = slideFontSize(cell.fontSizePoints ?: 11f, viewport.zoom * 4f / 3f, density),
                                fontFamily = family, fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                                fontStyle = if (cell.italic) FontStyle.Italic else FontStyle.Normal,
                                textDecoration = TextDecoration.combine(listOfNotNull(TextDecoration.Underline.takeIf { cell.underline }, TextDecoration.LineThrough.takeIf { cell.strikeThrough })),
                                textDirection = if (sheetCellIsRtl(cell)) TextDirection.Rtl else TextDirection.Ltr,
                                textAlign = horizontal,platformStyle=PlatformTextStyle(includeFontPadding=false))
                            if (cell.shrinkToFit && !cell.wrapText) {
                                val natural = textMeasurer.measure(AnnotatedString(cell.value), style, softWrap = false, maxLines = 1).size.width
                                if (natural > width) style = style.copy(fontSize = style.fontSize * (width.toFloat() / natural).coerceAtLeast(.1f))
                            }
                            val text = textMeasurer.measure(AnnotatedString(cell.value), style,
                                overflow = TextOverflow.Clip, softWrap = cell.wrapText,
                                maxLines = if (cell.wrapText || '\n' in cell.value) 256 else 1,
                                constraints = Constraints(minWidth = width, maxWidth = width, maxHeight = 16_384))
                            val textX = when (horizontal) { TextAlign.Right -> textRight - padding - indent - width; TextAlign.Center -> (left + right - width) / 2; else -> textLeft + padding + indent }
                            val verticalPadding=minOf(padding,((bottom-top-text.size.height)/2).coerceAtLeast(0f))
                            val textY = when (cell.verticalAlignment) {
                                SheetVerticalAlignment.TOP -> top + verticalPadding
                                SheetVerticalAlignment.CENTER -> (top + bottom - text.size.height) / 2
                                SheetVerticalAlignment.BOTTOM -> bottom - verticalPadding - text.size.height
                            }
                            val clipLeft = textLeft.coerceAtLeast(paneLeft); val clipRight = textRight.coerceAtMost(paneRight)
                            if (clipRight > clipLeft) clipRect(clipLeft, top.coerceAtLeast(paneTop), clipRight, bottom.coerceAtMost(paneBottom)) {
                                val angle = sheetRotationDegrees(cell.textRotation)
                                if (angle == 0f) drawText(text, topLeft = Offset(textX, textY)) else rotate(angle, Offset((left + right) / 2, (top + bottom) / 2)) { drawText(text, topLeft = Offset((left + right - text.size.width) / 2, (top + bottom - text.size.height) / 2)) }
                            }
                        }
                        if (selected?.range == placement.range) drawRect(selectedColor, Offset(left, top), Size(right - left, bottom - top), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    }
                }
                }
                // Headers stay visible while document coordinates pan underneath them.
                drawRect(headerColor, Offset(0f, 0f), Size(size.width.toFloat(), headerHeight))
                val rowHeaderX = if (sheet.rightToLeft) bodyRight else 0f
                drawRect(headerColor, Offset(rowHeaderX, 0f), Size(headerWidth, size.height.toFloat()))
                val labelStyle = TextStyle(color = headerTextColor, fontSize = with(density) { 12.dp.toSp() }, textAlign = TextAlign.Center)
                val frozenWidth = panes.minOfOrNull { it.right }?.takeIf { panes.any { p -> p.left > 0 } } ?: 0.0
                val frozenHeight = panes.minOfOrNull { it.bottom }?.takeIf { panes.any { p -> p.top > 0 } } ?: 0.0
                if (frozenWidth > 0) drawLine(headerTextColor.copy(alpha=.55f), Offset(screenX(frozenWidth), headerHeight), Offset(screenX(frozenWidth), size.height.toFloat()), 1.dp.toPx())
                if (frozenHeight > 0) drawLine(headerTextColor.copy(alpha=.55f), Offset(bodyLeft, headerHeight + (frozenHeight * pointsToPixels).toFloat()), Offset(bodyRight, headerHeight + (frozenHeight * pointsToPixels).toFloat()), 1.dp.toPx())
                val headerColumns = (layout.columns.visible(0.0, frozenWidth) + layout.columns.visible(viewport.x + frozenWidth, viewport.x + bodyWidth / pointsToPixels)).distinct()
                for (column in headerColumns) {
                    val dummy = SheetPlacement(NativeMergedRange(0, column, column), sheet.defaultCell, layout.columns.offset(column), 0.0, layout.columns.offset(column + 1), 0.0)
                    val frozen = dummy.left < frozenWidth
                    val pane = SheetPane(0.0, 0.0, 0.0, 0.0, if (frozen) 0.0 else viewport.x, 0.0)
                    val left = xLeft(dummy, pane); val right = xRight(dummy, pane)
                    val label = textMeasurer.measure(AnnotatedString(sheetColumnLabel(column)), labelStyle, constraints = Constraints(minWidth = (right - left).coerceIn(1f, 16_384f).toInt(), maxWidth = (right - left).coerceIn(1f, 16_384f).toInt()), maxLines = 1)
                    val start = screenX(if (frozen) 0.0 else frozenWidth); val end = screenX(if (frozen) frozenWidth else bodyWidth / pointsToPixels.toDouble())
                    clipRect(minOf(start, end), 0f, maxOf(start, end), headerHeight) { drawText(label, topLeft = Offset(left, (headerHeight - label.size.height) / 2)) }
                }
                val headerRows = (layout.rows.visible(0.0, frozenHeight) + layout.rows.visible(viewport.y + frozenHeight, viewport.y + bodyHeight / pointsToPixels)).distinct()
                for (row in headerRows) {
                    val frozen = layout.rows.offset(row) < frozenHeight
                    val scroll = if (frozen) 0.0 else viewport.y
                    val top = headerHeight + ((layout.rows.offset(row) - scroll) * pointsToPixels).toFloat()
                    val bottom = headerHeight + ((layout.rows.offset(row + 1) - scroll) * pointsToPixels).toFloat()
                    val label = textMeasurer.measure(AnnotatedString((row + 1).toString()), labelStyle, constraints = Constraints(minWidth = headerWidth.toInt(), maxWidth = headerWidth.toInt()), maxLines = 1)
                    clipRect(rowHeaderX, headerHeight + (if (frozen) 0f else (frozenHeight * pointsToPixels).toFloat()), rowHeaderX + headerWidth, if (frozen) headerHeight + (frozenHeight * pointsToPixels).toFloat() else size.height.toFloat()) { drawText(label, topLeft = Offset(rowHeaderX, (top + bottom - label.size.height) / 2)) }
                }
            }
            // Expose every visible nonempty cell to accessibility, including merged origins offscreen.
            paneCells.forEach { (pane, visibleCells) -> visibleCells.filter { it.cell.value.isNotEmpty() }.forEach { placement ->
                val paneLeft = minOf(screenX(pane.left), screenX(pane.right)); val paneRight = maxOf(screenX(pane.left), screenX(pane.right))
                val left = xLeft(placement, pane).coerceAtLeast(paneLeft); val top = yTop(placement, pane).coerceAtLeast(headerHeight + (pane.top * pointsToPixels).toFloat())
                val right = xRight(placement, pane).coerceAtMost(paneRight); val bottom = yBottom(placement, pane).coerceAtMost(headerHeight + (pane.bottom * pointsToPixels).toFloat())
                if (right > left && bottom > top) Box(Modifier.absoluteOffset(with(density) { left.toDp() }, with(density) { top.toDp() })
                    .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
                    .semantics { contentDescription = "${placement.address}: ${placement.cell.value}"; onClick { selected = placement; showCell = true; true } })
            } }
        }
        HorizontalDivider()
        TextButton(onClick = { showCell = true }, enabled = selected != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("sheet-selected-cell")) {
            Text(selected?.let { "${it.address}: ${it.cell.value}" } ?: stringResource(R.string.sheet_select_cell),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if (showCell && selected != null) AlertDialog(onDismissRequest = { showCell = false },
        title = { Text(selected!!.address) }, text = { SelectionContainer { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text(selected!!.cell.value)
            selected!!.cell.formula?.let { formula -> HorizontalDivider(Modifier.padding(vertical = 12.dp)); Text(stringResource(R.string.sheet_saved_formula), style = MaterialTheme.typography.labelSmall); Text("=$formula") }
        } } },
        confirmButton = { TextButton(onClick = { showCell = false }) { Text(stringResource(R.string.close)) } })
    if (showJump) AlertDialog(onDismissRequest = { showJump = false }, title = { Text(stringResource(R.string.sheet_go_to)) },
        text = { OutlinedTextField(jumpAddress, onValueChange = { jumpAddress = it.take(16); jumpInvalid = false },
            modifier = Modifier.testTag("sheet-jump-address"), singleLine = true, isError = jumpInvalid,
            label = { Text("A1") }, supportingText = { if (jumpInvalid) Text(stringResource(R.string.sheet_bad_address)) }) },
        confirmButton = { TextButton(onClick = {
            val address = sheetAddress(jumpAddress)
            val destination = address?.let { layout.placement(it.first, it.second) }
            if (destination == null) jumpInvalid = true else {
                jump(address.first, address.second)
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
