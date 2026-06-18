package com.deposplit.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class HolderStatus(
    val shareId: UUID,
    val recipientName: String,
    val retrieveRequest: ShareRequest?,
)

data class SecretGroup(
    val secretId: UUID,
    val label: String,
    val createdAt: Instant,
    val holders: List<HolderStatus>,
)

data class HeldShareDisplay(
    val share: HeldShare,
    val senderName: String,
)

enum class HeldSortOrder { DATE, LABEL, SENDER }

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
                    Triple(
                        contactManagement.listContacts(),
                        shareManagement.listDistributed(),
                        shareManagement.listHeld(),
                    )
                }
            }
            if (phase1.isFailure) {
                _uiState.update { it.copy(isLoading = false, error = R.string.home_error_fallback) }
                return@launch
            }
            val (contacts, distributed, held) = phase1.getOrThrow()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    groupedSecrets = buildGroups(distributed, emptyList(), contacts),
                    heldShares = toDisplayList(held, contacts, sortOrder),
                )
            }

            // Phase 2: relay sync — soft failure, shows warning banner without wiping the lists
            runCatching {
                withContext(Dispatchers.IO) {
                    shareManagement.syncInbox()
                    shareManagement.syncDistributed()
                    Triple(
                        shareManagement.listSentRequests(),
                        shareManagement.listDistributed(),
                        shareManagement.listHeld(),
                    )
                }
            }.onSuccess { (allRequests, freshDistributed, freshHeld) ->
                val currentSortOrder = _uiState.value.heldSortOrder
                _uiState.update {
                    it.copy(
                        groupedSecrets = buildGroups(freshDistributed, allRequests, contacts),
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

    fun deleteAllFromSender(senderKey: ByteArray) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { shareManagement.deleteAllHeldFromSender(senderKey) }
            load()
        }
    }

    private fun buildGroups(
        distributed: List<ShareMetadata>,
        allRequests: List<ShareRequest>,
        contacts: List<Contact>,
    ): List<SecretGroup> = distributed
        .groupBy { it.secretId }
        .map { (secretId, shares) ->
            val first = shares.first()
            val holders = shares.map { share ->
                val name = contacts.find { it.edPublicKey.contentEquals(share.recipientKey) }?.pseudonym ?: "?"
                val latestRetrieve = allRequests
                    .filter { it.shareId == share.id && it.requestType == ShareRequestType.RETRIEVE }
                    .maxByOrNull { it.requestedAt }
                HolderStatus(
                    shareId = share.id,
                    recipientName = name,
                    retrieveRequest = latestRetrieve,
                )
            }
            SecretGroup(
                secretId = secretId,
                label = first.label,
                createdAt = first.secretCreatedAt,
                holders = holders,
            )
        }
        .sortedByDescending { it.createdAt }

    private fun toDisplayList(
        held: List<HeldShare>,
        contacts: List<Contact>,
        order: HeldSortOrder,
    ): List<HeldShareDisplay> = held
        .map { share ->
            val name = contacts.find { it.edPublicKey.contentEquals(share.senderKey) }?.pseudonym ?: "?"
            HeldShareDisplay(share = share, senderName = name)
        }
        .sortedWith(sortComparator(order))

    private fun sortComparator(order: HeldSortOrder): Comparator<HeldShareDisplay> = when (order) {
        HeldSortOrder.DATE -> compareByDescending { it.share.createdAt }
        HeldSortOrder.LABEL -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.share.label }
        HeldSortOrder.SENDER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.senderName }
    }
}
