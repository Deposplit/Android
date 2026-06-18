package com.deposplit.ui.home

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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.deposplit.DeposplitApp
import com.deposplit.R
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
                        Icon(Icons.Default.Person, contentDescription = stringResource(R.string.contacts_title))
                    }
                    IconButton(onClick = {
                        if (selectedTab == 2) requestsViewModel.load() else viewModel.load()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToDeposit) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_action_new_secret))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
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

            if (selectedTab != 2 && uiState.syncWarning) {
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.home_sync_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (selectedTab == 2) {
                RecipientRequestsTab(
                    uiState = requestsUiState,
                    onRetry = requestsViewModel::load,
                    onRespond = requestsViewModel::respond,
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
                                items(uiState.groupedSecrets, key = { it.secretId }) { group ->
                                    SecretGroupCard(
                                        group = group,
                                        isExpanded = uiState.expandedSecretId == group.secretId,
                                        isRequestingAll = group.secretId in uiState.requestingAllIds,
                                        onToggle = { viewModel.toggleExpand(group.secretId) },
                                        onRequestAll = { viewModel.requestAll(group.secretId) },
                                        onHolderClick = onNavigateToShareDetail,
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
            it.share.senderKey.contentEquals(display.share.senderKey)
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
                                viewModel.deleteAllFromSender(display.share.senderKey)
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
}

@Composable
private fun SecretGroupCard(
    group: SecretGroup,
    isExpanded: Boolean,
    isRequestingAll: Boolean,
    onToggle: () -> Unit,
    onRequestAll: () -> Unit,
    onHolderClick: (UUID) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onToggle) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = formatDate(group.createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                val canRequest = group.holders.any {
                    val state = it.retrieveRequest?.state
                    state != ShareRequestState.PENDING && state != ShareRequestState.APPROVED
                }
                Button(
                    onClick = onRequestAll,
                    enabled = canRequest && !isRequestingAll,
                    modifier = Modifier.fillMaxWidth(),
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
            }
        }
    }
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
        }
        holder.retrieveRequest?.let { req ->
            val (labelRes, color) = when (req.state) {
                ShareRequestState.PENDING -> R.string.share_request_state_pending to MaterialTheme.colorScheme.onSurfaceVariant
                ShareRequestState.APPROVED -> R.string.share_request_state_approved to MaterialTheme.colorScheme.primary
                ShareRequestState.DENIED -> R.string.share_request_state_denied to MaterialTheme.colorScheme.error
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
private fun ShareItem(
    label: String,
    createdAt: Instant,
    sender: String? = null,
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
