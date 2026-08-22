package com.deposplit.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.CustodyHeartbeatTuning
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.SecretState
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareTransactionType
import com.deposplit.value_objects.displayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.UUID

// Item 12's three-bucket freshness model — see CustodyHeartbeatTuning for the underlying windows
// and deposplit.com/CLAUDE.md "What is next" item 12 for the rationale.
enum class FreshnessBucket {
    // Proof-of-custody (heartbeat, pickup, or retrieve approval) observed within
    // CustodyHeartbeatTuning.lossThreshold. Counts toward n_live.
    CONFIRMED,
    // The holder sent a signed opt-out notice — never a loss alarm, shown as a standing advisory
    // instead. Does not count toward n_live.
    UNMONITORED,
    // Expected proof-of-custody hasn't arrived within the loss threshold (or never has). Drops
    // out of n_live — reversible the moment a fresh heartbeat/approval is observed.
    SILENT_OVERDUE,
}

data class HolderStatus(
    val shareId: UUID,
    val contactId: UUID,
    val recipientName: String,
    val retrievalRequest: ShareRequest?,
    val lastConfirmedAt: Instant?,
    val heartbeatOptedOutAt: Instant?,
    // Item 15 — the contact's pseudonym, shown as a secondary line, but only when recipientName
    // is actually a nickname (i.e. there's something to disambiguate); null otherwise.
    val recipientSubtitle: String? = null,
) {
    val freshnessBucket: FreshnessBucket
        get() = when {
            heartbeatOptedOutAt != null -> FreshnessBucket.UNMONITORED
            lastConfirmedAt != null && Duration.between(lastConfirmedAt, Instant.now()) <= CustodyHeartbeatTuning.lossThreshold -> FreshnessBucket.CONFIRMED
            else -> FreshnessBucket.SILENT_OVERDUE
        }

    // Item 12's early nudge — surfaced before a holder actually drops out of n_live, while still
    // comfortably CONFIRMED.
    val isGettingStale: Boolean
        get() = freshnessBucket == FreshnessBucket.CONFIRMED && lastConfirmedAt != null &&
            Duration.between(lastConfirmedAt, Instant.now()) > CustodyHeartbeatTuning.staleWarningThreshold
}

// Graduated n_live health alarm — see deposplit.com/CLAUDE.md "What is next" item 11.
enum class SecretHealth { HEALTHY, CAUTION, CRITICAL, LOST, DISCARDING }

data class SecretGroup(
    val secret: Secret,
    val holders: List<HolderStatus>,
) {
    // Item 12 — n_live is now the freshness-gated CONFIRMED count, not a raw ShareMetadata-row
    // count: an UNMONITORED holder never alarms, and a SILENT_OVERDUE one drops out (reversibly)
    // instead of being counted as still-live.
    val health: SecretHealth
        get() {
            if (secret.state == SecretState.DISCARDING) return SecretHealth.DISCARDING
            val nLive = holders.count { it.freshnessBucket == FreshnessBucket.CONFIRMED }
            val k = secret.k
            return when {
                nLive < k -> SecretHealth.LOST
                nLive == k -> SecretHealth.CRITICAL
                nLive == k + 1 -> SecretHealth.CAUTION
                else -> SecretHealth.HEALTHY
            }
        }
}

data class HeldShareDisplay(
    val share: HeldShare,
    val senderName: String,
    // Item 15 — the contact's pseudonym, shown as a secondary line, but only when senderName is
    // actually a nickname; null otherwise (including when there's no local Contact at all, in
    // which case senderName already falls back to HeldShare's own denormalized senderPseudonym).
    val senderSubtitle: String? = null,
)

enum class HeldSortOrder { DATE, LABEL, SENDER }

private data class Phase1Result(
    val contacts: List<Contact>,
    val secrets: List<Secret>,
    val distributed: List<ShareMetadata>,
    val held: List<HeldShare>,
)

private data class Phase2Result(
    val allRequests: List<ShareRequest>,
    val secrets: List<Secret>,
    val distributed: List<ShareMetadata>,
    val held: List<HeldShare>,
)

