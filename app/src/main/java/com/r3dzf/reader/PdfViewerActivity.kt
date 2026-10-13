package com.r3dzf.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import java.io.File

class PdfViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PDF_PATH)
        val file = path?.let(::File)
        if (file == null || !file.isFile) {
            finish()
            return
        }
        setContent {
            ReaderTheme {
                PdfDocumentView(file = file, onClose = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_PDF_PATH = "reader.pdf.path"

        fun intent(context: Context, file: File): Intent =
            Intent(context, PdfViewerActivity::class.java).putExtra(EXTRA_PDF_PATH, file.absolutePath)
    }
}
