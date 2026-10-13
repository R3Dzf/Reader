package com.r3dzf.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ReaderHome(
    state: ReaderUiState,
    onOpenPdf: () -> Unit,
    onOpenOffice: () -> Unit,
    onConnect: () -> Unit,
    onRefresh: () -> Unit,
    onReadFile: (DriveFile) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Reader", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.app_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    stringResource(R.string.reading_only),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.section_on_device), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Button(onClick = onOpenPdf, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_pdf))
                }
                OutlinedButton(onClick = onOpenOffice, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.open_office))
                }
                Text(stringResource(R.string.office_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.section_onedrive), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.onedrive_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = if (state.connected) onRefresh else onConnect, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                        Text(stringResource(if (state.connected) R.string.refresh_files else R.string.connect_onedrive))
                    }
                    state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                    if (state.connected && state.files.isEmpty() && !state.busy) {
                        Text(stringResource(R.string.empty_onedrive), style = MaterialTheme.typography.bodySmall)
                    }
                    state.files.forEach { file ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(file.extension.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { onReadFile(file) }, enabled = !state.busy) {
                                    Text(stringResource(R.string.read_as_pdf))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
