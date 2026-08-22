package com.deposplit.ui.contacts

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.value_objects.VerificationLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

class AddContactViewModel(private val contactManagement: ContactManagement) : ViewModel() {

    data class UiState(
        val pseudonym: String = "",
        val edPublicKey: String = "",
        val xPublicKey: String = "",
        val relayBaseUrl: String = "",
        // Item 15 — purely local, optional; set at add-time or later via the Contacts screen's
        // Rename action.
        val nickname: String = "",
        // .VERY_HIGH is deliberately excluded: physical co-presence can't be asserted by typing
        // a key in by hand — that's what the in-person QR scan flow is for. See CLAUDE.md item 6.
        val verificationLevel: VerificationLevel = VerificationLevel.VERY_LOW,
        @StringRes val pseudonymError: Int? = null,
        @StringRes val edKeyError: Int? = null,
        @StringRes val xKeyError: Int? = null,
        val isSaving: Boolean = false,
    ) {
        val selectableLevels: List<VerificationLevel> =
            VerificationLevel.entries.filter { it != VerificationLevel.VERY_HIGH }
    }

    sealed interface Effect {
        data object NavigateBack : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    fun onPseudonymChange(value: String) = _uiState.update { it.copy(pseudonym = value, pseudonymError = null) }
    fun onEdKeyChange(value: String) = _uiState.update { it.copy(edPublicKey = value, edKeyError = null) }
    fun onXKeyChange(value: String) = _uiState.update { it.copy(xPublicKey = value, xKeyError = null) }
    fun onRelayBaseUrlChange(value: String) = _uiState.update { it.copy(relayBaseUrl = value) }
    fun onNicknameChange(value: String) = _uiState.update { it.copy(nickname = value) }
    fun onVerificationLevelChange(value: VerificationLevel) = _uiState.update { it.copy(verificationLevel = value) }

    fun save() {
        val state = _uiState.value
        val pseudonymError = if (state.pseudonym.isBlank()) R.string.add_contact_error_required else null
        val edKeyBytes = decodeBase64Url(state.edPublicKey.trim())
        val xKeyBytes = decodeBase64Url(state.xPublicKey.trim())
        val edKeyError = when {
            state.edPublicKey.isBlank() -> R.string.add_contact_error_required
            edKeyBytes == null -> R.string.add_contact_error_invalid_base64url
            edKeyBytes.size != 32 -> R.string.add_contact_error_verify_key_length
            else -> null
        }
        val xKeyError = when {
            state.xPublicKey.isBlank() -> R.string.add_contact_error_required
            xKeyBytes == null -> R.string.add_contact_error_invalid_base64url
            xKeyBytes.size != 32 -> R.string.add_contact_error_enc_key_length
            else -> null
        }
        if (pseudonymError != null || edKeyError != null || xKeyError != null) {
            _uiState.update { it.copy(pseudonymError = pseudonymError, edKeyError = edKeyError, xKeyError = xKeyError) }
            return
        }

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contactManagement.addManually(
                        state.pseudonym.trim(),
                        edKeyBytes!!,
                        xKeyBytes!!,
                        state.verificationLevel,
                        state.relayBaseUrl.trim().ifBlank { null },
                        state.nickname.trim().ifBlank { null },
                    )
                }
            }
                .onSuccess { _effects.send(Effect.NavigateBack) }
                .onFailure {
                    _uiState.update { it.copy(isSaving = false, pseudonymError = R.string.add_contact_error_save) }
                }
        }
    }

    private fun decodeBase64Url(value: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrNull()
}
