package com.deposplit.ui.requests

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.KeyConflict
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
        val isLoading: Boolean = false,
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
                    Triple(
                        shareManagement.listPendingRequests(),
                        contactManagement.listContacts(),
                        runCatching { shareManagement.listKeyConflicts() }.getOrDefault(emptyList()),
                    )
                }
            }
                .onSuccess { (requests, contacts, keyConflicts) ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            requests = requests,
                            contacts = contacts,
                            keyConflicts = keyConflicts,
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

    // Item 10's retrieve-approval hardening: the attack signature is key change → quick retrieval,
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

    // Item 10 — resolving "yes, this really was them" goes through the existing Relink flow (a
    // fresh human-verified re-scan), not through this dismiss action — dismissing only
    // acknowledges the alert (a false alarm, or already handled out-of-band).
    fun dismissConflict(id: UUID) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { shareManagement.dismissKeyConflict(id) } }
                .onSuccess { load() }
        }
    }
}
