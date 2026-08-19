package com.deposplit.ui.deposit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.deposplit.ui.contacts.badgeColor
import com.deposplit.ui.contacts.displayName
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.VerificationLevel
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DepositScreen(onNavigateBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as DeposplitApp
    val viewModel: DepositViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                DepositViewModel(app.shareManagement, app.contactManagement)
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
                title = { Text(stringResource(R.string.deposit_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        }
    ) { padding ->
        DepositForm(uiState = uiState, viewModel = viewModel, contentPadding = padding)
    }
}

/**
 * The deposit form itself, factored out so the Repair flow (`RepairScreen`) can embed the same
 * validated form — including the split-time warning dialog — inside its own wizard step rather
 * than duplicating it. `DepositScreen` wraps this in its own `Scaffold`/`TopAppBar` for the
 * standalone "Split & Share" route; `RepairScreen` embeds it directly inside its own `Scaffold`.
 */
@Composable
fun DepositForm(
    uiState: DepositViewModel.UiState,
    viewModel: DepositViewModel,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (uiState.isLoadingContacts) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
    ) {
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = uiState.label,
            onValueChange = viewModel::onLabelChange,
            label = { Text(stringResource(R.string.deposit_label_label)) },
            placeholder = { Text(stringResource(R.string.deposit_label_placeholder)) },
            isError = uiState.labelError != null,
            supportingText = uiState.labelError?.let { resId -> { Text(stringResource(resId)) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.secret,
            onValueChange = viewModel::onSecretChange,
            label = { Text(stringResource(R.string.deposit_secret_label)) },
            isError = uiState.secretError != null,
            supportingText = uiState.secretError?.let { resId -> { Text(stringResource(resId)) } },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        Text(stringResource(R.string.deposit_recipients_title), style = MaterialTheme.typography.titleSmall)

        if (uiState.contacts.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.deposit_no_contacts),
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
                text = stringResource(uiState.selectionError!!),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (uiState.selectedCount >= 2) {
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.deposit_threshold_title), style = MaterialTheme.typography.titleSmall)
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
                text = stringResource(uiState.error!!),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = viewModel::onDepositClick,
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
                Text(stringResource(R.string.deposit_button))
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (uiState.pendingWarnings.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissWarnings,
            title = { Text(stringResource(R.string.deposit_warning_title)) },
            text = {
                Column {
                    uiState.pendingWarnings.forEachIndexed { index, warning ->
                        if (index > 0) Spacer(Modifier.height(8.dp))
                        Text(warningText(warning))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDespiteWarnings) {
                    Text(stringResource(R.string.deposit_warning_deposit_anyway))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissWarnings) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun warningText(warning: DepositViewModel.SplitTimeWarning): String = when (warning) {
    is DepositViewModel.SplitTimeWarning.OperationalLarge -> stringResource(R.string.deposit_warning_operational_large, warning.n)
    is DepositViewModel.SplitTimeWarning.OperationalMedium -> stringResource(R.string.deposit_warning_operational_medium, warning.n)
    is DepositViewModel.SplitTimeWarning.ConfidentialityLow -> stringResource(R.string.deposit_warning_confidentiality_low, warning.k, warning.n)
    is DepositViewModel.SplitTimeWarning.ConfidentialityMedium -> stringResource(R.string.deposit_warning_confidentiality_medium, warning.k, warning.n)
    DepositViewModel.SplitTimeWarning.AvailabilityNone -> stringResource(R.string.deposit_warning_availability_none)
    DepositViewModel.SplitTimeWarning.AvailabilityOne -> stringResource(R.string.deposit_warning_availability_one)
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
            if (contact.verificationLevel != VerificationLevel.VERY_LOW) {
                Text(
                    text = contact.verificationLevel.displayName(),
                    style = MaterialTheme.typography.bodySmall,
                    color = contact.verificationLevel.badgeColor(),
                )
            }
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
            text = stringResource(R.string.deposit_threshold_format, threshold, selectedCount),
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onClick = onIncrement, enabled = threshold < selectedCount) {
            Text("+", style = MaterialTheme.typography.titleLarge)
        }
    }
}
