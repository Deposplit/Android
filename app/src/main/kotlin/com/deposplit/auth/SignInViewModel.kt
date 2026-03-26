package com.deposplit.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SignInViewModel(
    private val authPort: AuthPort,
    oidcCallbacks: SharedFlow<String>,
) : ViewModel() {

    data class UiState(
        val input: String = "matrix.org",
        val isLoading: Boolean = false,
        val error: String? = null,
    )

    sealed interface Effect {
        data class OpenBrowser(val url: String) : Effect
        data object NavigateToHome : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            oidcCallbacks.collect { callbackUrl ->
                completeOidcLogin(callbackUrl)
            }
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value, error = null) }
    }

    fun onContinue() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { authPort.discoverLoginFlow(normalizeInput(_uiState.value.input)) }
                .onSuccess { flow ->
                    _uiState.update { it.copy(isLoading = false) }
                    when (flow) {
                        is LoginFlow.Oidc -> _effects.send(Effect.OpenBrowser(flow.authorizationUrl))
                        LoginFlow.Password -> _uiState.update {
                            it.copy(error = "This homeserver uses password login, which is not yet supported.")
                        }
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Could not reach homeserver") }
                }
        }
    }

    private fun completeOidcLogin(callbackUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { authPort.completeOidcLogin(callbackUrl) }
                .onSuccess {
                    _uiState.update { it.copy(isLoading = false) }
                    _effects.send(Effect.NavigateToHome)
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isLoading = false, error = e.message ?: "Login failed") }
                }
        }
    }

    private fun normalizeInput(input: String): String {
        val trimmed = input.trim()
        return when {
            trimmed.startsWith("@") -> {
                // Matrix ID like @alice:matrix.org → https://matrix.org
                val domain = trimmed.substringAfter(":", missingDelimiterValue = "")
                    .ifEmpty { error("Invalid Matrix ID — expected @user:homeserver") }
                "https://$domain"
            }
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            else -> "https://$trimmed"
        }
    }
}
