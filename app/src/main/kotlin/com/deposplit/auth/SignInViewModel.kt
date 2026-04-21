package com.deposplit.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SignInViewModel(private val authPort: AuthPort) : ViewModel() {

    data class UiState(
        val pseudonym: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
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
            _uiState.update { it.copy(error = "Please enter a name to continue.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { authPort.register(pseudonym) } }
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.send(Effect.NavigateToHome)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Setup failed") }
                }
        }
    }
}
