package com.deposplit.ui.qr

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.VerificationLevel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

class QrScanViewModel(private val contactManagement: ContactManagement) : ViewModel() {

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
                val decoder = Base64.getUrlDecoder()
                val verifyKey = decoder.decode(payload.verifyKey)
                val encKey = decoder.decode(payload.encKey)
                val cipherSuite = CipherSuite.fromWire(payload.cipherSuite)
                    ?: error("Unknown cipher suite in QR payload: ${payload.cipherSuite}")
                // A QR scan defaults to in-person co-presence, the strongest assurance the current
                // scan flow can claim (CLAUDE.md item 6). A remote/video-call scan is a weaker
                // claim, but there's no UI step here to downgrade it yet.
                contactManagement.addFromQr(payload.pseudonym, verifyKey, encKey, cipherSuite, VerificationLevel.VERY_HIGH, payload.relay)
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
