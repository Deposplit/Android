package com.deposplit.ui.secretdetail

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
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
import com.deposplit.ui.home.HealthBadge
import com.deposplit.ui.home.HolderRow
import com.deposplit.ui.home.SecretGroup
import com.deposplit.ui.home.SecretHealth
import com.deposplit.ui.home.formatDate
import com.deposplit.ui.reconstruction.ReconstructedSecretContent
import com.deposplit.ui.reconstruction.ReconstructionAdvisory
import com.deposplit.value_objects.SecretState
import kotlinx.coroutines.launch
import java.util.UUID

/** One secret: who holds a piece of it, and the three things that can be done to the whole of it.
 *
 * All three controls stay put whatever the state — what changes is whether they are enabled and the
 * line underneath saying why not. A control that vanishes leaves the reader hunting for something
 * they remember seeing, and teaches nothing about what k means.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SecretDetailScreen(
    secretId: UUID,
    onNavigateBack: () -> Unit,
    onNavigateToShareDetail: (UUID) -> Unit,
    onNavigateToRepair: (UUID) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as DeposplitApp
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val availability = remember(context) { biometricAvailability(context) }
    val viewModel: SecretDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SecretDetailViewModel(
                    secretId = secretId,
                    shareManagement = app.shareManagement,
                    contactManagement = app.contactManagement,
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmingClear by remember { mutableStateOf(false) }
    var confirmingDestroy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.group?.secret?.label ?: stringResource(R.string.secret_detail_title_fallback)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        }
    ) { padding ->
        val group = uiState.group
        when {
            uiState.isLoading && group == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            group == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(uiState.error ?: R.string.secret_detail_error_load),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::load) { Text(stringResource(R.string.action_retry)) }
                }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.secret_detail_threshold, group.secret.k, group.secret.n) +
                        " · " + formatDate(group.secret.secretCreatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HealthBadge(group.health)

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(stringResource(R.string.secret_detail_holders), style = MaterialTheme.typography.titleSmall)
                group.holders.forEach { holder ->
                    HolderRow(holder = holder, onClick = { onNavigateToShareDetail(holder.shareId) })
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                SecretActions(
                    group = group,
                    uiState = uiState,
                    availability = availability,
                    onRequestAll = viewModel::requestAll,
                    onReconstruct = {
                        if (BuildConfig.SKIP_BIOMETRIC) {
                            viewModel.reconstruct()
                        } else {
                            val act = activity
                            if (act != null) {
                                scope.launch {
                                    val result = authenticate(
                                        activity = act,
                                        title = context.getString(R.string.secret_detail_biometric_prompt_title),
                                        subtitle = context.getString(R.string.secret_detail_biometric_prompt_subtitle),
                                    )
                                    if (result is AuthResult.Succeeded) viewModel.reconstruct()
                                }
                            }
                        }
                    },
                    onClear = { confirmingClear = true },
                )

                uiState.reconstructedSecret?.let { secret ->
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        ReconstructedSecretContent(
                            secret = secret,
                            mimeType = group.secret.mimeType,
                            label = group.secret.label,
                        )
                    }
                    uiState.reconstructionIntegrity?.let { integrity ->
                        val unknown = stringResource(R.string.secret_detail_unknown_contact)
                        Spacer(Modifier.height(8.dp))
                        ReconstructionAdvisory(
                            integrity = integrity,
                            contactName = { id -> group.holders.find { it.contactId == id }?.recipientName ?: unknown },
                        )
                    }
                }

                uiState.actionError?.let { error ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (group.health == SecretHealth.CAUTION || group.health == SecretHealth.CRITICAL) {
                        Button(
                            onClick = { onNavigateToRepair(group.secret.id) },
                            colors = if (group.health == SecretHealth.CRITICAL) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                        ) { Text(stringResource(R.string.home_repair_button)) }
                    }
                    if (group.secret.state == SecretState.DESTROYING) {
                        // The health badge above already says Destroying, so this says the thing
                        // the badge cannot: how many holders are still to answer, which is exactly
                        // the judgement Force Forget beside it asks for. The count shrinks as each
                        // one confirms, because reconcileDestroying drops their ShareMetadata row.
                        Text(
                            text = pluralStringResource(
                                R.plurals.secret_detail_destroying_waiting,
                                group.holders.size,
                                group.holders.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        TextButton(onClick = {
                            viewModel.forceForget()
                            onNavigateBack()
                        }) { Text(stringResource(R.string.home_force_forget_button)) }
                    } else {
                        TextButton(onClick = { confirmingDestroy = true }) {
                            Text(stringResource(R.string.home_destroy_button), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Two texts, neither claiming anything about what the reader remembers: all that is known is
    // whether the secret is on this screen right now.
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.secret_detail_clear_title)) },
            text = {
                Text(
                    stringResource(
                        if (uiState.hasBeenShown) R.string.secret_detail_clear_confirm_after_reading
                        else R.string.secret_detail_clear_confirm_unread
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    viewModel.clearCollected()
                }) { Text(stringResource(R.string.secret_detail_clear_button)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (confirmingDestroy) {
        val holderCount = uiState.group?.holders?.size ?: 0
        AlertDialog(
            onDismissRequest = { confirmingDestroy = false },
            title = { Text(stringResource(R.string.home_destroy_title)) },
            text = { Text(stringResource(R.string.home_destroy_body, holderCount)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDestroy = false
                    viewModel.destroy()
                }) { Text(stringResource(R.string.home_destroy_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDestroy = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** The three secret-level actions, each with the reason it cannot be pressed set out beneath. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecretActions(
    group: SecretGroup,
    uiState: SecretDetailViewModel.UiState,
    availability: AuthAvailability,
    onRequestAll: () -> Unit,
    onReconstruct: () -> Unit,
    onClear: () -> Unit,
) {
    // Biometrics gate reconstruct, so when they are unavailable that is one more reason it cannot
    // be pressed — named, rather than left for the reader to discover by pressing.
    val biometricReason = if (BuildConfig.SKIP_BIOMETRIC) {
        null
    } else {
        when (availability) {
            AuthAvailability.Available -> null
            AuthAvailability.NoneEnrolled -> R.string.secret_detail_biometric_none_enrolled
            AuthAvailability.NoHardware -> R.string.secret_detail_biometric_no_hardware
            is AuthAvailability.Unavailable -> R.string.secret_detail_biometric_unavailable
        }
    }
    val busy = uiState.isRequestingAll || uiState.isReconstructing || uiState.isClearing

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(onClick = onRequestAll, enabled = group.canRequestRetrieval && !busy) {
            if (uiState.isRequestingAll) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.home_request_all))
            }
        }
        Button(onClick = onReconstruct, enabled = group.canReconstruct && biometricReason == null && !busy) {
            if (uiState.isReconstructing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(stringResource(R.string.secret_detail_reconstruct_button))
            }
        }
        OutlinedButton(onClick = onClear, enabled = group.canClearCollected && !busy) {
            Text(stringResource(R.string.secret_detail_clear_button))
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.secret_detail_approved_count, group.approvedRetrievals, group.secret.k),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    group.retrievalUnavailableReason?.let { Reason(stringResource(it)) }
    if (group.reconstructShortfall > 0) {
        Reason(
            pluralStringResource(
                R.plurals.secret_detail_reconstruct_disabled,
                group.reconstructShortfall,
                group.reconstructShortfall,
            )
        )
    } else if (biometricReason != null) {
        Reason(stringResource(biometricReason))
    }
    if (!group.canClearCollected) Reason(stringResource(R.string.secret_detail_clear_disabled))
    // Standing advice rather than a dialog over the secret: the moment it is finally on screen is
    // the worst possible moment to cover it with something that asks for nothing.
    if (uiState.hasBeenShown && group.canClearCollected) {
        Reason(stringResource(R.string.secret_detail_clear_reminder))
    }
}

@Composable
private fun Reason(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
