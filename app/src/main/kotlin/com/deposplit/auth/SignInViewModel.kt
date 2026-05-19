package com.deposplit.auth

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignInViewModel(private val auth: Identity) : ViewModel() {

    data class UiState(
        val pseudonym: String = "",
        val isLoading: Boolean = false,
        @StringRes val error: Int? = null,
    )

    sealed interface Effect {
        data object NavigateToHome : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onPseudonymChange(value: String) {
        _uiState.update { it.copy(pseudonym = value, error = null) }
    }

    fun onRegister() {
        val pseudonym = _uiState.value.pseudonym.trim()
        if (pseudonym.isEmpty()) {
            _uiState.update { it.copy(error = R.string.signin_error_empty_name) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { auth.register(pseudonym) } }
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.send(Effect.NavigateToHome)
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, error = R.string.signin_error_fallback) }
                }
        }
    }
}
