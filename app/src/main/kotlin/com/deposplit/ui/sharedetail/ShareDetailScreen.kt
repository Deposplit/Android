package com.deposplit.ui.sharedetail

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareRequestType
import com.deposplit.ui.biometric.AuthAvailability
import com.deposplit.ui.biometric.AuthResult
import com.deposplit.ui.biometric.authenticate
import com.deposplit.ui.biometric.biometricAvailability
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareDetailScreen(shareId: UUID, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as DeposplitApp
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val availability = remember(context) { biometricAvailability(context) }
    val viewModel: ShareDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ShareDetailViewModel(
                    shareId = shareId,
                    shareManagement = app.shareManagement,
                    contactManagement = app.contactManagement,
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.share?.label ?: stringResource(R.string.share_detail_title_fallback)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        }
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

            uiState.share != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(16.dp))

                val recipientName = uiState.contacts
                    .find { it.id == uiState.share!!.contactId }
                    ?.pseudonym
                    ?: stringResource(R.string.share_detail_unknown_contact)

                LabeledValue(stringResource(R.string.share_detail_recipient_label), recipientName)
                Spacer(Modifier.height(4.dp))
                LabeledValue(stringResource(R.string.share_detail_deposited_label), formatDate(uiState.share!!.secretCreatedAt))

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                RequestSection(
                    title = stringResource(R.string.share_request_retrieve),
                    request = uiState.retrieveRequest,
                    isOpening = uiState.isOpeningRetrieve,
                    buttonLabel = stringResource(R.string.share_detail_retrieve_button),
                    onOpen = viewModel::openRetrieveRequest,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                RequestSection(
                    title = stringResource(R.string.share_request_delete),
                    request = uiState.deleteRequest,
                    isOpening = uiState.isOpeningDelete,
                    buttonLabel = stringResource(R.string.share_detail_delete_button),
                    onOpen = viewModel::openDeleteRequest,
                )

                if (uiState.approvedRetrieveCount >= 2 || uiState.reconstructedSecret != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    Text(stringResource(R.string.share_detail_reconstruct_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = pluralStringResource(
                            R.plurals.share_detail_approved_shares,
                            uiState.approvedRetrieveCount,
                            uiState.approvedRetrieveCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (uiState.reconstructedSecret != null) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(
                                text = uiState.reconstructedSecret!!,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        Spacer(Modifier.height(8.dp))
                        if (BuildConfig.SKIP_BIOMETRIC) {
                            OutlinedButton(
                                onClick = { scope.launch { viewModel.reconstruct() } },
                                enabled = !uiState.isReconstructing,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (uiState.isReconstructing) CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                else Text(stringResource(R.string.share_detail_reconstruct_button))
                            }
                        } else {
                            val unavailableMessage = when (availability) {
                                AuthAvailability.Available -> null
                                AuthAvailability.NoneEnrolled ->
                                    stringResource(R.string.share_detail_biometric_none_enrolled)
                                AuthAvailability.NoHardware ->
                                    stringResource(R.string.share_detail_biometric_no_hardware)
                                is AuthAvailability.Unavailable ->
                                    stringResource(R.string.share_detail_biometric_unavailable)
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
                                OutlinedButton(
                                    onClick = {
                                        val act = activity ?: return@OutlinedButton
                                        scope.launch {
                                            val result = authenticate(
                                                activity = act,
                                                title = promptTitle,
                                                subtitle = promptSubtitle,
                                            )
                                            if (result is AuthResult.Succeeded) viewModel.reconstruct()
                                        }
                                    },
                                    enabled = !uiState.isReconstructing && activity != null,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (uiState.isReconstructing) CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    else Text(stringResource(R.string.share_detail_reconstruct_button))
                                }
                            }
                        }
                    }
                }

                if (uiState.actionError != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(uiState.actionError!!),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RequestSection(
    title: String,
    request: ShareRequest?,
    isOpening: Boolean,
    buttonLabel: String,
    onOpen: () -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))

    if (request == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.share_detail_no_request), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpen, enabled = !isOpening) {
                if (isOpening) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(buttonLabel)
            }
        }
    } else {
        val (stateLabel, stateColor) = when (request.state) {
            ShareRequestState.PENDING -> stringResource(R.string.share_request_state_pending) to MaterialTheme.colorScheme.onSurfaceVariant
            ShareRequestState.APPROVED -> stringResource(R.string.share_request_state_approved) to MaterialTheme.colorScheme.primary
            ShareRequestState.DENIED -> stringResource(R.string.share_request_state_denied) to MaterialTheme.colorScheme.error
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(stateLabel, style = MaterialTheme.typography.bodyMedium, color = stateColor)
                Text(
                    text = formatDate(request.requestedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (request.state == ShareRequestState.DENIED) {
                OutlinedButton(onClick = onOpen, enabled = !isOpening) {
                    if (isOpening) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatDate(instant: Instant): String =
    dateFormatter.format(instant.atZone(ZoneId.systemDefault()))
