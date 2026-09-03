package com.deposplit.ui.paywall

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(onNavigateBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as DeposplitApp
    val viewModel: PaywallViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PaywallViewModel(app.purchases) }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.paywall_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.paywall_intro), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(20.dp))

            Text(stringResource(R.string.paywall_benefit_secrets_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.paywall_benefit_secrets_body, uiState.freeTierLimit))
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.paywall_benefit_byor_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.paywall_benefit_byor_body))
            Spacer(Modifier.height(24.dp))

            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            if (uiState.isPremium) {
                Text(stringResource(R.string.paywall_active), style = MaterialTheme.typography.titleMedium)
            } else {
                Button(
                    onClick = {},
                    enabled = uiState.isPurchaseAvailable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.paywall_purchase_button))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {},
                    enabled = uiState.isPurchaseAvailable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.paywall_restore_button))
                }
                if (!uiState.isPurchaseAvailable) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.paywall_purchase_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.paywall_honesty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
