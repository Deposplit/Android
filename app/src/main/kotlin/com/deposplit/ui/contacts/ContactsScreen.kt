package com.deposplit.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
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
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.VerificationLevel
import com.deposplit.value_objects.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAddContact: () -> Unit,
    onNavigateToScanQr: () -> Unit,
    onNavigateToRelinkContact: (Contact) -> Unit,
) {
    val app = LocalContext.current.applicationContext as DeposplitApp
    val viewModel: ContactsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ContactsViewModel(app.contactManagement, app.shareManagement) }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToScanQr) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.contacts_action_scan_qr))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToAddContact) {
                Icon(Icons.Default.PersonAdd, contentDescription = stringResource(R.string.contacts_action_add))
            }
        },
    ) { padding ->
        when {
            uiState.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.error != null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(uiState.error!!),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::load) { Text(stringResource(R.string.action_retry)) }
                }
            }

            uiState.contacts.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.contacts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.contacts, key = { it.id }) { contact ->
                    ContactItem(
                        contact = contact,
                        awaitingRelink = contact.id in uiState.awaitingRelink,
                        onMarkRelinked = { viewModel.markRelinked(contact.id) },
                        onDelete = { viewModel.delete(contact.id) },
                        onRelink = { onNavigateToRelinkContact(contact) },
                        onMarkCompromised = { viewModel.markKeyCompromised(contact.id) },
                        onToggleHeartbeatEmission = { viewModel.toggleHeartbeatEmission(contact) },
                        onRename = { nickname -> viewModel.rename(contact.id, nickname) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactItem(
    contact: Contact,
    awaitingRelink: Boolean,
    onMarkRelinked: () -> Unit,
    onDelete: () -> Unit,
    onRelink: () -> Unit,
    onMarkCompromised: () -> Unit,
    onToggleHeartbeatEmission: () -> Unit,
    onRename: (String?) -> Unit,
) {
    var showCompromiseConfirm by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                    if (contact.revokedVerifyKeys.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.contacts_revoked_badge_description),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (awaitingRelink) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.PersonOff,
                            contentDescription = stringResource(
                                R.string.contacts_awaiting_relink_description,
                                contact.displayName,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    if (contact.heartbeatEmissionOptedOut) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.NotificationsOff,
                            contentDescription = stringResource(R.string.contacts_heartbeat_paused_badge_description),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (contact.nickname != null) {
                    // The only name value that ever left the counterparty's device — kept visible
                    // as a secondary line so it can actually be cross-checked.
                    Text(
                        text = contact.pseudonym,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (contact.verificationLevel != VerificationLevel.VERY_LOW) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = contact.verificationLevel.displayName(),
                        style = MaterialTheme.typography.bodySmall,
                        color = contact.verificationLevel.badgeColor(),
                    )
                }
            }
            if (awaitingRelink) {
                IconButton(onClick = onMarkRelinked) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(
                            R.string.contacts_mark_relinked_description,
                            contact.displayName,
                        ),
                    )
                }
            }
            IconButton(onClick = { showRenameDialog = true }) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.contacts_rename_description, contact.displayName),
                )
            }
            IconButton(onClick = onRelink) {
                Icon(
                    Icons.Default.Autorenew,
                    contentDescription = stringResource(R.string.contacts_relink_description, contact.displayName),
                )
            }
            IconButton(onClick = onToggleHeartbeatEmission) {
                Icon(
                    if (contact.heartbeatEmissionOptedOut) Icons.Default.Notifications else Icons.Default.NotificationsOff,
                    contentDescription = stringResource(
                        if (contact.heartbeatEmissionOptedOut) {
                            R.string.contacts_resume_heartbeats_description
                        } else {
                            R.string.contacts_pause_heartbeats_description
                        },
                        contact.displayName,
                    ),
                )
            }
            IconButton(onClick = { showCompromiseConfirm = true }) {
                Icon(
                    Icons.Default.Shield,
                    contentDescription = stringResource(R.string.contacts_mark_compromised_description, contact.displayName),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.contacts_delete_description, contact.displayName),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    if (showCompromiseConfirm) {
        AlertDialog(
            onDismissRequest = { showCompromiseConfirm = false },
            title = { Text(stringResource(R.string.contacts_mark_compromised_title)) },
            text = { Text(stringResource(R.string.contacts_mark_compromised_message, contact.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    showCompromiseConfirm = false
                    onMarkCompromised()
                }) { Text(stringResource(R.string.contacts_mark_compromised_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCompromiseConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (showRenameDialog) {
        var nicknameInput by remember { mutableStateOf(contact.nickname.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text(stringResource(R.string.contacts_rename_title)) },
            text = {
                OutlinedTextField(
                    value = nicknameInput,
                    onValueChange = { nicknameInput = it },
                    label = { Text(stringResource(R.string.contacts_rename_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    onRename(nicknameInput.trim().ifBlank { null })
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
