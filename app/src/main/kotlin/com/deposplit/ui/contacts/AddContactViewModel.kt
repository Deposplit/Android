package com.deposplit.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.contacts.Contact
import com.deposplit.contacts.ContactRepository
import com.deposplit.contacts.VerificationLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Base64
import java.util.UUID

class AddContactViewModel(private val repository: ContactRepository) : ViewModel() {

    data class UiState(
        val pseudonym: String = "",
        val edPublicKey: String = "",
        val xPublicKey: String = "",
        val pseudonymError: String? = null,
        val edKeyError: String? = null,
        val xKeyError: String? = null,
        val isSaving: Boolean = false,
    )

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

    fun save() {
        val state = _uiState.value
        val pseudonymError = if (state.pseudonym.isBlank()) "Required" else null
        val edKeyBytes = decodeBase64Url(state.edPublicKey.trim())
        val xKeyBytes = decodeBase64Url(state.xPublicKey.trim())
        val edKeyError = when {
            state.edPublicKey.isBlank() -> "Required"
            edKeyBytes == null -> "Invalid base64url"
            edKeyBytes.size != 32 -> "Must be 32 bytes (Ed25519 public key)"
            else -> null
        }
        val xKeyError = when {
            state.xPublicKey.isBlank() -> "Required"
            xKeyBytes == null -> "Invalid base64url"
            xKeyBytes.size != 32 -> "Must be 32 bytes (X25519 public key)"
            else -> null
        }
        if (pseudonymError != null || edKeyError != null || xKeyError != null) {
            _uiState.update { it.copy(pseudonymError = pseudonymError, edKeyError = edKeyError, xKeyError = xKeyError) }
            return
        }

        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            runCatching {
                val contact = Contact(
                    id = UUID.randomUUID(),
                    pseudonym = state.pseudonym.trim(),
                    edPublicKey = edKeyBytes!!,
                    xPublicKey = xKeyBytes!!,
                    verificationLevel = VerificationLevel.UNVERIFIED,
                    verifiedAt = null,
                    addedAt = Instant.now().toString(),
                )
                withContext(Dispatchers.IO) { repository.save(contact) }
            }
                .onSuccess { _effects.send(Effect.NavigateBack) }
                .onFailure { e ->
                    _uiState.update { it.copy(isSaving = false, pseudonymError = e.message ?: "Failed to save") }
                }
        }
    }

    private fun decodeBase64Url(value: String): ByteArray? = runCatching {
        Base64.getUrlDecoder().decode(value)
    }.getOrNull()
}
