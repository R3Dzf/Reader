package com.r3dzf.reader

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private var uiState by mutableStateOf(ReaderUiState())
    private var msalApp: ISingleAccountPublicClientApplication? = null
    private var accessToken: String? = null

    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openLocalPdf(uri)
    }

    private val officePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openInMicrosoftApp(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReaderTheme {
                ReaderHome(
                    state = uiState,
                    onOpenPdf = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onOpenOffice = {
                        officePicker.launch(
                            arrayOf(
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "application/msword",
                                "application/vnd.ms-excel",
                                "application/vnd.ms-powerpoint"
                            )
                        )
                    },
                    onConnect = ::connectOneDrive,
                    onRefresh = ::refreshOneDrive,
                    onReadFile = ::convertAndOpen
                )
            }
        }
        initializeMicrosoftSignIn()
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

    private fun initializeMicrosoftSignIn() {
        val configText = resources.openRawResource(R.raw.auth_config_single_account)
            .bufferedReader().use { it.readText() }
        val clientId = runCatching { JSONObject(configText).optString("client_id") }.getOrNull().orEmpty()
        val configured = clientId.isNotBlank() && !clientId.startsWith("REPLACE_")
        uiState = uiState.copy(
            microsoftConfigured = configured,
            status = if (configured) null else getString(R.string.microsoft_setup_required)
        )
        if (!configured) return

        PublicClientApplication.createSingleAccountPublicClientApplication(
            applicationContext,
            R.raw.auth_config_single_account,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    msalApp = application
                    uiState = uiState.copy(status = null)
                }

                override fun onError(exception: MsalException) {
                    uiState = uiState.copy(status = exception.message ?: getString(R.string.microsoft_not_ready))
                }
            }
        )
    }

    private fun connectOneDrive() {
        val client = msalApp
        if (client == null) {
            uiState = uiState.copy(status = getString(R.string.microsoft_setup_required))
            return
        }
        uiState = uiState.copy(busy = true, status = getString(R.string.loading))
        client.acquireToken(this, arrayOf("Files.Read"), object : AuthenticationCallback {
            override fun onSuccess(authenticationResult: IAuthenticationResult) {
                accessToken = authenticationResult.accessToken
                uiState = uiState.copy(connected = true, busy = false, status = null)
                refreshOneDrive()
            }

            override fun onError(exception: MsalException) {
                uiState = uiState.copy(busy = false, status = exception.message ?: getString(R.string.microsoft_not_ready))
            }

            override fun onCancel() {
                uiState = uiState.copy(busy = false, status = null)
            }
        })
    }

    private fun refreshOneDrive() {
        val token = accessToken
        if (token == null) {
            connectOneDrive()
            return
        }
        uiState = uiState.copy(busy = true, status = getString(R.string.loading))
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { OneDriveApi.listOfficeFiles(token) } }
                .onSuccess { files ->
                    uiState = uiState.copy(busy = false, connected = true, files = files, status = null)
                }
                .onFailure { error ->
                    uiState = uiState.copy(busy = false, status = error.message ?: getString(R.string.open_failed))
                }
        }
    }

    private fun convertAndOpen(item: DriveFile) {
        val token = accessToken
        if (token == null) {
            connectOneDrive()
            return
        }
        uiState = uiState.copy(busy = true, status = getString(R.string.converting))
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { OneDriveApi.convertToPdf(token, item, cacheDir) }
            }
            uiState = uiState.copy(busy = false, status = result.exceptionOrNull()?.message)
            result.getOrNull()?.let { startActivity(PdfViewerActivity.intent(this@MainActivity, it)) }
        }
    }

    private fun openLocalPdf(uri: Uri) {
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { copyToCache(uri, "local-pdf", ".pdf") } }
            result.onSuccess { startActivity(PdfViewerActivity.intent(this@MainActivity, it)) }
                .onFailure { Toast.makeText(this@MainActivity, getString(R.string.pdf_failed), Toast.LENGTH_LONG).show() }
        }
    }

    private fun openInMicrosoftApp(uri: Uri) {
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(viewIntent, getString(R.string.choose_app)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.open_failed), Toast.LENGTH_LONG).show()
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
