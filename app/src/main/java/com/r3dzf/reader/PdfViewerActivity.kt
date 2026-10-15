package com.r3dzf.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

class PdfViewerActivity : ComponentActivity() {
    private lateinit var session: ReadingSession
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file=intent.getStringExtra(EXTRA_PDF_PATH)?.let(::File)
        if(file==null || !isReaderPrivateFile(this,file,"incoming")) { finish();return }
        val name=intent.getStringExtra(EXTRA_NAME) ?: file.name
        session=ReadingSession(this,intent.getStringExtra(EXTRA_ID),lifecycleScope)
        lifecycleScope.launch {
            val progress=session.initial()
            setContent { ReaderTheme { PdfDocumentView(file,displayName=name,initialProgress=progress,onProgress=session::changed,onClose=::finish) } }
        }
    }
    override fun onStop() { if(::session.isInitialized) session.stop();super.onStop() }
    companion object {
        private const val EXTRA_PDF_PATH="reader.pdf.path"
        private const val EXTRA_NAME="reader.pdf.name"
        private const val EXTRA_ID="reader.library.id"
        fun intent(context: Context,file: File,name: String=file.name,historyId: String?=null): Intent =
            Intent(context,PdfViewerActivity::class.java).putExtra(EXTRA_PDF_PATH,file.absolutePath).putExtra(EXTRA_NAME,name).putExtra(EXTRA_ID,historyId)
    }
}
