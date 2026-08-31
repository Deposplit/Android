package com.deposplit.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deposplit.DeposplitApp
import com.deposplit.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DeposplitApp
    val viewModel: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(app.relaySettings, app.catalogManagement, app.shareManagement, app.contactManagement) }
        }
    )
    var showRegenerateConfirmation by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) writeCatalogExport(context, uri, viewModel.exportCatalogBytes())
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readCatalogImport(context, uri)?.let { viewModel.importCatalogBytes(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_relay_description))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.relayBaseUrl,
                onValueChange = viewModel::onRelayBaseUrlChange,
                label = { Text(stringResource(R.string.settings_relay_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row {
                Button(onClick = { viewModel.save(); onNavigateBack() }) {
                    Text(stringResource(R.string.action_save))
                }
                Spacer(Modifier.height(0.dp))
                OutlinedButton(onClick = viewModel::resetToDefault, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.settings_relay_reset))
                }
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_catalog_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_catalog_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                OutlinedButton(onClick = { exportLauncher.launch("deposplit-catalog.json") }) {
                    Text(stringResource(R.string.settings_catalog_export))
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.settings_catalog_import))
                }
            }
            if (uiState.catalogMessage != null) {
                Spacer(Modifier.height(8.dp))
                val text = uiState.catalogMessageArg?.let { stringResource(uiState.catalogMessage!!, it) } ?: stringResource(uiState.catalogMessage!!)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.settings_identity_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.settings_regenerate_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showRegenerateConfirmation = true },
                enabled = !uiState.isRegeneratingIdentity,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                if (uiState.isRegeneratingIdentity) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.settings_regenerate_button))
                }
            }
            uiState.regenerateResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_regenerate_result, result.notifiedContacts, result.totalContacts),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!result.drainSucceeded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_regenerate_drain_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            uiState.regenerateError?.let { errorRes ->
                Spacer(Modifier.height(8.dp))
                Text(stringResource(errorRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showRegenerateConfirmation) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirmation = false },
            title = { Text(stringResource(R.string.settings_regenerate_confirm_title)) },
            text = { Text(stringResource(R.string.settings_regenerate_confirm_body, uiState.contactCount)) },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateConfirmation = false
                    viewModel.regenerateIdentity()
                }) {
                    Text(stringResource(R.string.settings_regenerate_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private fun writeCatalogExport(context: Context, uri: android.net.Uri, bytes: ByteArray) {
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
}

private fun readCatalogImport(context: Context, uri: android.net.Uri): ByteArray? =
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
