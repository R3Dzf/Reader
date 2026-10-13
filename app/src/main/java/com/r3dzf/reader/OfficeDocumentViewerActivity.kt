package com.r3dzf.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

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
        if (file == null || format == null || !format.supportedOffline || !file.isFile ||
            !file.path.startsWith(allowedDirectory.path + File.separator)
        ) {
            finish()
            return
        }

        setContent {
            ReaderTheme {
                OfficeDocumentViewerScreen(
                    file = file,
                    displayName = name.ifBlank { file.name },
                    format = format,
                    onClose = ::finish
                )
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
private fun OfficeDocumentViewerScreen(
    file: File,
    displayName: String,
    format: OfficeFormat,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var rendererDirectory by remember(file.path) { mutableStateOf<File?>(null) }
    var loadingError by remember(file.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.path) {
        runCatching { withContext(Dispatchers.IO) { OfficeRendererAssets.ensureExtracted(context) } }
            .onSuccess { rendererDirectory = it }
            .onFailure { loadingError = it.message ?: context.getString(R.string.office_engine_failed) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(format.extension.uppercase(), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            }
        }

        when {
            loadingError != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(loadingError.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            rendererDirectory == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.office_engine_loading))
                }
            }
            else -> {
                val engineRoot = rendererDirectory ?: return
                AndroidView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    factory = { viewContext -> createOfficeWebView(viewContext, engineRoot, file, format) },
                    onRelease = { webView ->
                        webView.stopLoading()
                        webView.destroy()
                    }
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createOfficeWebView(
    context: Context,
    rendererRoot: File,
    documentFile: File,
    format: OfficeFormat
): WebView {
    val token = UUID.randomUUID().toString()
    val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(APP_ASSET_DOMAIN)
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/renderer/", RendererPathHandler(rendererRoot))
        .addPathHandler("/document/", DocumentPathHandler(documentFile, token))
        .build()

    return WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        setBackgroundColor(0xFFECEEF2.toInt())
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                assetLoader.shouldInterceptRequest(request.url) ?: blockedResponse()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != APP_ASSET_DOMAIN
        }
        loadUrl("https://$APP_ASSET_DOMAIN/assets/office-viewer.html?format=${format.extension}&token=$token")
    }
}

private class RendererPathHandler(private val root: File) : WebViewAssetLoader.PathHandler {
    private val canonicalRoot = root.canonicalFile

    override fun handle(path: String): WebResourceResponse? {
        val file = File(canonicalRoot, Uri.decode(path)).canonicalFile
        if (!file.path.startsWith(canonicalRoot.path + File.separator) || !file.isFile) return null
        return WebResourceResponse(officeMimeType(file.extension), null, file.inputStream())
    }
}

private class DocumentPathHandler(private val file: File, private val token: String) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        if (path != "$token/file" || !file.isFile) return null
        return WebResourceResponse("application/octet-stream", null, file.inputStream())
    }
}

private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    403,
    "Blocked",
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream(ByteArray(0))
)

private const val APP_ASSET_DOMAIN = "appassets.androidplatform.net"
