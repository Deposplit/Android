package com.deposplit.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.api.Role
import com.deposplit.api.ShareRequest
import com.deposplit.api.ShareRequestState
import com.deposplit.api.ShareTransport
import com.deposplit.contacts.Contact
import com.deposplit.contacts.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class RequestsViewModel(
    private val transport: ShareTransport,
    private val contactRepository: ContactRepository,
) : ViewModel() {

    data class UiState(
        val requests: List<ShareRequest> = emptyList(),
        val contacts: List<Contact> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
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
                    transport.listShareRequests(Role.RECIPIENT, ShareRequestState.PENDING) to
                        contactRepository.getAll()
                }
            }
                .onSuccess { (requests, contacts) ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            requests = requests,
                            contacts = contacts,
                            respondingIds = emptySet(),
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
                }
        }
    }

    fun respond(requestId: UUID, approved: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(respondingIds = it.respondingIds + requestId) }
            runCatching {
                withContext(Dispatchers.IO) { transport.respondToShareRequest(requestId, approved) }
            }
                .onSuccess { load() }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            respondingIds = it.respondingIds - requestId,
                            error = e.message ?: "Failed to respond",
                        )
                    }
                }
        }
    }
}
