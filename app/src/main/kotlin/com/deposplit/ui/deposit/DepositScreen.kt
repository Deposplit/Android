package com.deposplit.ui.deposit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deposplit.DeposplitApp
import com.deposplit.contacts.Contact
import com.deposplit.contacts.VerificationLevel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositScreen(onNavigateBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as DeposplitApp
    val viewModel: DepositViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                DepositViewModel(app.authAdapter, app.shareTransport, app.contactRepository)
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                DepositViewModel.Effect.NavigateBack -> onNavigateBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Secret") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        if (uiState.isLoadingContacts) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
        ) {
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.label,
                onValueChange = viewModel::onLabelChange,
                label = { Text("Label") },
                placeholder = { Text("e.g. BitLocker key") },
                isError = uiState.labelError != null,
                supportingText = uiState.labelError?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = uiState.secret,
                onValueChange = viewModel::onSecretChange,
                label = { Text("Secret") },
                isError = uiState.secretError != null,
                supportingText = uiState.secretError?.let { { Text(it) } },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            Text("Recipients", style = MaterialTheme.typography.titleSmall)

            if (uiState.contacts.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "No contacts yet — add contacts first",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                uiState.contacts.forEach { contact ->
                    ContactRow(
                        contact = contact,
                        selected = contact.id in uiState.selectedContactIds,
                        onToggle = { viewModel.onToggleContact(contact.id) },
                    )
                }
            }

            if (uiState.selectionError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = uiState.selectionError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (uiState.selectedCount >= 2) {
                Spacer(Modifier.height(20.dp))
                Text("Required to reconstruct", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                ThresholdRow(
                    threshold = uiState.threshold,
                    selectedCount = uiState.selectedCount,
                    onDecrement = viewModel::onThresholdDecrement,
                    onIncrement = viewModel::onThresholdIncrement,
                )
            }

            if (uiState.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = viewModel::deposit,
                enabled = !uiState.isDepositing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isDepositing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Split & Share")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ContactRow(contact: Contact, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(contact.pseudonym, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (contact.verificationLevel == VerificationLevel.VERIFIED) "Verified" else "Unverified",
                style = MaterialTheme.typography.bodySmall,
                color = if (contact.verificationLevel == VerificationLevel.VERIFIED)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ThresholdRow(
    threshold: Int,
    selectedCount: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconButton(onClick = onDecrement, enabled = threshold > 2) {
            Text("−", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = "$threshold of $selectedCount",
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onIncrement, enabled = threshold < selectedCount) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}
