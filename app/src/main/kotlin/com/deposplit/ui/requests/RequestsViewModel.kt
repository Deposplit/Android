package com.deposplit.ui.requests

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.ShareRepository
import com.deposplit.driving_ports.ShareTransport
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.Role
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareRequestType
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
    private val shareRepository: ShareRepository,
) : ViewModel() {

    data class UiState(
        val requests: List<ShareRequest> = emptyList(),
        val contacts: List<Contact> = emptyList(),
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
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, error = R.string.requests_error_load) }
                }
        }
    }

    fun respond(requestId: UUID, approved: Boolean) {
        val request = _uiState.value.requests.find { it.id == requestId } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(respondingIds = it.respondingIds + requestId) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val ciphertext = if (approved && request.requestType == ShareRequestType.RETRIEVE) {
                        shareRepository.getCiphertext(request.share.id)
                            ?: error("Share ciphertext not found in local storage")
                    } else null
                    transport.respondToShareRequest(requestId, approved, ciphertext)
                    if (approved && request.requestType == ShareRequestType.DELETE) {
                        shareRepository.delete(request.share.id)
                    }
                }
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
}
