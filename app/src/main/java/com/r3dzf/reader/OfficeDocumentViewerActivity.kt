package com.r3dzf.reader

import android.graphics.BitmapFactory
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

class OfficeDocumentViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val format = intent.getStringExtra(EXTRA_FORMAT)?.let { ext ->
            OfficeFormat.entries.firstOrNull { it.extension == ext }
        }
        val file = runCatching { path?.let { File(it).canonicalFile } }.getOrNull()
        val allowedDirectory = File(cacheDir, "office-input").canonicalFile
        if (file == null || format == null || !file.isFile ||
            !file.path.startsWith(allowedDirectory.path + File.separator)
        ) {
            finish()
            return
        }

        setContent {
            ReaderTheme {
                NativeOfficeViewer(file, name.ifBlank { file.name }, format, ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_PATH = "reader.office.path"
        private const val EXTRA_NAME = "reader.office.name"
        private const val EXTRA_FORMAT = "reader.office.format"

        fun intent(context: Context, document: PreparedOfficeDocument): Intent =
            Intent(context, OfficeDocumentViewerActivity::class.java)
                .putExtra(EXTRA_PATH, document.file.absolutePath)
                .putExtra(EXTRA_NAME, document.displayName)
                .putExtra(EXTRA_FORMAT, document.format.extension)
    }
}

@Composable
private fun NativeOfficeViewer(file: File, displayName: String, format: OfficeFormat, onClose: () -> Unit) {
    var document by remember(file.path) { mutableStateOf<NativeWordDocument?>(null) }
    var workbook by remember(file.path) { mutableStateOf<NativeWorkbook?>(null) }
    var presentation by remember(file.path) { mutableStateOf<NativePresentation?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    var formatPending by remember(file.path) { mutableStateOf(false) }
    var invalidDocument by remember(file.path) { mutableStateOf(false) }
    var loaded by remember(file.path) { mutableStateOf(false) }

    LaunchedEffect(file.path, format) {
        runCatching {
            withContext(Dispatchers.IO) {
                NativeOfficeEngine.open(file, format)
            }
        }.onSuccess { result ->
                when (result) {
                    is NativeWordDocument -> document = result
                    is NativeWorkbook -> workbook = result
                    is NativePresentation -> presentation = result
                }
                loaded = true
            }
            .onFailure {
                if (it is OfficeFormatNotImplementedException) formatPending = true
                else if (it is InvalidOfficeDocumentException) invalidDocument = true
                else error = it.message ?: "Could not read this document."
            }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(format.extension.uppercase(), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        }

        when {
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            formatPending -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.office_format_roadmap, format.extension.uppercase()))
            }
            invalidDocument -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.office_document_invalid), color = MaterialTheme.colorScheme.error)
            }
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            document != null -> WordDocumentView(document!!)
            workbook != null -> WorkbookDocumentView(workbook!!)
            presentation != null -> PresentationDocumentView(presentation!!)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.office_empty_document))
            }
        }
    }
}

@Composable
internal fun OfficeImageView(
    bytes: ByteArray,
    targetWidthDp: Float,
    targetHeightDp: Float,
    description: String?,
    crop: SlideImageCrop? = null
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val rawWidth = (targetWidthDp * density).toInt().coerceIn(1, 4096)
    val rawHeight = (targetHeightDp * density).toInt().coerceIn(1, 4096)
    val scale = minOf(1f, sqrt(6_000_000f / (rawWidth.toFloat() * rawHeight)))
    val targetWidth = (rawWidth * scale).toInt().coerceAtLeast(1)
    val targetHeight = (rawHeight * scale).toInt().coerceAtLeast(1)
    val image = produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        null,
        bytes,
        targetWidth,
        targetHeight
    ) {
        value = withContext(Dispatchers.IO) { decodeOfficeImage(bytes, targetWidth, targetHeight) }
    }.value
    if (image == null) {
        Text(stringResource(R.string.office_image_unreadable), style = MaterialTheme.typography.labelSmall)
    } else {
        androidx.compose.foundation.Image(
            bitmap = image,
            contentDescription = description,
            modifier = Modifier.fillMaxSize().then(if (crop == null) Modifier else Modifier.graphicsLayer {
                val horizontal = (1f - crop.left - crop.right).coerceAtLeast(0.01f)
                val vertical = (1f - crop.top - crop.bottom).coerceAtLeast(0.01f)
                scaleX = 1f / horizontal
                scaleY = 1f / vertical
                translationX = (crop.right - crop.left) * size.width / (2 * horizontal)
                translationY = (crop.bottom - crop.top) * size.height / (2 * vertical)
            }),
            contentScale = if (crop == null) ContentScale.Fit else ContentScale.FillBounds
        )
    }
}

