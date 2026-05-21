package com.deposplit.ui.requests

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.ShareRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class RequestsViewModel(
    private val shareManagement: ShareManagement,
    private val contactManagement: ContactManagement,
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
                    shareManagement.listPendingRequests() to contactManagement.listContacts()
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
}
