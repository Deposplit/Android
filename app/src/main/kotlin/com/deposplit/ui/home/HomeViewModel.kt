package com.deposplit.ui.home

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.api.HeldShare
import com.deposplit.api.Role
import com.deposplit.api.ShareMetadata
import com.deposplit.api.ShareRepository
import com.deposplit.api.ShareTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(
    private val transport: ShareTransport,
    private val shareRepository: ShareRepository,
) : ViewModel() {

    data class UiState(
        val distributedShares: List<ShareMetadata> = emptyList(),
        val heldShares: List<HeldShare> = emptyList(),
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
            runCatching {
                withContext(Dispatchers.IO) {
                    val distributed = transport.listShares(Role.SENDER)
                    // Poll relay inbox for new shares not yet picked up
                    val inbox = transport.listShares(Role.RECIPIENT)
                    for (meta in inbox) {
                        if (shareRepository.getCiphertext(meta.id) == null) {
                            runCatching {
                                val ciphertext = transport.pickUpShare(meta.id)
                                shareRepository.save(
                                    HeldShare(
                                        id = meta.id,
                                        secretId = meta.secretId,
                                        label = meta.label,
                                        senderKey = meta.senderKey,
                                        createdAt = meta.createdAt,
                                        ciphertext = ciphertext,
                                    )
                                )
                            }
                            // Silently skip: relay may have already cleared it on a previous run
                        }
                    }
                    distributed to shareRepository.getAll()
                }
            }
                .onSuccess { (distributed, held) ->
                    _uiState.update {
                        it.copy(isLoading = false, distributedShares = distributed, heldShares = held)
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, error = R.string.home_error_fallback) }
                }
        }
    }
}