class HomeViewModel(
    private val shareManagement: ShareManagement,
    private val contactManagement: ContactManagement,
) : ViewModel() {

    data class UiState(
        val groupedSecrets: List<SecretGroup> = emptyList(),
        val heldShares: List<HeldShareDisplay> = emptyList(),
        val heldSortOrder: HeldSortOrder = HeldSortOrder.DATE,
        val isLoading: Boolean = false,
        val syncWarning: Boolean = false,
        @StringRes val error: Int? = null,
        val expandedSecretId: UUID? = null,
        val requestingAllIds: Set<UUID> = emptySet(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        val sortOrder = _uiState.value.heldSortOrder
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, syncWarning = false) }

            // Phase 1: local data only — renders immediately even when offline
            val phase1 = runCatching {
                withContext(Dispatchers.IO) {
                    Phase1Result(
                        contacts = contactManagement.listContacts(),
                        secrets = shareManagement.listSecrets(),
                        distributed = shareManagement.listDistributed(),
                        held = shareManagement.listHeld(),
                    )
                }
            }
            if (phase1.isFailure) {
                _uiState.update { it.copy(isLoading = false, error = R.string.home_error_fallback) }
                return@launch
            }
            val (contacts, secrets, distributed, held) = phase1.getOrThrow()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    groupedSecrets = buildGroups(secrets, distributed, emptyList(), contacts),
                    heldShares = toDisplayList(held, contacts, sortOrder),
                )
            }

            // Phase 2: relay sync — soft failure, shows warning banner without wiping the lists
            runCatching {
                withContext(Dispatchers.IO) {
                    shareManagement.syncInbox()
                    shareManagement.syncDistributed()
                    Phase2Result(
                        allRequests = shareManagement.listSentRequests(),
                        secrets = shareManagement.listSecrets(),
                        distributed = shareManagement.listDistributed(),
                        held = shareManagement.listHeld(),
                    )
                }
            }.onSuccess { (allRequests, freshSecrets, freshDistributed, freshHeld) ->
                val currentSortOrder = _uiState.value.heldSortOrder
                _uiState.update {
                    it.copy(
                        groupedSecrets = buildGroups(freshSecrets, freshDistributed, allRequests, contacts),
                        heldShares = toDisplayList(freshHeld, contacts, currentSortOrder),
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(syncWarning = true) }
            }
        }
    }

    fun toggleExpand(secretId: UUID) {
        _uiState.update {
            it.copy(expandedSecretId = if (it.expandedSecretId == secretId) null else secretId)
        }
    }

    fun requestAll(secretId: UUID) {
        viewModelScope.launch {
            _uiState.update { it.copy(requestingAllIds = it.requestingAllIds + secretId) }
            withContext(Dispatchers.IO) { runCatching { shareManagement.requestAll(secretId) } }
            _uiState.update { it.copy(requestingAllIds = it.requestingAllIds - secretId) }
            load()
        }
    }

    fun discardSecret(secretId: UUID) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { shareManagement.discardSecret(secretId) } }
            load()
        }
    }

    fun forceForgetSecret(secretId: UUID) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { shareManagement.forceForgetSecret(secretId) } }
            load()
        }
    }

    fun setHeldSortOrder(order: HeldSortOrder) {
        _uiState.update { state ->
            state.copy(
                heldSortOrder = order,
                heldShares = state.heldShares.sortedWith(sortComparator(order)),
            )
        }
    }

    fun deleteSingleShare(shareId: UUID) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { shareManagement.deleteHeldShare(shareId) }
            load()
        }
    }

    fun deleteAllFromSender(contactId: UUID) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { shareManagement.deleteAllHeldFromSender(contactId) }
            load()
        }
    }

    private fun buildGroups(
        secrets: List<Secret>,
        distributed: List<ShareMetadata>,
        allRequests: List<ShareRequest>,
        contacts: List<Contact>,
    ): List<SecretGroup> {
        val byShareSecretId = distributed.groupBy { it.secretId }
        return secrets
            .map { secret ->
                val shares = byShareSecretId[secret.id] ?: emptyList()
                val holders = shares.map { share ->
                    val contact = contacts.find { it.id == share.contactId }
                    val latestRetrieval = allRequests
                        .filter { it.shareId == share.id && it.transactionType == ShareTransactionType.RETRIEVAL }
                        .maxByOrNull { it.requestedAt }
                    HolderStatus(
                        shareId = share.id,
                        contactId = share.contactId,
                        recipientName = contact?.displayName ?: "?",
                        retrievalRequest = latestRetrieval,
                        lastConfirmedAt = share.lastConfirmedAt,
                        heartbeatOptedOutAt = contact?.heartbeatOptedOutAt,
                        recipientSubtitle = contact?.takeIf { it.nickname != null }?.pseudonym,
                    )
                }
                SecretGroup(secret = secret, holders = holders)
            }
            .sortedByDescending { it.secret.secretCreatedAt }
    }

    private fun toDisplayList(
        held: List<HeldShare>,
        contacts: List<Contact>,
        order: HeldSortOrder,
    ): List<HeldShareDisplay> = held
        .map { share ->
            val contact = contacts.find { it.id == share.contactId }
            val name = contact?.displayName ?: share.senderPseudonym
            HeldShareDisplay(share = share, senderName = name, senderSubtitle = contact?.takeIf { it.nickname != null }?.pseudonym)
        }
        .sortedWith(sortComparator(order))

    private fun sortComparator(order: HeldSortOrder): Comparator<HeldShareDisplay> = when (order) {
        HeldSortOrder.DATE -> compareByDescending { it.share.createdAt }
        HeldSortOrder.LABEL -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.share.label }
        HeldSortOrder.SENDER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.senderName }
    }
}
