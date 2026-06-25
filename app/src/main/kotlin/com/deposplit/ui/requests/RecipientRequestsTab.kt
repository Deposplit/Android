package com.deposplit.ui.requests

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.deposplit.R
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

@Composable
fun RecipientRequestsTab(
    uiState: RequestsViewModel.UiState,
    onRetry: () -> Unit,
    onRespond: (UUID, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.error != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(uiState.error!!),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        when {
            uiState.isLoading -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            uiState.error != null && uiState.requests.isEmpty() -> Spacer(Modifier.weight(1f))

            uiState.requests.isEmpty() -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.requests_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(uiState.requests, key = { it.id }) { request ->
                    val senderName = uiState.contacts
                        .find { it.edPublicKey.contentEquals(request.senderKey) }
                        ?.pseudonym
                        ?: keyPreview(request.senderKey)
                    RequestItem(
                        request = request,
                        senderName = senderName,
                        isResponding = request.id in uiState.respondingIds,
                        onRespond = { approved -> onRespond(request.id, approved) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RequestItem(
    request: ShareRequest,
    senderName: String,
    isResponding: Boolean,
    onRespond: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (badgeText, badgeBackground, badgeContent) = when (request.requestType) {
                    ShareRequestType.PICK_UP -> Triple(
                        stringResource(R.string.share_request_pick_up),
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    ShareRequestType.RETRIEVE -> Triple(
                        stringResource(R.string.share_request_retrieve),
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    ShareRequestType.DELETE -> Triple(
                        stringResource(R.string.share_request_delete),
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Surface(color = badgeBackground, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = badgeText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeContent,
                    )
                }
                Text(
                    text = request.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.requests_from, senderName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDate(request.requestedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onRespond(false) },
                    enabled = !isResponding,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isResponding) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.requests_action_deny))
                }
                Button(
                    onClick = { onRespond(true) },
                    enabled = !isResponding,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isResponding) CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    else Text(stringResource(R.string.requests_action_approve))
                }
            }
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatDate(instant: Instant): String =
    dateFormatter.format(instant.atZone(ZoneId.systemDefault()))

private fun keyPreview(key: ByteArray): String =
    key.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) } + "…"
