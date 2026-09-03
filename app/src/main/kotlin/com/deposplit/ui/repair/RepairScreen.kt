package com.deposplit.ui.repair

import androidx.activity.compose.LocalActivity
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deposplit.BuildConfig
import com.deposplit.DeposplitApp
import com.deposplit.R
import com.deposplit.ui.biometric.AuthAvailability
import com.deposplit.ui.biometric.AuthResult
import com.deposplit.ui.biometric.authenticate
import com.deposplit.ui.biometric.biometricAvailability
import com.deposplit.ui.deposit.DepositForm
import com.deposplit.ui.deposit.DepositViewModel
import com.deposplit.ui.reconstruction.ReconstructionAdvisory
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.displayName
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The "reconstruct-and-re-split" repair flow: gather k approved retrievals → reconstruct →
 * re-deposit (prefilled) → optionally discard the old distribution. One screen with internal
 * wizard state ([RepairPhase]), so the reconstructed plaintext never leaves this route's
 * ViewModels or gets serialized into a navigation argument.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepairScreen(secretId: UUID, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DeposplitApp
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val availability = remember(context) { biometricAvailability(context) }
    val viewModel: RepairViewModel = viewModel(
        factory = viewModelFactory {
            initializer { RepairViewModel(secretId, app.shareManagement, app.contactManagement) }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        uiState.secret?.label?.let { stringResource(R.string.repair_title_format, it) }
                            ?: stringResource(R.string.repair_title_fallback)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        }
    ) { padding ->
        when (uiState.phase) {
            RepairPhase.GATHERING, RepairPhase.RECONSTRUCTING -> GatheringContent(
                uiState = uiState,
                availability = availability,
                activity = activity,
                scope = scope,
                padding = padding,
                onRequestMissing = viewModel::requestMissingRetrievals,
                onReconstruct = viewModel::reconstruct,
            )

            RepairPhase.REDEPOSIT -> {
                val prefill = uiState.prefill
                if (prefill != null) {
                    val depositViewModel: DepositViewModel = viewModel(
                        key = "repair_deposit_$secretId",
                        factory = viewModelFactory {
                            initializer { DepositViewModel(app.shareManagement, app.contactManagement, prefill) }
                        }
                    )
                    val depositUiState by depositViewModel.uiState.collectAsStateWithLifecycle()
                    LaunchedEffect(depositViewModel) {
                        depositViewModel.effects.collect { effect ->
                            when (effect) {
                                DepositViewModel.Effect.NavigateBack -> viewModel.newDepositSucceeded()
                            }
                        }
                    }
                    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                        uiState.reconstructionIntegrity?.let { integrity ->
                            val unknownContactLabel = stringResource(R.string.share_detail_unknown_contact)
                            ReconstructionAdvisory(
                                integrity = integrity,
                                contactName = { id -> uiState.contacts.find { it.id == id }?.displayName ?: unknownContactLabel },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            DepositForm(uiState = depositUiState, viewModel = depositViewModel)
                        }
                    }
                }
            }

            RepairPhase.CONFIRM_DISCARD -> ConfirmDiscardContent(
                uiState = uiState,
                padding = padding,
                onDiscard = viewModel::discardOldAndFinish,
                onSkip = viewModel::skipDiscard,
            )

            RepairPhase.DONE -> DoneContent(padding = padding, onClose = onNavigateBack)
        }
    }
}

@Composable
private fun GatheringContent(
    uiState: RepairViewModel.UiState,
    availability: AuthAvailability,
    activity: FragmentActivity?,
    scope: kotlinx.coroutines.CoroutineScope,
    padding: PaddingValues,
    onRequestMissing: () -> Unit,
    onReconstruct: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        if (uiState.isLoading && uiState.secret == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Box
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            val neededRetrievals = uiState.secret?.k ?: Int.MAX_VALUE
            Text(
                text = stringResource(R.string.repair_need_retrievals, neededRetrievals, uiState.approvedCount),
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.repair_holders_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            if (uiState.holderStatuses.isEmpty()) {
                Text(
                    text = stringResource(R.string.repair_no_holders),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                uiState.holderStatuses.forEach { holder ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(holder.pseudonym, style = MaterialTheme.typography.bodyMedium)
                            if (holder.subtitle != null) {
                                Text(
                                    text = holder.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = holder.requestState.label(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            OutlinedButton(
                onClick = onRequestMissing,
                enabled = !uiState.isActing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isActing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.repair_request_missing_button))
            }

            if (uiState.readyToReconstruct) {
                Spacer(Modifier.height(12.dp))
                if (BuildConfig.SKIP_BIOMETRIC) {
                    Button(
                        onClick = onReconstruct,
                        enabled = !uiState.isActing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.isActing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.share_detail_reconstruct_button))
                    }
                } else {
                    val unavailableMessage = when (availability) {
                        AuthAvailability.Available -> null
                        AuthAvailability.NoneEnrolled -> stringResource(R.string.share_detail_biometric_none_enrolled)
                        AuthAvailability.NoHardware -> stringResource(R.string.share_detail_biometric_no_hardware)
                        is AuthAvailability.Unavailable -> stringResource(R.string.share_detail_biometric_unavailable)
                    }
                    if (unavailableMessage != null) {
                        Text(
                            text = unavailableMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val promptTitle = stringResource(R.string.share_detail_biometric_prompt_title)
                        val promptSubtitle = stringResource(R.string.share_detail_biometric_prompt_subtitle)
                        Button(
                            onClick = {
                                val act = activity ?: return@Button
                                scope.launch {
                                    val result = authenticate(activity = act, title = promptTitle, subtitle = promptSubtitle)
                                    if (result is AuthResult.Succeeded) onReconstruct()
                                }
                            },
                            enabled = !uiState.isActing && activity != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (uiState.isActing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.share_detail_reconstruct_button))
                        }
                    }
                }
            }

            if (uiState.error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(uiState.error),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConfirmDiscardContent(
    uiState: RepairViewModel.UiState,
    padding: PaddingValues,
    onDiscard: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.repair_complete_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.repair_complete_body, uiState.depositedHolderCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onDiscard,
            enabled = !uiState.isActing,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isActing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.repair_discard_old_button))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) { Text(stringResource(R.string.repair_not_now_button)) }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun DoneContent(padding: PaddingValues, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.repair_done_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose) { Text(stringResource(R.string.repair_close_button)) }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ShareRequestState?.label(): String = when (this) {
    null -> stringResource(R.string.repair_not_requested)
    ShareRequestState.PENDING -> stringResource(R.string.share_request_state_pending)
    ShareRequestState.APPROVED -> stringResource(R.string.share_request_state_approved)
    ShareRequestState.DENIED -> stringResource(R.string.share_request_state_denied)
    ShareRequestState.WITHDRAWN -> stringResource(R.string.share_request_state_withdrawn)
}
