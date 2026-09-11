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
import com.deposplit.value_objects.ShareRequestState
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

// The three-bucket custody-freshness model — see CustodyHeartbeatTuning for the underlying
// windows and the reasoning behind them.
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
    // The contact's pseudonym, shown as a secondary line, but only when recipientName
    // is actually a nickname (i.e. there's something to disambiguate); null otherwise.
    val recipientSubtitle: String? = null,
) {
    val freshnessBucket: FreshnessBucket
        get() = when {
            heartbeatOptedOutAt != null -> FreshnessBucket.UNMONITORED
            lastConfirmedAt != null && Duration.between(lastConfirmedAt, Instant.now()) <= CustodyHeartbeatTuning.lossThreshold -> FreshnessBucket.CONFIRMED
            else -> FreshnessBucket.SILENT_OVERDUE
        }

    // The early nudge — surfaced before a holder actually drops out of n_live, while still
    // comfortably CONFIRMED.
    val isGettingStale: Boolean
        get() = freshnessBucket == FreshnessBucket.CONFIRMED && lastConfirmedAt != null &&
            Duration.between(lastConfirmedAt, Instant.now()) > CustodyHeartbeatTuning.staleWarningThreshold
}

// Graduated n_live health alarm.
enum class SecretHealth { HEALTHY, CAUTION, CRITICAL, LOST, DESTROYING }

data class SecretGroup(
    val secret: Secret,
    val holders: List<HolderStatus>,
) {
    // n_live is the freshness-gated CONFIRMED count, not a raw ShareMetadata-row
    // count: an UNMONITORED holder never alarms, and a SILENT_OVERDUE one drops out (reversibly)
    // instead of being counted as still-live.
    val health: SecretHealth
        get() {
            if (secret.state == SecretState.DESTROYING) return SecretHealth.DESTROYING
            val nLive = holders.count { it.freshnessBucket == FreshnessBucket.CONFIRMED }
            val k = secret.k
            return when {
                nLive < k -> SecretHealth.LOST
                nLive == k -> SecretHealth.CRITICAL
                nLive == k + 1 -> SecretHealth.CAUTION
                else -> SecretHealth.HEALTHY
            }
        }

    // Mirrors what requestAll actually does — it skips a holder whose retrieval row is PENDING or
    // APPROVED — so the press is worth offering while any holder still lacks one, and is a no-op
    // only once nobody is left to ask.
    //
    // Deliberately still enabled once k copies are in: a surplus beyond the threshold is what lets
    // reconstruct cross-check the shares it has, so asking the stragglers is how a "no integrity
    // margin" outcome becomes a confirmed one.
    val canRequestRetrieval: Boolean
        get() = secret.state == SecretState.ACTIVE && holders.any {
            val state = it.retrievalRequest?.state
            state != ShareRequestState.PENDING && state != ShareRequestState.APPROVED
        }

    // Why "Retrieve shares" can't be pressed, or null when it can be. A control that can't work
    // says so in words rather than disappearing.
    @get:StringRes
    val retrievalUnavailableReason: Int?
        get() = when {
            secret.state != SecretState.ACTIVE -> R.string.secret_detail_retrieve_disabled_destroying
            !canRequestRetrieval -> R.string.secret_detail_retrieve_disabled_all_asked
            else -> null
        }

    val approvedRetrievals: Int
        get() = holders.count { it.retrievalRequest?.state == ShareRequestState.APPROVED }

    val canReconstruct: Boolean
        get() = approvedRetrievals >= secret.k

    // How many more holders have to hand a piece back before the secret can be put together.
    val reconstructShortfall: Int
        get() = (secret.k - approvedRetrievals).coerceAtLeast(0)

    // Collected copies are what there is to clear. An ask still waiting for an answer is cleared
    // along with them, but on its own means nothing has been collected yet.
    val canClearCollected: Boolean
        get() = approvedRetrievals > 0
}

data class HeldShareDisplay(
    val share: HeldShare,
    val senderName: String,
    // The contact's pseudonym, shown as a secondary line, but only when senderName is
    // actually a nickname; null otherwise (including when there's no local Contact at all, in
    // which case senderName already falls back to HeldShare's own denormalized senderPseudonym).
    val senderSubtitle: String? = null,
)

enum class HeldSortOrder { DATE, LABEL, SENDER }

/** One card per secret, holders folded in — shared by the Distributed tab and by a single
 * secret's own screen, so both read the same rules off the same rows.
 */
internal fun buildSecretGroups(
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
                val latestRetrieval = contact?.let { holder ->
                    allRequests
                        .filter {
                            it.secretId == share.secretId && it.recipientKey.contentEquals(holder.verifyKey) &&
                                it.transactionType == ShareTransactionType.RETRIEVAL
                        }
                        .maxByOrNull { it.requestedAt }
                }
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

private data class Phase1Result(
    val contacts: List<Contact>,
    val secrets: List<Secret>,
    val distributed: List<ShareMetadata>,
    val held: List<HeldShare>,
    val awaitingRelink: Int,
)

private data class Phase2Result(
    val allRequests: List<ShareRequest>,
    val secrets: List<Secret>,
    val distributed: List<ShareMetadata>,
    val held: List<HeldShare>,
    val awaitingRelink: Int,
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
        // How many contacts still hold a key this device no longer signs with. A standing
        // advisory rather than an alarm: it is expected work after a phone switch, and it clears
        // itself as each contact gets back in touch.
        val awaitingRelinkCount: Int = 0,
        @StringRes val error: Int? = null,
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
                        awaitingRelink = contactManagement.contactsAwaitingRelink().size,
                    )
                }
            }
            if (phase1.isFailure) {
                _uiState.update { it.copy(isLoading = false, error = R.string.home_error_fallback) }
                return@launch
            }
            val phase1Result = phase1.getOrThrow()
            val (contacts, secrets, distributed, held) = phase1Result
            _uiState.update {
                it.copy(
                    isLoading = false,
                    awaitingRelinkCount = phase1Result.awaitingRelink,
                    groupedSecrets = buildSecretGroups(secrets, distributed, emptyList(), contacts),
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
                        awaitingRelink = contactManagement.contactsAwaitingRelink().size,
                    )
                }
            }.onSuccess { phase2 ->
                val (allRequests, freshSecrets, freshDistributed, freshHeld) = phase2
                val currentSortOrder = _uiState.value.heldSortOrder
                _uiState.update {
                    it.copy(
                        groupedSecrets = buildSecretGroups(freshSecrets, freshDistributed, allRequests, contacts),
                        heldShares = toDisplayList(freshHeld, contacts, currentSortOrder),
                        // The sync may itself be the evidence that clears someone.
                        awaitingRelinkCount = phase2.awaitingRelink,
                    )
                }
            }.onFailure {
                _uiState.update { it.copy(syncWarning = true) }
            }
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
