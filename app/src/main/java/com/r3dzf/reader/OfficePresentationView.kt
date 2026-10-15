package com.r3dzf.reader


import androidx.core.graphics.withTranslation
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import kotlin.math.roundToInt

@Composable
internal fun PresentationDocumentView(presentation: NativePresentation) {
    Column(Modifier.fillMaxSize().background(Color(0xFFECEEF2))) {
        Text(
            stringResource(R.string.presentation_gesture_hint),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            items(presentation.slides, key = { it.number }) { slide ->
                ZoomableSlide(presentation, slide)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ZoomableSlide(presentation: NativePresentation, slide: NativeSlide) {
    var zoom by rememberSaveable(slide.number) { mutableFloatStateOf(1f) }
    var panX by rememberSaveable(slide.number) { mutableFloatStateOf(0f) }
    var panY by rememberSaveable(slide.number) { mutableFloatStateOf(0f) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    fun transform(zoomChange: Float = 1f, x: Float = 0f, y: Float = 0f) {
        val next = SlideViewport(zoom, panX, panY).transformed(
            zoomChange, x, y, viewportSize.width.toFloat(), viewportSize.height.toFloat()
        )
        zoom = next.scale
        panX = next.translationX
        panY = next.translationY
    }
    val gestures = rememberTransformableState { zoomChange, offsetChange, _ ->
        transform(zoomChange, offsetChange.x, offsetChange.y)
    }
    val zoomInLabel = stringResource(R.string.zoom_in)
    val zoomOutLabel = stringResource(R.string.zoom_out)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.slide_number, slide.number),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium
            )
            TextButton(
                onClick = { transform(1f / 1.5f) }, enabled = zoom > 1f,
                modifier = Modifier.semantics { contentDescription = zoomOutLabel }
            ) { Text("−") }
            Text(stringResource(R.string.zoom_percent, (zoom * 100).roundToInt()), style = MaterialTheme.typography.labelSmall)
            TextButton(
                onClick = { transform(1.5f) }, enabled = zoom < 5f,
                modifier = Modifier.semantics { contentDescription = zoomInLabel }
            ) { Text("+") }
            TextButton(onClick = { transform(1f / zoom) }) { Text(stringResource(R.string.fit_slide)) }
        }
        Box(
            Modifier.widthIn(max = 720.dp).fillMaxWidth()
                .aspectRatio(presentation.slideWidthPoints / presentation.slideHeightPoints)
                .background(Color.White).clipToBounds()
                .onSizeChanged { viewportSize = it; transform() }
                .transformable(gestures, canPan = { zoom > 1f })
                .pointerInput(slide.number) {
                    detectTapGestures(onDoubleTap = { transform(if (zoom > 1f) 1f / zoom else 2f) })
                }
        ) {
            // Office coordinates are physical positions measured from the left, even in an Arabic UI.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val imageScale = when { zoom <= 1f -> 1f; zoom <= 2f -> 2f; zoom <= 4f -> 4f; else -> 5f }
                SlideContent(presentation, slide, imageScale, Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                })
            }
        }
    }
}

