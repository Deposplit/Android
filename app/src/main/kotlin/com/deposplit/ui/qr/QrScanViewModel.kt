package com.deposplit.ui.qr

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driven_ports.ContactRepository
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.VerificationLevel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class QrScanViewModel(private val contactRepository: ContactRepository) : ViewModel() {

    data class UiState(@StringRes val error: Int? = null)

    sealed interface Effect {
        data object NavigateBack : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    val hasScanned = AtomicBoolean(false)

    fun onQrDecoded(raw: String) {
        if (!hasScanned.compareAndSet(false, true)) return
        viewModelScope.launch {
            runCatching {
                val payload = decodeQrPayload(raw)
                require(payload.v == 1) { "Unknown QR payload version: ${payload.v}" }
                val decoder = Base64.getUrlDecoder()
                val edKey = decoder.decode(payload.ed)
                val xKey = decoder.decode(payload.x)
                val now = Instant.now().toString()
                contactRepository.save(
                    Contact(
                        id = UUID.randomUUID(),
                        pseudonym = payload.pseudonym,
                        edPublicKey = edKey,
                        xPublicKey = xKey,
                        verificationLevel = VerificationLevel.VERIFIED,
                        verifiedAt = now,
                        addedAt = now,
                    )
                )
            }.onSuccess {
                _effects.send(Effect.NavigateBack)
            }.onFailure {
                hasScanned.set(false)
                _uiState.update { it.copy(error = R.string.qr_scan_error_fallback) }
            }
        }
    }

    fun retry() {
        _uiState.update { it.copy(error = null) }
        hasScanned.set(false)
    }
}
