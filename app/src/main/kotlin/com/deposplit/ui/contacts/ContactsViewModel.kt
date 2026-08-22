package com.deposplit.ui.contacts

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.value_objects.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ContactsViewModel(
    private val contactManagement: ContactManagement,
    private val shareManagement: ShareManagement,
) : ViewModel() {

    data class UiState(
        val contacts: List<Contact> = emptyList(),
        val isLoading: Boolean = false,
        @StringRes val error: Int? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { contactManagement.listContacts() } }
                .onSuccess { contacts -> _uiState.update { it.copy(isLoading = false, contacts = contacts) } }
                .onFailure { _uiState.update { it.copy(isLoading = false, error = R.string.contacts_error_load) } }
        }
    }

    fun delete(contactId: UUID) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { contactManagement.deleteContact(contactId) } }
                .onSuccess { load() }
                .onFailure { _uiState.update { it.copy(error = R.string.contacts_error_delete) } }
        }
    }

    // Item 10 — flags this contact's *current* key as compromised, out-of-band-triggered (the user
    // has some independent reason to believe it). From this point, any signed rotation notice
    // claiming continuity from that key is refused auto-accept; only a fresh human-verified relink
    // can move the contact forward.
    fun markKeyCompromised(contactId: UUID) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { contactManagement.markKeyCompromised(contactId) } }
                .onSuccess { load() }
                .onFailure { _uiState.update { it.copy(error = R.string.contacts_error_mark_compromised) } }
        }
    }

    // Item 12 — this device's own choice to stop (or resume) heartbeating this contact (who is
    // the owner of shares this device holds from them). Low-stakes and reversible, unlike marking
    // a key compromised — no confirmation needed.
    fun toggleHeartbeatEmission(contact: Contact) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { shareManagement.setHeartbeatEmissionOptedOut(contact.id, !contact.heartbeatEmissionOptedOut) } }
                .onSuccess { load() }
        }
    }

    // Item 15 — purely local disambiguation label; never touches keys/level/cipherSuite. Pass
    // null (or a blank string, normalized service-side) to clear an existing nickname.
    fun rename(contactId: UUID, nickname: String?) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { contactManagement.renameContact(contactId, nickname) } }
                .onSuccess { load() }
                .onFailure { _uiState.update { it.copy(error = R.string.contacts_error_rename) } }
        }
    }
}
