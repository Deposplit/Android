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
import androidx.compose.material.icons.filled.Shield
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
import com.deposplit.value_objects.KeyConflict
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareTransactionType
import com.deposplit.value_objects.displayName
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
    keyChangedDaysAgo: (ShareRequest) -> Long? = { null },
    contactName: (KeyConflict) -> String? = { null },
    onDismissConflict: (UUID) -> Unit = {},
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

            uiState.error != null && uiState.requests.isEmpty() && uiState.keyConflicts.isEmpty() -> Spacer(Modifier.weight(1f))

            uiState.requests.isEmpty() && uiState.keyConflicts.isEmpty() -> Box(
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
                items(uiState.keyConflicts, key = { it.id }) { conflict ->
                    KeyConflictItem(
                        conflict = conflict,
                        contactName = contactName(conflict) ?: keyPreview(conflict.oldVerifyKey),
                        onDismiss = { onDismissConflict(conflict.id) },
                    )
                }
                items(uiState.requests, key = { it.id }) { request ->
                    val senderContact = uiState.contacts.find { it.verifyKey.contentEquals(request.senderKey) }
                    val senderName = senderContact?.displayName ?: keyPreview(request.senderKey)
                    RequestItem(
                        request = request,
                        senderName = senderName,
                        senderSubtitle = senderContact?.takeIf { it.nickname != null }?.pseudonym,
                        keyChangedDaysAgo = keyChangedDaysAgo(request),
                        isResponding = request.id in uiState.respondingIds,
                        onRespond = { approved -> onRespond(request.id, approved) },
                    )
                }
            }
        }
    }
}

/** Never auto-resolved. Resolving "yes, this really was them" goes through the existing
 * Relink (Key Changed) flow on the Contacts screen, not through anything here; this card only
 * warns and lets the user acknowledge (dismiss) the alert.
 */
@Composable
private fun KeyConflictItem(conflict: KeyConflict, contactName: String, onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    text = stringResource(R.string.requests_key_conflict_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.requests_key_conflict_message, contactName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.requests_key_conflict_detected_at, formatDate(conflict.detectedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.requests_key_conflict_dismiss))
            }
        }
    }
}

@Composable
private fun RequestItem(
    request: ShareRequest,
    senderName: String,
    // The sender's pseudonym, shown only when senderName above is actually a nickname.
    senderSubtitle: String? = null,
    keyChangedDaysAgo: Long?,
    isResponding: Boolean,
    onRespond: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (badgeText, badgeBackground, badgeContent) = when (request.transactionType) {
                    ShareTransactionType.DEPOSIT -> Triple(
                        stringResource(R.string.share_request_deposit),
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    ShareTransactionType.RETRIEVAL -> Triple(
                        stringResource(R.string.share_request_retrieval),
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    ShareTransactionType.REMOVAL -> Triple(
                        stringResource(R.string.share_request_removal),
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.colorScheme.onErrorContainer,
                    )
                    // Never surfaced here — inventory is a self-approved push, consumed
                    // silently by syncInbox's processRecoveryMetadata, not routed through
                    // listPendingRequests.
                    ShareTransactionType.INVENTORY -> Triple(
                        stringResource(R.string.share_request_inventory),
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
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
            if (senderSubtitle != null) {
                Text(
                    text = senderSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = formatDate(request.requestedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Retrieve-approval hardening — the attack signature is key change followed
            // by a quick retrieval request, so nudge toward a fresh out-of-band check.
            if (keyChangedDaysAgo != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = pluralKeyChangedWarning(senderName, keyChangedDaysAgo),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
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

@Composable
private fun pluralKeyChangedWarning(senderName: String, days: Long): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.requests_key_changed_warning, days.toInt(), senderName, days)
