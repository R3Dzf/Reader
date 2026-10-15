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
import androidx.compose.foundation.layout.height
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

    fun aspectRatio(index: Int): Float = synchronized(renderLock) {
        val page=renderer.openPage(index)
        try { page.width.toFloat()/page.height.coerceAtLeast(1) } finally { page.close() }
    }

    fun renderPage(index: Int, requestedWidth: Int): Bitmap = synchronized(renderLock) {
        val page = renderer.openPage(index)
        try {
            val scale = min(
                min(requestedWidth.toFloat() / page.width, 2600f / page.width),
                2600f / page.height
            ).coerceAtLeast(0.000001f)
            val width = (page.width * scale).toInt().coerceIn(1,2600)
            val height = (page.height * scale).toInt().coerceIn(1,2600)
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

    ReaderPages(displayName,handle.renderer.pageCount,"pdf",initialProgress,onProgress,onClose) {pageIndex,continuous,reset,onTap,onInteraction,onZoom ->
        PdfPage(handle,pageIndex,continuous,reset,onTap,onInteraction,onZoom)
    }
}

@Composable
private fun PdfPage(handle: PdfRenderHandle,index: Int,continuous: Boolean,reset: Int,onTap: ()->Unit,onInteraction: ()->Unit,onZoom: (Float)->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().then(if(continuous) Modifier else Modifier.fillMaxSize())) {
        val ratio=remember(handle,index) {runCatching {handle.aspectRatio(index)}.getOrDefault(.75f).coerceAtLeast(.001f)}
        val frame=if(continuous) Modifier.fillMaxWidth().height(minOf(maxWidth/ratio,4000.dp)) else Modifier.fillMaxSize()
        ZoomableReaderPage(ratio,frame,reset,"pdf-page-$index",onTap,onInteraction,onZoom) {zoom ->
            PdfPageBitmap(handle,index,zoom)
        }
    }
}

@Composable
private fun PdfPageBitmap(handle: PdfRenderHandle,index: Int,zoom: Float) {
    BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.White)) {
        val density=LocalDensity.current
        val bucket=when {zoom<=1.05f -> 1;zoom<=2.1f -> 2;else -> 4}
        val width=(with(density) {maxWidth.roundToPx()}*bucket).coerceIn(720,2600)
        var bitmap by remember(handle,index) {mutableStateOf<Bitmap?>(null)}
        var failed by remember(handle,index) {mutableStateOf(false)}
        LaunchedEffect(handle,index,width) {
            val result=withContext(Dispatchers.IO) {runCatching {handle.renderPage(index,width)}}
            bitmap=result.getOrNull();failed=bitmap==null
        }
        when {
            bitmap!=null -> Image(bitmap!!.asImageBitmap(),stringResource(R.string.page_number,index+1),Modifier.fillMaxSize().testTag("pdf-page-image-$index"),contentScale=ContentScale.Fit)
            failed -> Text(stringResource(R.string.pdf_failed),Modifier.padding(24.dp))
            else -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {CircularProgressIndicator()}
        }
    }
}
