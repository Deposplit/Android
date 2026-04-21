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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deposplit.api.ShareRequest
import com.deposplit.api.ShareRequestType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun RecipientRequestsTab(
    uiState: RequestsViewModel.UiState,
    onRetry: () -> Unit,
    onRespond: (UUID, Boolean) -> Unit,
) {
    when {
        uiState.isLoading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        uiState.error != null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRetry) { Text("Retry") }
            }
        }

        uiState.requests.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No pending requests",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(uiState.requests, key = { it.id }) { request ->
                val senderName = uiState.contacts
                    .find { it.edPublicKey.contentEquals(request.share.senderKey) }
                    ?.pseudonym
                    ?: keyPreview(request.share.senderKey)
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
                val isRetrieve = request.requestType == ShareRequestType.RETRIEVE
                Surface(
                    color = if (isRetrieve) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = if (isRetrieve) "Retrieve" else "Delete",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isRetrieve) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Text(
                    text = request.share.label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "From: $senderName",
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
                    else Text("Deny")
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
                    else Text("Approve")
                }
            }
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

private fun formatDate(iso: String): String = runCatching {
    dateFormatter.format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault(iso)

private fun keyPreview(key: ByteArray): String =
    key.take(4).joinToString("") { "%02x".format(it.toInt() and 0xff) } + "…"
