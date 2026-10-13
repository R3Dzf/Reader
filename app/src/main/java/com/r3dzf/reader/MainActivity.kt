package com.r3dzf.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openLocalPdf(uri)
    }

    private val officePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openOfficeDocument(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReaderTheme {
                ReaderHome(
                    onOpenPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onOpenOffice = {
                        officePicker.launch(OfficeFormat.entries.map { it.mimeType }.toTypedArray())
                    }
                )
            }
        }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.type == "application/pdf") {
            intent.data?.let(::openLocalPdf)
        }
    }

    private fun openLocalPdf(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { copyToCache(uri, "local-pdf", ".pdf") }
            }
            result.onSuccess { startActivity(PdfViewerActivity.intent(this@MainActivity, it)) }
                .onFailure {
                    Toast.makeText(this@MainActivity, getString(R.string.pdf_failed), Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun openOfficeDocument(uri: Uri) {
        lifecycleScope.launch {
            val prepared = runCatching {
                withContext(Dispatchers.IO) { prepareOfficeDocument(this@MainActivity, uri) }
            }
            prepared.onSuccess { document ->
                startActivity(OfficeDocumentViewerActivity.intent(this@MainActivity, document))
            }.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: getString(R.string.open_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun copyToCache(uri: Uri, prefix: String, suffix: String): File {
        val directory = File(cacheDir, "incoming").apply { mkdirs() }
        val outputFile = File.createTempFile(prefix, suffix, directory)
        try {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not read the selected file." }
                FileOutputStream(outputFile).use { output -> input.copyTo(output) }
            }
            return outputFile
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }
}
