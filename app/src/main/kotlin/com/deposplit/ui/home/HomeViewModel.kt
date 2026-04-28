package com.deposplit.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.api.HeldShare
import com.deposplit.api.Role
import com.deposplit.api.ShareRepository
import com.deposplit.api.ShareRequest
import com.deposplit.api.ShareRequestState
import com.deposplit.api.ShareRequestType
import com.deposplit.api.ShareTransport
import com.deposplit.contacts.ContactRepository
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
    val pickedUpAt: String?,
    val retrieveRequest: ShareRequest?,
)

data class SecretGroup(
    val secretId: UUID,
    val label: String,
    val createdAt: String,
    val holders: List<HolderStatus>,
)

data class HeldShareDisplay(
    val share: HeldShare,
    val senderName: String,
)

enum class HeldSortOrder { DATE, LABEL, SENDER }

class HomeViewModel(
    private val transport: ShareTransport,
    private val shareRepository: ShareRepository,
    private val contactRepository: ContactRepository,
) : ViewModel() {

    data class UiState(
        val groupedSecrets: List<SecretGroup> = emptyList(),
        val heldShares: List<HeldShareDisplay> = emptyList(),
        val heldSortOrder: HeldSortOrder = HeldSortOrder.DATE,
        val isLoading: Boolean = false,
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
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val distributed = transport.listShares(Role.SENDER)
                    val allRequests = transport.listShareRequests(Role.SENDER)
                    val contacts = contactRepository.getAll()
                    val inbox = transport.listShares(Role.RECIPIENT)
                    for (meta in inbox) {
                        if (shareRepository.getCiphertext(meta.id) == null) {
                            runCatching {
                                val ciphertext = transport.pickUpShare(meta.id)
                                shareRepository.save(
                                    HeldShare(
                                        id = meta.id,
                                        secretId = meta.secretId,
                                        label = meta.label,
                                        senderKey = meta.senderKey,
                                        createdAt = meta.createdAt,
                                        ciphertext = ciphertext,
                                    )
                                )
                            }
                            // Silently skip: relay may have already cleared it on a previous run
                        }
                    }
                    val grouped = distributed
                        .groupBy { it.secretId }
                        .map { (secretId, shares) ->
                            val first = shares.first()
                            val holders = shares.map { share ->
                                val name = contacts
                                    .find { it.edPublicKey.contentEquals(share.recipientKey) }
                                    ?.pseudonym ?: "?"
                                val latestRetrieve = allRequests
                                    .filter {
                                        it.share.id == share.id &&
                                            it.requestType == ShareRequestType.RETRIEVE
                                    }
                                    .maxByOrNull { it.requestedAt }
                                HolderStatus(
                                    shareId = share.id,
                                    recipientName = name,
                                    pickedUpAt = share.pickedUpAt,
                                    retrieveRequest = latestRetrieve,
                                )
                            }
                            SecretGroup(
                                secretId = secretId,
                                label = first.label,
                                createdAt = first.createdAt,
                                holders = holders,
                            )
                        }
                        .sortedByDescending { it.createdAt }
                    val held = shareRepository.getAll()
                        .map { share ->
                            val name = contacts
                                .find { it.edPublicKey.contentEquals(share.senderKey) }
                                ?.pseudonym ?: "?"
                            HeldShareDisplay(share = share, senderName = name)
                        }
                        .sortedWith(sortComparator(sortOrder))
                    grouped to held
                }
            }
                .onSuccess { (grouped, held) ->
                    _uiState.update {
                        it.copy(isLoading = false, groupedSecrets = grouped, heldShares = held)
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, error = R.string.home_error_fallback) }
                }
        }
    }

    fun toggleExpand(secretId: UUID) {
        _uiState.update {
            it.copy(expandedSecretId = if (it.expandedSecretId == secretId) null else secretId)
        }
    }

    fun requestAll(secretId: UUID) {
        val group = _uiState.value.groupedSecrets.find { it.secretId == secretId } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(requestingAllIds = it.requestingAllIds + secretId) }
            withContext(Dispatchers.IO) {
                for (holder in group.holders) {
                    val state = holder.retrieveRequest?.state
                    if (state != ShareRequestState.PENDING && state != ShareRequestState.APPROVED) {
                        runCatching { transport.openShareRequest(holder.shareId, ShareRequestType.RETRIEVE) }
                    }
                }
            }
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
            withContext(Dispatchers.IO) { shareRepository.delete(shareId) }
            load()
        }
    }

    fun deleteAllFromSender(senderKey: ByteArray) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                shareRepository.getAll()
                    .filter { it.senderKey.contentEquals(senderKey) }
                    .forEach { shareRepository.delete(it.id) }
            }
            load()
        }
    }

    private fun sortComparator(order: HeldSortOrder): Comparator<HeldShareDisplay> = when (order) {
        HeldSortOrder.DATE -> compareByDescending { it.share.createdAt }
        HeldSortOrder.LABEL -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.share.label }
        HeldSortOrder.SENDER -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.senderName }
    }
}
