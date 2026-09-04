package com.deposplit.ui.signin

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

/**
 * Re-registration after a phone switch. Deliberately the same call as first registration —
 * [Identity.register] mints a fresh identity and leaves contacts, secrets, share metadata and the
 * shares held for other people exactly where they are, so there is nothing to weigh up before
 * pressing the button.
 *
 * The pseudonym is pre-filled from storage because it survived the switch along with everything
 * else; only the keys did not.
 */
class KeysLostViewModel(private val auth: Identity) : ViewModel() {

    data class UiState(
        val pseudonym: String = "",
        val isLoading: Boolean = false,
        @StringRes val error: Int? = null,
    )

    sealed interface Effect {
        data object NavigateToHome : Effect
    }

    private val _uiState = MutableStateFlow(
        UiState(pseudonym = runCatching { auth.pseudonym() }.getOrDefault(""))
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onPseudonymChange(value: String) {
        _uiState.update { it.copy(pseudonym = value, error = null) }
    }

    fun onCreateNewKeys() {
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
