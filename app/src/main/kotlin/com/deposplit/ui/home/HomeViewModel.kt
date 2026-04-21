package com.deposplit.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.api.Role
import com.deposplit.api.ShareMetadata
import com.deposplit.api.ShareTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val transport: ShareTransport) : ViewModel() {

    data class UiState(
        val distributedShares: List<ShareMetadata> = emptyList(),
        val heldShares: List<ShareMetadata> = emptyList(),
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
            runCatching {
                withContext(Dispatchers.IO) {
                    transport.listShares(Role.SENDER) to transport.listShares(Role.RECIPIENT)
                }
            }
                .onSuccess { (distributed, held) ->
                    _uiState.update {
                        it.copy(isLoading = false, distributedShares = distributed, heldShares = held)
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load") }
                }
        }
    }
}
