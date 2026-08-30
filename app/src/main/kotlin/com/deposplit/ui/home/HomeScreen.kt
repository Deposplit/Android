package com.deposplit.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deposplit.DeposplitApp
import com.deposplit.R
import com.deposplit.value_objects.SecretState
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.ui.requests.RecipientRequestsTab
import com.deposplit.ui.requests.RequestsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToContacts: () -> Unit,
    onNavigateToDeposit: () -> Unit,
    onNavigateToShareDetail: (UUID) -> Unit,
    onNavigateToQrDisplay: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToRepair: (UUID) -> Unit,
) {
    val app = LocalContext.current.applicationContext as DeposplitApp
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(app.shareManagement, app.contactManagement) }
        }
    )
    val requestsViewModel: RequestsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { RequestsViewModel(app.shareManagement, app.contactManagement) }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val requestsUiState by requestsViewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<HeldShareDisplay?>(null) }
    var pendingDiscard by remember { mutableStateOf<SecretGroup?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.load()
        requestsViewModel.load()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onNavigateToQrDisplay) {
                        Icon(Icons.Default.QrCode, contentDescription = stringResource(R.string.home_action_qr_code))
                    }
                    IconButton(onClick = onNavigateToContacts) {
                        Icon(Icons.Default.Group, contentDescription = stringResource(R.string.contacts_title))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                    IconButton(onClick = {
                        if (selectedTab == 2) requestsViewModel.load() else viewModel.load()
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.action_refresh),
                            tint = if (uiState.syncWarning) MaterialTheme.colorScheme.error else LocalContentColor.current,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToDeposit) {
                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.home_action_new_secret))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (uiState.syncWarning) {
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
                        text = stringResource(R.string.home_sync_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.home_tab_distributed)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.home_tab_held)) },
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(stringResource(R.string.home_tab_requests)) },
                )
            }

            if (selectedTab == 2) {
                RecipientRequestsTab(
                    uiState = requestsUiState,
                    onRetry = requestsViewModel::load,
                    onRespond = requestsViewModel::respond,
                    keyChangedDaysAgo = requestsViewModel::keyChangedDaysAgo,
                    contactName = requestsViewModel::contactName,
                    onDismissConflict = requestsViewModel::dismissConflict,
                )
            } else {
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
                                text = stringResource(uiState.error!!),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = viewModel::load) { Text(stringResource(R.string.action_retry)) }
                        }
                    }

                    else -> {
                        val isEmpty = if (selectedTab == 0) uiState.groupedSecrets.isEmpty()
                                      else uiState.heldShares.isEmpty()
                        if (isEmpty) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = if (selectedTab == 0) stringResource(R.string.home_empty_distributed)
                                           else stringResource(R.string.home_empty_held),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (selectedTab == 0) {
                            LazyColumn(
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(uiState.groupedSecrets, key = { it.secret.id }) { group ->
                                    SecretGroupCard(
                                        group = group,
                                        isExpanded = uiState.expandedSecretId == group.secret.id,
                                        isRequestingAll = group.secret.id in uiState.requestingAllIds,
                                        onToggle = { viewModel.toggleExpand(group.secret.id) },
                                        onRequestAll = { viewModel.requestAll(group.secret.id) },
                                        onHolderClick = onNavigateToShareDetail,
                                        onDiscard = { pendingDiscard = group },
                                        onForceForget = { viewModel.forceForgetSecret(group.secret.id) },
                                        onRepair = { onNavigateToRepair(group.secret.id) },
                                    )
                                }
                            }
                        } else {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    HeldSortOrder.entries.forEach { order ->
                                        FilterChip(
                                            selected = uiState.heldSortOrder == order,
                                            onClick = { viewModel.setHeldSortOrder(order) },
                                            label = {
                                                Text(
                                                    stringResource(
                                                        when (order) {
                                                            HeldSortOrder.DATE -> R.string.home_sort_date
                                                            HeldSortOrder.LABEL -> R.string.home_sort_label
                                                            HeldSortOrder.SENDER -> R.string.home_sort_sender
                                                        }
                                                    )
                                                )
                                            },
                                        )
                                    }
                                }
                                LazyColumn(
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    items(uiState.heldShares, key = { it.share.id }) { display ->
                                        ShareItem(
                                            label = display.share.label,
                                            createdAt = display.share.createdAt,
                                            sender = display.senderName,
                                            senderSubtitle = display.senderSubtitle,
                                            onDelete = { pendingDelete = display },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { display ->
        val senderCount = uiState.heldShares.count {
            it.share.contactId == display.share.contactId
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.home_held_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.home_held_delete_body))
                    if (senderCount > 1) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                viewModel.deleteAllFromSender(display.share.contactId)
                                pendingDelete = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.home_held_delete_all_from, display.senderName))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSingleShare(display.share.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.home_held_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    pendingDiscard?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDiscard = null },
            title = { Text(stringResource(R.string.home_discard_title)) },
            text = { Text(stringResource(R.string.home_discard_body, group.holders.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.discardSecret(group.secret.id)
                    pendingDiscard = null
                }) {
                    Text(stringResource(R.string.home_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscard = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SecretGroupCard(
    group: SecretGroup,
    isExpanded: Boolean,
    isRequestingAll: Boolean,
    onToggle: () -> Unit,
    onRequestAll: () -> Unit,
    onHolderClick: (UUID) -> Unit,
    onDiscard: () -> Unit,
    onForceForget: () -> Unit,
    onRepair: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onToggle) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.secret.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = formatDate(group.secret.secretCreatedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HealthBadge(group.health)
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isExpanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                group.holders.forEach { holder ->
                    HolderRow(holder = holder, onClick = { onHolderClick(holder.shareId) })
                }
                Spacer(Modifier.height(12.dp))
                val isDiscarding = group.secret.state == SecretState.DISCARDING
                val canRequest = !isDiscarding && group.holders.any {
                    val state = it.retrievalRequest?.state
                    state != ShareRequestState.PENDING && state != ShareRequestState.APPROVED
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = onRequestAll,
                        enabled = canRequest && !isRequestingAll,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isRequestingAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(R.string.home_request_all))
                        }
                    }
                    if (group.health == SecretHealth.CAUTION || group.health == SecretHealth.CRITICAL) {
                        Button(
                            onClick = onRepair,
                            colors = if (group.health == SecretHealth.CRITICAL) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                        ) {
                            Text(stringResource(R.string.home_repair_button))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (isDiscarding) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.home_discarding_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        TextButton(onClick = onForceForget) {
                            Text(stringResource(R.string.home_force_forget_button))
                        }
                    }
                } else {
                    TextButton(onClick = onDiscard) {
                        Text(stringResource(R.string.home_discard_button), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthBadge(health: SecretHealth) {
    val (labelRes, color) = when (health) {
        SecretHealth.HEALTHY -> return
        SecretHealth.DISCARDING -> R.string.home_health_discarding to MaterialTheme.colorScheme.tertiary
        SecretHealth.CAUTION -> R.string.home_health_caution to MaterialTheme.colorScheme.tertiary
        SecretHealth.CRITICAL -> R.string.home_health_critical to MaterialTheme.colorScheme.error
        SecretHealth.LOST -> R.string.home_health_lost to MaterialTheme.colorScheme.error
    }
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@Composable
private fun HolderRow(holder: HolderStatus, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(holder.recipientName, style = MaterialTheme.typography.bodyMedium)
            if (holder.recipientSubtitle != null) {
                Text(
                    text = holder.recipientSubtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Early nudge, surfaced before the holder actually drops out of n_live.
            when {
                holder.isGettingStale -> FreshnessLabel(R.string.home_freshness_stale, Icons.Filled.Schedule, MaterialTheme.colorScheme.tertiary)
                holder.freshnessBucket == FreshnessBucket.UNMONITORED -> FreshnessLabel(R.string.home_freshness_unmonitored, Icons.Filled.VisibilityOff, MaterialTheme.colorScheme.onSurfaceVariant)
                holder.freshnessBucket == FreshnessBucket.SILENT_OVERDUE -> FreshnessLabel(R.string.home_freshness_silent, Icons.Filled.Warning, MaterialTheme.colorScheme.error)
                else -> {}
            }
        }
        holder.retrievalRequest?.let { req ->
            val (labelRes, color) = when (req.state) {
                ShareRequestState.PENDING -> R.string.share_request_state_pending to MaterialTheme.colorScheme.onSurfaceVariant
                ShareRequestState.APPROVED -> R.string.share_request_state_approved to MaterialTheme.colorScheme.primary
                ShareRequestState.DENIED -> R.string.share_request_state_denied to MaterialTheme.colorScheme.error
                ShareRequestState.WITHDRAWN -> R.string.share_request_state_withdrawn to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun FreshnessLabel(@StringRes textRes: Int, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(stringResource(textRes), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ShareItem(
    label: String,
    createdAt: Instant,
    sender: String? = null,
    // The sender's pseudonym, shown only when `sender` above is actually a nickname.
    senderSubtitle: String? = null,
    onDelete: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
                end = if (onDelete != null) 4.dp else 16.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = formatDate(createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sender != null) {
                    Text(
                        text = stringResource(R.string.home_held_from, sender),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (senderSubtitle != null) {
                    Text(
                        text = senderSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.home_held_delete_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun formatDate(instant: Instant): String =
    dateFormatter.format(instant.atZone(ZoneId.systemDefault()))