private fun decodeOfficeImage(bytes: ByteArray, targetWidth: Int, targetHeight: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (
        sampleSize < 32_768 &&
        (bounds.outWidth / sampleSize > targetWidth || bounds.outHeight / sampleSize > targetHeight)
    ) {
        sampleSize *= 2
    }
    return runCatching {
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        )?.asImageBitmap()
    }.getOrNull()
}

@Composable
internal fun WordDocumentView(document: NativeWordDocument) {
    if (document.blocks.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.office_empty_document))
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color(0xFFECEEF2)).testTag("word-document"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Box(
                Modifier.fillMaxWidth()
                    .widthIn(max = document.pageSettings.widthPoints.dp)
                    .background(Color.White)
                    .padding(top = document.pageSettings.marginTopPoints.dp)
            )
        }
        itemsIndexed(document.blocks) { _, block ->
            Column(
                Modifier.fillMaxWidth()
                    .widthIn(max = document.pageSettings.widthPoints.dp)
                    .background(Color.White)
                    .padding(
                        start = document.pageSettings.marginLeftPoints.dp,
                        end = document.pageSettings.marginRightPoints.dp
                    )
            ) {
                when (block) {
                    is WordParagraph -> {
                        if (block.startsNewPage) WordPageBreakMarker()
                        WordParagraphView(block)
                        if (block.endsPage) WordPageBreakMarker()
                    }
                    is WordTable -> WordTableView(block)
                }
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth()
                    .widthIn(max = document.pageSettings.widthPoints.dp)
                    .background(Color.White)
                    .padding(bottom = document.pageSettings.marginBottomPoints.dp)
            )
        }
    }
}

@Composable
private fun WordParagraphView(paragraph: WordParagraph) {
    val content = remember(paragraph) {
        buildAnnotatedString {
            paragraph.runs.forEach { run ->
                val color = run.colorRgb?.let { Color((0xFF000000L or it).toInt()) }
                val style = SpanStyle(
                    fontWeight = if (run.bold) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
                    textDecoration = if (run.underline) TextDecoration.Underline else TextDecoration.None,
                    fontSize = run.fontSizePoints?.let { (it * 4f / 3f).sp } ?: 16.sp,
                    color = color ?: Color.Unspecified
                )
                pushStyle(style)
                append(run.text)
                pop()
            }
        }
    }
    val textAlign = when (paragraph.alignment) {
        WordAlignment.START -> TextAlign.Start
        WordAlignment.CENTER -> TextAlign.Center
        WordAlignment.END -> TextAlign.End
        WordAlignment.JUSTIFY -> TextAlign.Justify
    }
    val headingStyle = when (paragraph.headingLevel) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.bodyLarge
    }
    val shownContent = if (content.isEmpty()) AnnotatedString(" ") else content
    val textStyle = headingStyle.copy(
            textAlign = textAlign,
            textDirection = if (paragraph.rightToLeft) TextDirection.Rtl else TextDirection.ContentOrRtl
        )
    val paragraphModifier = Modifier.padding(top = paragraph.spaceBeforePoints.dp, bottom = paragraph.spaceAfterPoints.dp)
    Column(paragraphModifier.fillMaxWidth()) {
        if (paragraph.listMarker == null) {
            Text(shownContent, modifier = Modifier.fillMaxWidth(), style = textStyle)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = (paragraph.listLevel * 16).dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(paragraph.listMarker, style = textStyle)
                Text(shownContent, modifier = Modifier.weight(1f), style = textStyle)
            }
        }
        paragraph.images.forEach { image ->
            val aspectRatio = (image.widthPoints / image.heightPoints).coerceIn(0.15f, 8f)
            BoxWithConstraints(
                Modifier.fillMaxWidth().aspectRatio(aspectRatio).heightIn(max = 600.dp)
            ) {
                OfficeImageView(
                    bytes = image.imageBytes,
                    targetWidthDp = maxWidth.value,
                    targetHeightDp = maxHeight.value,
                    description = image.description
                )
            }
        }
    }
}

@Composable
private fun WordPageBreakMarker() {
    Text(
        stringResource(R.string.document_page_break),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

@Composable
private fun WordTableView(table: WordTable) {
    Column(Modifier.fillMaxWidth()) {
        table.rows.forEach { cells ->
            if (cells.isNotEmpty()) {
                Row(Modifier.fillMaxWidth()) {
                    cells.forEach { cell ->
                        Column(
                            Modifier.weight(1f)
                                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = cell.text,
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.ContentOrRtl)
                            )
                            cell.images.forEach { image ->
                                val aspectRatio = (image.widthPoints / image.heightPoints).coerceIn(0.15f, 8f)
                                BoxWithConstraints(
                                    Modifier.fillMaxWidth().aspectRatio(aspectRatio).heightIn(max = 240.dp)
                                ) {
                                    OfficeImageView(
                                        bytes = image.imageBytes,
                                        targetWidthDp = maxWidth.value,
                                        targetHeightDp = maxHeight.value,
                                        description = image.description
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

