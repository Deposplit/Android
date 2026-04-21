package com.deposplit.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

class ContactsViewModel(private val repository: ContactRepository) : ViewModel() {

    data class UiState(
        val contacts: List<Contact> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { repository.getAll() } }
                .onSuccess { contacts -> _uiState.update { it.copy(isLoading = false, contacts = contacts) } }
                .onFailure { e -> _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") } }
        }
    }

    fun delete(contactId: UUID) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.delete(contactId) } }
                .onSuccess { load() }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Failed to delete") } }
        }
    }
}
