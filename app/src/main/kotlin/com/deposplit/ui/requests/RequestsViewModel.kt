package com.deposplit.ui.requests

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.KeyConflict
import com.deposplit.value_objects.RelayFanOut
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

class RequestsViewModel(
    private val shareManagement: ShareManagement,
    private val contactManagement: ContactManagement,
) : ViewModel() {

    data class UiState(
        val requests: List<ShareRequest> = emptyList(),
        val contacts: List<Contact> = emptyList(),
        val keyConflicts: List<KeyConflict> = emptyList(),
        val heldSecretIds: Set<UUID> = emptySet(),
        val isLoading: Boolean = false,
        // Every relay that did not answer, by base URL. The list above still holds what the
        // others returned, and each of these is named rather than turned into an error: error is
        // kept for a failure of this device's own, which no relay line could explain.
        val unreachableRelays: List<String> = emptyList(),
        // False when no relay answered, so an empty list says nothing about whether any request is
        // waiting, and the tab must not claim there is none.
        val anyRelayAnswered: Boolean = true,
        @StringRes val error: Int? = null,
        val respondingIds: Set<UUID> = emptySet(),
    )

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
                    Loaded(
                        shareManagement.listPendingRequests(),
                        contactManagement.listContacts(),
                        runCatching { shareManagement.listKeyConflicts() }.getOrDefault(emptyList()),
                        runCatching { shareManagement.listHeld().map { it.secretId }.toSet() }.getOrDefault(emptySet()),
                    )
                }
            }
                .onSuccess { loaded ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            requests = loaded.requests.items,
                            unreachableRelays = loaded.requests.unreachableRelays.sorted(),
                            anyRelayAnswered = loaded.requests.anyAnswered,
                            contacts = loaded.contacts,
                            keyConflicts = loaded.keyConflicts,
                            heldSecretIds = loaded.heldSecretIds,
                            respondingIds = emptySet(),
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, error = R.string.requests_error_load) }
                }
        }
    }

    fun respond(requestId: UUID, approved: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(respondingIds = it.respondingIds + requestId) }
            runCatching {
                withContext(Dispatchers.IO) { shareManagement.respond(requestId, approved) }
            }
                .onSuccess { load() }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            respondingIds = it.respondingIds - requestId,
                            error = R.string.requests_error_respond,
                        )
                    }
                }
        }
    }

    // Approving a retrieval re-encrypts the share to the requester, so it needs the share in hand.
    // The ask can still arrive after this device deleted it: the owner learns of a withdrawal only
    // on their next poll, and until then the card has to say why Approve cannot work.
    fun canApprove(request: ShareRequest): Boolean =
        request.transactionType != ShareTransactionType.RETRIEVAL || request.secretId in uiState.value.heldSecretIds

    // Retrieve-approval hardening: the attack signature is key change → quick retrieval,
    // so this is surfaced only for Retrieval requests, not every request type.
    fun keyChangedDaysAgo(request: ShareRequest): Long? {
        if (request.transactionType != ShareTransactionType.RETRIEVAL) return null
        val changedAt = uiState.value.contacts
            .find { it.verifyKey.contentEquals(request.senderKey) }
            ?.keyChangedAt ?: return null
        return Duration.between(changedAt, Instant.now()).toDays()
    }

    fun contactName(conflict: KeyConflict): String? =
        uiState.value.contacts.find { it.id == conflict.contactId }?.displayName

    // Resolving "yes, this really was them" goes through the existing Relink flow (a
    // fresh human-verified re-scan), not through this dismiss action — dismissing only
    // acknowledges the alert (a false alarm, or already handled out-of-band).
    fun dismissConflict(id: UUID) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { shareManagement.dismissKeyConflict(id) } }
                .onSuccess { load() }
        }
    }

    private data class Loaded(
        val requests: RelayFanOut<ShareRequest>,
        val contacts: List<Contact>,
        val keyConflicts: List<KeyConflict>,
        val heldSecretIds: Set<UUID>,
    )
}
