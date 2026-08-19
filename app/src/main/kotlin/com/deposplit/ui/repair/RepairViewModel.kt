package com.deposplit.ui.repair

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.ui.deposit.DepositViewModel
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareTransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class RepairPhase { GATHERING, RECONSTRUCTING, REDEPOSIT, CONFIRM_DISCARD, DONE }

/**
 * Item 9's "reconstruct-and-re-split" repair flow — composes three already-existing primitives
 * (`reconstruct`, `deposit`, `discardSecret`) that were previously only reachable from three
 * disconnected screens. See deposplit.com/CLAUDE.md "What is next" item 9 and item 11 (the
 * primitives this composes) and item 12 (the freshness-gated health signal that now gives this
 * flow a reason to be surfaced).
 */
class RepairViewModel(
    private val secretId: UUID,
    private val shareManagement: ShareManagement,
    private val contactManagement: ContactManagement,
) : ViewModel() {

    data class HolderRetrievalStatus(
        val contactId: UUID,
        val pseudonym: String,
        val requestState: ShareRequestState?,
    )

    data class UiState(
        val phase: RepairPhase = RepairPhase.GATHERING,
        val secret: Secret? = null,
        val isLoading: Boolean = false,
        val isActing: Boolean = false,
        val holderStatuses: List<HolderRetrievalStatus> = emptyList(),
        val approvedCount: Int = 0,
        val depositedHolderCount: Int = 0,
        val prefill: DepositViewModel.Prefill? = null,
        @StringRes val error: Int? = null,
    ) {
        val readyToReconstruct: Boolean get() = secret != null && approvedCount >= secret.k
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val secret = shareManagement.listSecrets().find { it.id == secretId }
                    val distributed = shareManagement.listDistributed().filter { it.secretId == secretId }
                    val contacts = contactManagement.listContacts()
                    val requests = shareManagement.listSentRequests().filter { it.secretId == secretId }
                    val holders = distributed.map { share ->
                        val contact = contacts.find { it.id == share.contactId }
                        val latestRetrieval = requests
                            .filter { it.shareId == share.id && it.transactionType == ShareTransactionType.RETRIEVAL }
                            .maxByOrNull { it.requestedAt }
                        HolderRetrievalStatus(
                            contactId = share.contactId,
                            pseudonym = contact?.pseudonym ?: "?",
                            requestState = latestRetrieval?.state,
                        )
                    }
                    val approved = requests.count {
                        it.transactionType == ShareTransactionType.RETRIEVAL &&
                            it.state == ShareRequestState.APPROVED &&
                            it.ciphertext != null
                    }
                    Triple(secret, holders, approved)
                }
            }
                .onSuccess { (secret, holders, approved) ->
                    _uiState.update {
                        it.copy(isLoading = false, secret = secret, holderStatuses = holders, approvedCount = approved)
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, error = R.string.repair_error_load) }
                }
        }
    }

    // Opens retrieval requests for any holder of this secret without one already outstanding.
    // Safe to call repeatedly — requestAll never opens a duplicate for a holder that already has
    // a pending/approved retrieval request for this secret.
    fun requestMissingRetrievals() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActing = true) }
            withContext(Dispatchers.IO) { runCatching { shareManagement.requestAll(secretId) } }
            _uiState.update { it.copy(isActing = false) }
            load()
        }
    }

    fun reconstruct() {
        val state = _uiState.value
        val secret = state.secret ?: return
        if (!state.readyToReconstruct) return
        _uiState.update { it.copy(phase = RepairPhase.RECONSTRUCTING, isActing = true, error = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { shareManagement.reconstruct(secretId) }
            }
                .onSuccess { secretBytes ->
                    val currentHolderIds = state.holderStatuses.map { it.contactId }.toSet()
                    val prefill = DepositViewModel.Prefill(
                        label = secret.label,
                        secretText = secretBytes.toString(Charsets.UTF_8),
                        selectedContactIds = currentHolderIds,
                        threshold = secret.k,
                    )
                    _uiState.update { it.copy(isActing = false, phase = RepairPhase.REDEPOSIT, prefill = prefill) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(isActing = false, phase = RepairPhase.GATHERING, error = R.string.repair_error_reconstruct)
                    }
                }
        }
    }

    // Called once the embedded re-deposit form reports success. Drops the transient prefill (and
    // with it the only remaining in-memory copy of the reconstructed plaintext) immediately,
    // since nothing needs it past this point.
    fun newDepositSucceeded() {
        _uiState.update {
            it.copy(
                phase = RepairPhase.CONFIRM_DISCARD,
                depositedHolderCount = it.prefill?.selectedContactIds?.size ?: 0,
                prefill = null,
            )
        }
    }

    // Fans out removal requests to the *old* distribution's holders and flips it to DISCARDING.
    // Called at most once per flow — discardSecret is not idempotent against repeat calls (each
    // re-opens a fresh removal request per holder), so this phase transition must never be
    // re-entered after firing.
    fun discardOldAndFinish() {
        viewModelScope.launch {
            _uiState.update { it.copy(isActing = true) }
            withContext(Dispatchers.IO) { runCatching { shareManagement.discardSecret(secretId) } }
            _uiState.update { it.copy(isActing = false, phase = RepairPhase.DONE) }
        }
    }

    fun skipDiscard() {
        _uiState.update { it.copy(phase = RepairPhase.DONE) }
    }
}
