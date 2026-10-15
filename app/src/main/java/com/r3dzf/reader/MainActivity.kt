package com.r3dzf.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val library by lazy { DocumentLibrary.get(this) }
    private var documents by mutableStateOf<List<LibraryDocument>>(emptyList())
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importFile(it) } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReaderTheme { ReaderHome(documents,busy,error,
            onOpen={picker.launch((listOf("application/pdf")+OfficeFormat.entries.map { it.mimeType.lowercase(java.util.Locale.ROOT) }).distinct().toTypedArray())},
            onOpenDocument={document -> open(document.id)},
            onFavorite={document -> mutate { library.favorite(document.id,!document.favorite) }},
            onRemove={document -> mutate { library.remove(document.id) }},onClear={mutate { library.clear() }},onErrorDismissed={error=null}) } }
        if(savedInstanceState==null) handleIncomingIntent(intent)
    }
    override fun onResume() { super.onResume();refresh() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);handleIncomingIntent(intent) }
    private fun handleIncomingIntent(intent: Intent?) {
        if(intent?.action==Intent.ACTION_VIEW) intent.data?.takeIf { it.scheme=="content" }?.let { importFile(it,intent.type) }
    }
    private fun refresh() { lifecycleScope.launch { runCatching { library.documents() }.onSuccess { documents=it }.onFailure { showError(it) } } }
    private fun mutate(action: suspend () -> Unit) {
        if(busy) return
        lifecycleScope.launch { busy=true;try { action();documents=library.documents() } catch(failure: Exception) { showError(failure) } finally { busy=false } }
    }
    private fun importFile(uri: Uri,mimeHint: String?=null) {
        if(busy) return
        lifecycleScope.launch {
            busy=true
            try { val document=library.import(uri,mimeHint);documents=library.documents();launchDocument(document.id) }
            catch(failure: Exception) { showError(failure) }
            finally { busy=false }
        }
    }
    private fun open(id: String) {
        if(busy) return
        lifecycleScope.launch { busy=true;try { launchDocument(id);documents=library.documents() } catch(failure: Exception) { showError(failure);refresh() } finally { busy=false } }
    }
    private suspend fun launchDocument(id: String) {
        val (document,file)=library.open(id)
        startActivity(if(document.kind==DocumentKind.PDF) PdfViewerActivity.intent(this,file,document.displayName,document.id)
            else OfficeDocumentViewerActivity.intent(this,PreparedOfficeDocument(file,document.displayName,OfficeFormat.fromName("file.${document.extension}")!!),document.id))
    }
    private fun showError(failure: Throwable) {
        error=getString(when((failure as? LibraryException)?.problem) {
            LibraryProblem.TOO_LARGE -> R.string.library_too_large;LibraryProblem.FULL -> R.string.library_full
            LibraryProblem.MISSING -> R.string.library_missing;LibraryProblem.UNSUPPORTED -> R.string.library_unsupported
            else -> R.string.open_failed
        })
    }
}
