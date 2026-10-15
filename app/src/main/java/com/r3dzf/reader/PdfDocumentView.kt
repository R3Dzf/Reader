package com.r3dzf.reader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

private class PdfRenderHandle(file: File) {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try { PdfRenderer(descriptor) } catch(error: Exception) { descriptor.close();throw error }
    private val renderLock = Any()

    fun renderPage(index: Int, requestedWidth: Int): Bitmap = synchronized(renderLock) {
        val page = renderer.openPage(index)
        try {
            val scale = min(
                min(requestedWidth.toFloat() / page.width, 1.8f),
                2600f / page.height
            ).coerceAtLeast(0.5f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    fun close() = synchronized(renderLock) {
        runCatching { renderer.close() }
        runCatching { descriptor.close() };Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfDocumentView(file: File, displayName: String = file.name, initialProgress: ReadingProgress = ReadingProgress(), onProgress: (ReadingProgress) -> Unit = {}, onClose: () -> Unit) {
    val handle = remember(file) { runCatching { PdfRenderHandle(file) }.getOrNull() }
    DisposableEffect(handle) {
        onDispose { handle?.close() }
    }

    if (handle == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.pdf_failed), modifier = Modifier.padding(24.dp))
        }
        return
    }

    val listState=rememberLazyListState(initialProgress.index.coerceIn(0,(handle.renderer.pageCount-1).coerceAtLeast(0)),initialProgress.offset)
    ObserveReadingProgress(listState,handle.renderer.pageCount,onProgress)
    Scaffold(
        topBar = { DocumentTopBar(displayName,pluralStringResource(R.plurals.page_count,handle.renderer.pageCount,handle.renderer.pageCount),onClose) }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(padding).testTag("pdf-list"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items((0 until handle.renderer.pageCount).toList()) { pageIndex ->
                PdfPage(handle = handle, index = pageIndex)
            }
        }
    }
}

@Composable
private fun PdfPage(handle: PdfRenderHandle, index: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val targetWidth = with(density) { maxWidth.roundToPx() }.coerceIn(720, 1800)
        var bitmap by remember(handle, index, targetWidth) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(handle, index, targetWidth) { mutableStateOf(false) }

        LaunchedEffect(handle, index, targetWidth) {
            val result = withContext(Dispatchers.IO) {
                runCatching { handle.renderPage(index, targetWidth) }
            }
            bitmap = result.getOrNull()
            failed = bitmap == null
        }

        Surface(
            modifier = Modifier.fillMaxWidth().testTag("pdf-page-$index"),
            color = androidx.compose.ui.graphics.Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp
        ) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.page_number, index + 1),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).testTag("pdf-page-image-$index"),
                    contentScale = ContentScale.FillWidth
                )
                failed -> Text(
                    stringResource(R.string.pdf_failed),
                    modifier = Modifier.padding(24.dp)
                )
                else -> Row(
                    modifier = Modifier.fillMaxWidth().padding(36.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
            }
        }
    }
}
