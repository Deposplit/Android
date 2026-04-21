package com.deposplit.ui.deposit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.api.ShareTransport
import com.deposplit.auth.AuthPort
import com.deposplit.contacts.Contact
import com.deposplit.contacts.ContactRepository
import com.deposplit.shamir.split
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class DepositViewModel(
    private val auth: AuthPort,
    private val transport: ShareTransport,
    private val contactRepository: ContactRepository,
) : ViewModel() {

    data class UiState(
        val label: String = "",
        val secret: String = "",
        val threshold: Int = 2,
        val contacts: List<Contact> = emptyList(),
        val selectedContactIds: Set<UUID> = emptySet(),
        val isLoadingContacts: Boolean = false,
        val isDepositing: Boolean = false,
        val error: String? = null,
        val labelError: String? = null,
        val secretError: String? = null,
        val selectionError: String? = null,
    ) {
        val selectedCount: Int get() = selectedContactIds.size
    }

    sealed interface Effect {
        data object NavigateBack : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        loadContacts()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContacts = true) }
            runCatching { withContext(Dispatchers.IO) { contactRepository.getAll() } }
                .onSuccess { contacts -> _uiState.update { it.copy(isLoadingContacts = false, contacts = contacts) } }
                .onFailure { e -> _uiState.update { it.copy(isLoadingContacts = false, error = e.message ?: "Failed to load contacts") } }
        }
    }

    fun onLabelChange(value: String) = _uiState.update { it.copy(label = value, labelError = null) }
    fun onSecretChange(value: String) = _uiState.update { it.copy(secret = value, secretError = null) }

    fun onToggleContact(contactId: UUID) {
        _uiState.update { state ->
            val newIds = if (contactId in state.selectedContactIds)
                state.selectedContactIds - contactId
            else
                state.selectedContactIds + contactId
            val newN = newIds.size
            val newThreshold = state.threshold.coerceIn(2, newN.coerceAtLeast(2))
            state.copy(selectedContactIds = newIds, threshold = newThreshold, selectionError = null)
        }
    }

    fun onThresholdIncrement() = _uiState.update { state ->
        state.copy(threshold = (state.threshold + 1).coerceAtMost(state.selectedCount.coerceAtLeast(2)))
    }

    fun onThresholdDecrement() = _uiState.update { state ->
        state.copy(threshold = (state.threshold - 1).coerceAtLeast(2))
    }

    fun deposit() {
        val state = _uiState.value
        val labelError = if (state.label.isBlank()) "Required" else null
        val secretError = if (state.secret.isBlank()) "Required" else null
        val selectionError = if (state.selectedCount < 2) "Select at least 2 recipients" else null

        if (labelError != null || secretError != null || selectionError != null) {
            _uiState.update { it.copy(labelError = labelError, secretError = secretError, selectionError = selectionError) }
            return
        }

        _uiState.update { it.copy(isDepositing = true, error = null) }

        viewModelScope.launch {
            runCatching {
                val selectedContacts = state.contacts.filter { it.id in state.selectedContactIds }
                val secretBytes = state.secret.toByteArray(Charsets.UTF_8)
                val shares = split(secretBytes, selectedContacts.size, state.threshold)
                val secretId = UUID.randomUUID()
                val label = state.label.trim()
                withContext(Dispatchers.IO) {
                    shares.zip(selectedContacts).forEach { (share, contact) ->
                        val ciphertext = auth.encrypt(share, contact.xPublicKey)
                        transport.depositShare(secretId, label, contact.edPublicKey, ciphertext)
                    }
                }
            }
                .onSuccess { _effects.send(Effect.NavigateBack) }
                .onFailure { e -> _uiState.update { it.copy(isDepositing = false, error = e.message ?: "Deposit failed") } }
        }
    }
}
