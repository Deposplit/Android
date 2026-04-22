package com.deposplit.ui.sharedetail

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
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deposplit.DeposplitApp
import com.deposplit.api.ShareRequest
import com.deposplit.api.ShareRequestState
import com.deposplit.api.ShareRequestType
import com.deposplit.contacts.Contact
import com.deposplit.ui.biometric.AuthAvailability
import com.deposplit.ui.biometric.AuthResult
import com.deposplit.ui.biometric.authenticate
import com.deposplit.ui.biometric.biometricAvailability
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
                    auth = app.authAdapter,
                    transport = app.shareTransport,
                    contactRepository = app.contactRepository,
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.share?.label ?: "Share") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                        text = uiState.error!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = viewModel::load) { Text("Retry") }
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
                    .find { it.edPublicKey.contentEquals(uiState.share!!.recipientKey) }
                    ?.pseudonym
                    ?: keyPreview(uiState.share!!.recipientKey)

                LabeledValue("Recipient", recipientName)
                Spacer(Modifier.height(4.dp))
                LabeledValue("Deposited", formatDate(uiState.share!!.createdAt))

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                RequestSection(
                    title = "Retrieve",
                    request = uiState.retrieveRequest,
                    isOpening = uiState.isOpeningRetrieve,
                    buttonLabel = "Request Retrieval",
                    onOpen = viewModel::openRetrieveRequest,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                RequestSection(
                    title = "Delete",
                    request = uiState.deleteRequest,
                    isOpening = uiState.isOpeningDelete,
                    buttonLabel = "Request Deletion",
                    onOpen = viewModel::openDeleteRequest,
                )

                if (uiState.approvedRetrieveCount >= 2 || uiState.reconstructedSecret != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                    Text("Reconstruct Secret", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${uiState.approvedRetrieveCount} approved share(s) available",
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
                        val unavailableMessage = when (availability) {
                            AuthAvailability.Available -> null
                            AuthAvailability.NoneEnrolled ->
                                "Enrol a biometric (fingerprint or face) in device settings to reconstruct the secret."
                            AuthAvailability.NoHardware ->
                                "This device has no biometric sensor — reconstruction is disabled."
                            is AuthAvailability.Unavailable ->
                                "Biometric authentication is currently unavailable."
                        }
                        if (unavailableMessage != null) {
                            Text(
                                text = unavailableMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedButton(
                                onClick = {
                                    val act = activity ?: return@OutlinedButton
                                    scope.launch {
                                        val result = authenticate(
                                            activity = act,
                                            title = "Unlock to reconstruct",
                                            subtitle = "Confirm it's you before the secret is shown.",
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
                                else Text("Reconstruct")
                            }
                        }
                    }
                }

                if (uiState.actionError != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = uiState.actionError!!,
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
            Text("No request", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpen, enabled = !isOpening) {
                if (isOpening) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(buttonLabel)
            }
        }
    } else {
        val (stateLabel, stateColor) = when (request.state) {
            ShareRequestState.PENDING -> "Pending" to MaterialTheme.colorScheme.onSurfaceVariant
            ShareRequestState.APPROVED -> "Approved" to MaterialTheme.colorScheme.primary
            ShareRequestState.DENIED -> "Denied" to MaterialTheme.colorScheme.error
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
                    else Text("Retry")
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

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun formatDate(iso: String): String = runCatching {
    dateFormatter.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso)

private fun keyPreview(key: ByteArray): String =
    key.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) } + "…"