@Composable
internal fun SlideContent(presentation: NativePresentation, slide: NativeSlide, imageScale: Float, modifier: Modifier) {
    val background = slide.backgroundRgb?.takeIf { slide.backgroundFill == null }?.let { Color((0xFF000000L or it).toInt()) } ?: Color.White
    BoxWithConstraints(modifier.background(background).drawBehind { slide.backgroundFill?.let { drawSlideFill(it) } }.clipToBounds().testTag("slide-${slide.number}")) {
        val scale = maxWidth.value / presentation.slideWidthPoints
        val density = LocalDensity.current
        slide.shapes.forEach { shape ->
            val width = maxWidth * shape.widthFraction
            val height = maxHeight * shape.heightFraction
            val unsupportedLabel = when (shape.kind) {
                NativeSlideShapeKind.IMAGE -> if (shape.imageBytes == null) R.string.presentation_image_unavailable else null
                NativeSlideShapeKind.TABLE -> if (shape.table == null) R.string.presentation_table_unavailable else null
                NativeSlideShapeKind.OTHER -> R.string.presentation_element_unavailable
                else -> null
            }
            Box(
                Modifier.absoluteOffset(x = maxWidth * shape.xFraction, y = maxHeight * shape.yFraction)
                    .width(if (shape.geometry == "line" && width < 1.dp) 1.dp else width).height(if (shape.geometry == "line" && height < 1.dp) 1.dp else height)
                    .graphicsLayer {
                        rotationZ = shape.rotationDegrees
                        scaleX = if (shape.flipHorizontal) -1f else 1f
                        scaleY = if (shape.flipVertical) -1f else 1f
                    }
                    .drawWithContent {
                        val canvas = drawContext.canvas.nativeCanvas
                        val content = this
                        val centerX = size.width/2
                        val centerY = size.height/2
                        canvas.withTranslation(centerX,centerY) {
                            skew(shape.shearX,0f)
                            withTranslation(-centerX,-centerY) { content.drawContent() }
                        }
                    }
                    .drawBehind { drawSlideShape(shape, scale) }
                    .then(if (unsupportedLabel != null) Modifier.border(1.dp, Color(0xFFB65A28)) else Modifier)
                    .clipToBounds()
                    .then(if (shape.table != null || shape.imageBytes != null) Modifier else Modifier.padding(
                        start = (shape.textBox.left * scale).dp, end = (shape.textBox.right * scale).dp,
                        top = (shape.textBox.top * scale).dp, bottom = (shape.textBox.bottom * scale).dp
                    )),
                contentAlignment = when (shape.textBox.vertical) {
                    SlideCellVerticalAlignment.TOP -> Alignment.TopStart
                    SlideCellVerticalAlignment.CENTER -> Alignment.CenterStart
                    SlideCellVerticalAlignment.BOTTOM -> Alignment.BottomStart
                }
            ) {
                when {
                    shape.table != null -> SlideTableView(shape.table, scale)
                    shape.paragraphs.isNotEmpty() -> SlideTextBlock(shape.paragraphs, scale, 14f, Modifier.fillMaxWidth())
                    shape.text != null -> Text(
                        shape.text, modifier = Modifier.fillMaxSize(),
                        color = shape.textRgb?.let { Color((0xFF000000L or it).toInt()) } ?: Color(0xFF202124),
                        fontSize = slideFontSize(shape.fontSizePoints ?: 14f, scale, density),
                        fontWeight = if (shape.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (shape.italic) FontStyle.Italic else FontStyle.Normal,
                        textAlign = if (shape.centered) TextAlign.Center else TextAlign.Start,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textDirection = if (shape.rightToLeft) TextDirection.Rtl else TextDirection.ContentOrRtl
                        )
                    )
                    shape.imageBytes != null -> OfficeImageView(
                        shape.imageBytes, width.value * imageScale, height.value * imageScale, null, shape.imageCrop
                    )
                    unsupportedLabel != null -> Text(
                        stringResource(unsupportedLabel), color = Color(0xFF8B3E1A),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun SlideTableView(table: NativeSlideTable, scale: Float) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        table.placedCells.forEach { placement ->
            val cell = placement.cell
            val fill = cell.fillRgb?.let { Color((0xFF000000L or it).toInt()) }
            Box(
                Modifier.absoluteOffset(maxWidth * placement.xFraction, maxHeight * placement.yFraction)
                    .width(maxWidth * placement.widthFraction).height(maxHeight * placement.heightFraction)
                    .then(if (fill != null) Modifier.background(fill) else Modifier)
                    .clipToBounds().drawBehind { drawSlideCellBorders(cell, scale) }
                    .padding(
                        start = (cell.marginLeftPoints * scale).dp, end = (cell.marginRightPoints * scale).dp,
                        top = (cell.marginTopPoints * scale).dp, bottom = (cell.marginBottomPoints * scale).dp
                    ),
                contentAlignment = when (cell.verticalAlignment) {
                    SlideCellVerticalAlignment.TOP -> Alignment.TopStart
                    SlideCellVerticalAlignment.CENTER -> Alignment.CenterStart
                    SlideCellVerticalAlignment.BOTTOM -> Alignment.BottomStart
                }
            ) {
                SlideTextBlock(cell.paragraphs, scale, 10f, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SlideTextBlock(paragraphs: List<NativeSlideTextParagraph>, scale: Float, fallbackSize: Float, modifier: Modifier) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val families = remember(paragraphs, context) {
        paragraphs.flatMap { it.runs + it.defaultStyle }.distinct().associateWith { run ->
            FontFamily(DocumentFonts.typeface(context, run.fontFamilyName, run.bold, run.italic,
                run.text.codePoints().anyMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.ARABIC }))
        }
    }
    fun size(points: Float?) = slideFontSize(points ?: fallbackSize, scale, density)
    Column(modifier) {
        paragraphs.forEach { paragraph ->
            val text = buildAnnotatedString {
                paragraph.bulletMarker?.let { append(it); append(" ") }
                paragraph.runs.forEach { run ->
                    val decorations = listOfNotNull(
                        TextDecoration.Underline.takeIf { run.underline },
                        TextDecoration.LineThrough.takeIf { run.strikeThrough }
                    )
                    withStyle(SpanStyle(
                        color = run.textRgb?.let { Color((0xFF000000L or it).toInt()) } ?: Color.Unspecified,
                        fontSize = size(run.fontSizePoints),
                        fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
                        fontFamily = families[run],
                        textDecoration = if (decorations.isEmpty()) TextDecoration.None else TextDecoration.combine(decorations)
                    )) { append(run.text) }
                }
                if (length == 0) append(" ")
            }
            Text(
                text,
                modifier = Modifier.fillMaxWidth().padding(
                    top = (paragraph.spaceBeforePoints * scale).dp,
                    bottom = (paragraph.spaceAfterPoints * scale).dp
                ),
                color = paragraph.defaultStyle.textRgb?.let { Color((0xFF000000L or it).toInt()) } ?: Color(0xFF202124),
                fontSize = size(paragraph.defaultStyle.fontSizePoints),
                fontFamily = families[paragraph.defaultStyle],
                textAlign = when (paragraph.alignment) {
                    SlideParagraphAlignment.START -> TextAlign.Start
                    SlideParagraphAlignment.LEFT -> TextAlign.Left
                    SlideParagraphAlignment.CENTER -> TextAlign.Center
                    SlideParagraphAlignment.RIGHT -> TextAlign.Right
                    SlideParagraphAlignment.JUSTIFY -> TextAlign.Justify
                },
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = paragraph.lineSpacingPoints?.let { size(it) }
                        ?: paragraph.lineSpacingMultiplier?.let { multiplier -> size((paragraph.runs.maxOfOrNull { it.fontSizePoints ?: fallbackSize } ?: fallbackSize) * multiplier) }
                        ?: TextUnit.Unspecified,
                    textIndent = TextIndent(
                        firstLine = slideFontSize((paragraph.marginStartPoints + paragraph.indentPoints).coerceAtLeast(0f), scale, density),
                        restLine = slideFontSize(paragraph.marginStartPoints, scale, density)
                    ),
                    textDirection = if (paragraph.rightToLeft) TextDirection.Rtl else TextDirection.Ltr
                )
            )
        }
    }
}

private fun DrawScope.drawSlideCellBorders(cell: NativeSlideTableCell, scale: Float) {
    fun edge(border: NativeSlideCellBorder?, side: Int) {
        val stroke = minOf(
            if (border == null) 0.5.dp.toPx() else (border.widthPoints * scale).dp.toPx(),
            size.width, size.height
        )
        if (stroke <= 0f) return
        val inset = stroke / 2f
        val (start, end) = when (side) {
            0 -> Offset(inset, 0f) to Offset(inset, size.height)
            1 -> Offset(size.width - inset, 0f) to Offset(size.width - inset, size.height)
            2 -> Offset(0f, inset) to Offset(size.width, inset)
            else -> Offset(0f, size.height - inset) to Offset(size.width, size.height - inset)
        }
        val path = when (border?.dash) {
            SlideBorderDash.DASH -> PathEffect.dashPathEffect(floatArrayOf(stroke * 4, stroke * 3))
            SlideBorderDash.DOT -> PathEffect.dashPathEffect(floatArrayOf(stroke, stroke * 2))
            else -> null
        }
        drawLine(Color((0xFF000000L or (border?.colorRgb ?: 0x98A2B3L)).toInt()), start, end, stroke, pathEffect = path)
    }
    edge(cell.borderLeft, 0)
    edge(cell.borderRight, 1)
    edge(cell.borderTop, 2)
    edge(cell.borderBottom, 3)
}

private fun DrawScope.fillBrush(fill: SlideFill): Brush {
    if (fill.stops.isEmpty()) return Brush.linearGradient(listOf(
        Color((0xFF000000L or (fill.rgb ?: 0xFFFFFF)).toInt()).copy(alpha = fill.alpha),
        Color((0xFF000000L or (fill.rgb ?: 0xFFFFFF)).toInt()).copy(alpha = fill.alpha)))
    val angle = Math.toRadians(fill.angleDegrees.toDouble())
    val dx = kotlin.math.cos(angle).toFloat(); val dy = kotlin.math.sin(angle).toFloat()
    val length = kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)
    val center = Offset(size.width / 2, size.height / 2)
    val vector = Offset(dx * length / 2, dy * length / 2)
    val stops = fill.stops.map { it.position to Color((0xFF000000L or it.rgb).toInt()).copy(alpha=it.alpha * fill.alpha) }.toTypedArray()
    return Brush.linearGradient(*stops, start=center-vector, end=center+vector)
}

private fun DrawScope.drawSlideFill(fill: SlideFill) {
    if (fill.alpha > 0f && (fill.rgb != null || fill.stops.isNotEmpty())) drawRect(fillBrush(fill))
}

private fun DrawScope.drawSlideShape(shape: NativeSlideShape, scale: Float) {
    val fill = shape.fill ?: shape.fillRgb?.let { SlideFill(it) }
    val geometry = shape.geometry
    val path = Path().apply {
        when (geometry) {
            "ellipse" -> addOval(androidx.compose.ui.geometry.Rect(Offset.Zero,size))
            "roundRect" -> addRoundRect(androidx.compose.ui.geometry.RoundRect(0f,0f,size.width,size.height,
                androidx.compose.ui.geometry.CornerRadius(minOf(size.width,size.height)*0.16f)))
            "triangle" -> { moveTo(size.width/2,0f); lineTo(size.width,size.height); lineTo(0f,size.height); close() }
            "rtTriangle" -> { moveTo(0f,0f); lineTo(size.width,size.height); lineTo(0f,size.height); close() }
            "diamond" -> { moveTo(size.width/2,0f); lineTo(size.width,size.height/2); lineTo(size.width/2,size.height); lineTo(0f,size.height/2); close() }
            "line" -> { moveTo(0f,0f); lineTo(size.width,size.height) }
            else -> addRect(androidx.compose.ui.geometry.Rect(Offset.Zero,size))
        }
    }
    if (geometry != "line" && fill != null && fill.alpha > 0f && (fill.rgb != null || fill.stops.isNotEmpty())) drawPath(path,fillBrush(fill))
    shape.outline?.takeIf { it.widthPoints > 0f }?.let { border ->
        val width = (border.widthPoints * scale).dp.toPx()
        val effect = when (border.dash) {
            SlideBorderDash.DASH -> PathEffect.dashPathEffect(floatArrayOf(width*4,width*3))
            SlideBorderDash.DOT -> PathEffect.dashPathEffect(floatArrayOf(width,width*2))
            else -> null
        }
        drawPath(path,Color((0xFF000000L or (border.colorRgb ?: 0L)).toInt()),style=Stroke(width,pathEffect=effect))
    }
}
