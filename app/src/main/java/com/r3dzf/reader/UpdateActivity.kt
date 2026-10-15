package com.r3dzf.reader

import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

class UpdateActivity: ComponentActivity() {
    private val updates by lazy { AppUpdates.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReaderTheme {
            val status by updates.state.collectAsState()
            UpdateScreen(status,{updates.check()},{updates.download()},{updates.download(full=true)},{updates.install(this)},{updates.cancel()},{finish()})
        } }
        if(updates.state.value.phase==UpdatePhase.IDLE) updates.check()
    }
}
@Composable
internal fun UpdateScreen(status: UpdateStatus,onCheck: ()->Unit,onDownload: ()->Unit,onFull: ()->Unit,onInstall: ()->Unit,onCancel: ()->Unit,onClose: ()->Unit) {
    val context=LocalContext.current
    val release=status.release
    val transfer=status.delta?:release?.full
    val busy=status.phase in setOf(UpdatePhase.CHECKING,UpdatePhase.DOWNLOADING,UpdatePhase.PATCHING,UpdatePhase.VERIFYING)
    Scaffold(topBar={DocumentTopBar(stringResource(R.string.app_updates),"Rafolio · ${BuildConfig.VERSION_NAME}",onClose)}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("updates-screen"),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text(stringResource(when(status.phase) {
                UpdatePhase.IDLE -> R.string.update_check_title
                UpdatePhase.CHECKING -> R.string.update_checking
                UpdatePhase.CURRENT -> R.string.update_current
                UpdatePhase.AVAILABLE -> R.string.update_available
                UpdatePhase.DOWNLOADING -> R.string.update_downloading
                UpdatePhase.PATCHING -> R.string.update_preparing
                UpdatePhase.VERIFYING -> R.string.update_verifying
                UpdatePhase.READY -> R.string.update_ready
                UpdatePhase.FULL_REQUIRED -> R.string.update_full_required
                UpdatePhase.ERROR -> R.string.update_failed
            }),style=MaterialTheme.typography.headlineMedium)
            if(release!=null) {
                Text(stringResource(R.string.update_version,release.versionName),style=MaterialTheme.typography.titleMedium)
                if(status.phase in setOf(UpdatePhase.AVAILABLE,UpdatePhase.FULL_REQUIRED)) {
                    val asset=if(status.phase==UpdatePhase.FULL_REQUIRED) release.full else transfer!!
                    Text(stringResource(if(asset==release.full) R.string.update_full_size else R.string.update_delta_size,Formatter.formatShortFileSize(context,asset.bytes)),modifier=Modifier.testTag("update-size"))
                    if(asset!=release.full) Text(stringResource(R.string.update_saved_percent,((1.0-asset.bytes.toDouble()/release.full.bytes)*100).toInt()),style=MaterialTheme.typography.bodyMedium)
                }
                if(release.notes.isNotBlank()) Text(release.notes,style=MaterialTheme.typography.bodyMedium)
            }
            if(busy) {
                if(status.total>0 && status.phase in setOf(UpdatePhase.DOWNLOADING,UpdatePhase.PATCHING)) {
                    LinearProgressIndicator(progress={ (status.done.toFloat()/status.total).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth().testTag("update-progress"))
                    Text(stringResource(R.string.update_progress,Formatter.formatShortFileSize(context,status.done),Formatter.formatShortFileSize(context,status.total),((status.done*100)/status.total).coerceIn(0,100).toInt()))
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick=onCancel,modifier=Modifier.testTag("cancel-update")) { Text(stringResource(R.string.cancel)) }
            } else when(status.phase) {
                UpdatePhase.AVAILABLE -> Button(onClick=onDownload,modifier=Modifier.fillMaxWidth().testTag("download-update")) { Text(stringResource(R.string.update_download)) }
                UpdatePhase.FULL_REQUIRED -> {
                    Text(stringResource(R.string.update_delta_fallback),style=MaterialTheme.typography.bodyMedium)
                    Button(onClick=onFull,modifier=Modifier.fillMaxWidth().testTag("download-full-update")) { Text(stringResource(R.string.update_download_full)) }
                }
                UpdatePhase.READY -> {
                    Text(stringResource(R.string.update_install_hint),style=MaterialTheme.typography.bodyMedium)
                    Button(onClick=onInstall,modifier=Modifier.fillMaxWidth().testTag("install-update")) { Text(stringResource(R.string.update_install)) }
                }
                else -> {
                    if(status.error!=null) Text(stringResource(when(status.error) {
                        "no_release" -> R.string.update_no_release
                        "space" -> R.string.update_space
                        "signing_migration" -> R.string.update_migration
                        "split_install" -> R.string.update_split
                        "android_version" -> R.string.update_android
                        "integrity","signature","package" -> R.string.update_untrusted
                        else -> R.string.update_network
                    }),style=MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick=onCheck,modifier=Modifier.fillMaxWidth().testTag("check-update")) { Text(stringResource(R.string.update_check)) }
                }
            }
            HorizontalDivider()
            Text(stringResource(R.string.update_privacy_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
