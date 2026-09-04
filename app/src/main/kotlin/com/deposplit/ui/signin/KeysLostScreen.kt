package com.deposplit.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

/**
 * Shown instead of Home when this device's private keys did not survive a phone switch. Blocking,
 * because every signed action would fail anyway — and because the one thing that must not happen
 * is the user handing out a QR code for an identity they can no longer prove.
 */
@Composable
fun KeysLostScreen(onNavigateToHome: () -> Unit) {
    val app = LocalContext.current.applicationContext as DeposplitApp
    val viewModel: KeysLostViewModel = viewModel(
        factory = viewModelFactory {
            initializer { KeysLostViewModel(app.authAdapter) }
        }
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                KeysLostViewModel.Effect.NavigateToHome -> onNavigateToHome()
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.keys_lost_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.keys_lost_what_happened),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.keys_lost_nothing_else_lost),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.keys_lost_what_to_do),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = uiState.pseudonym,
                onValueChange = viewModel::onPseudonymChange,
                label = { Text(stringResource(R.string.signin_name_label)) },
                placeholder = { Text(stringResource(R.string.signin_name_placeholder)) },
                singleLine = true,
                isError = uiState.error != null,
                supportingText = uiState.error?.let { resId ->
                    { Text(stringResource(resId), color = MaterialTheme.colorScheme.error) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = viewModel::onCreateNewKeys,
                enabled = !uiState.isLoading && uiState.pseudonym.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.keys_lost_button))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
