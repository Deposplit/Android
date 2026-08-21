package com.deposplit.ui.contacts

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.ui.qr.decodeQrPayload
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holder-side "this contact's key changed" flow (deposplit.com/CLAUDE.md "What is next" item 8).
 * Scans the contact's re-presented QR code, updates the existing contact record **in place**
 * (preserving `contactId` — see [ContactManagement.updateContact]), then pushes a metadata-only
 * recovery report for every share held from them, so a recovering owner on a fresh device can
 * rebuild her records. Distinct from [com.deposplit.ui.qr.QrScanViewModel] (which always creates a
 * *new* contact) — mixing the two up would mint a fresh `contactId` and orphan the held shares.
 */
class RelinkContactViewModel(
    private val contactId: UUID,
    private val contactManagement: ContactManagement,
    private val shareManagement: ShareManagement,
) : ViewModel() {

    data class UiState(
        val contact: Contact? = null,
        val pendingLevel: VerificationLevel? = null,
        @StringRes val error: Int? = null,
    )

    sealed interface Effect {
        data object NavigateBack : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val hasScanned = AtomicBoolean(false)
    private var pendingVerifyKey: ByteArray? = null
    private var pendingEncKey: ByteArray? = null
    private var pendingCipherSuite: CipherSuite? = null

    fun load() {
        viewModelScope.launch {
            val contact = withContext(Dispatchers.IO) { contactManagement.listContacts() }.find { it.id == contactId }
            _uiState.update { it.copy(contact = contact) }
        }
    }

    fun onQrDecoded(raw: String) {
        if (!hasScanned.compareAndSet(false, true)) return
        runCatching {
            val payload = decodeQrPayload(raw)
            val decoder = Base64.getUrlDecoder()
            pendingVerifyKey = decoder.decode(payload.verifyKey)
            pendingEncKey = decoder.decode(payload.encKey)
            pendingCipherSuite = CipherSuite.fromWire(payload.cipherSuite)
                ?: error("Unknown cipher suite in QR payload: ${payload.cipherSuite}")
        }.onSuccess {
            // In-person re-scan is the strongest assurance this flow can claim (item 6) —
            // defaulted, but always shown for confirmation since a key change forces a fresh
            // choice (item 8).
            _uiState.update { it.copy(pendingLevel = VerificationLevel.VERY_HIGH, error = null) }
        }.onFailure {
            hasScanned.set(false)
            _uiState.update { it.copy(error = R.string.qr_scan_error_fallback) }
        }
    }

    fun onLevelChange(level: VerificationLevel) {
        _uiState.update { it.copy(pendingLevel = level) }
    }

    fun confirm() {
        val verifyKey = pendingVerifyKey ?: return
        val encKey = pendingEncKey ?: return
        val cipherSuite = pendingCipherSuite ?: return
        val level = _uiState.value.pendingLevel ?: return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    contactManagement.updateContact(contactId, verifyKey = verifyKey, encKey = encKey, cipherSuite = cipherSuite, verificationLevel = level)
                    shareManagement.pushRecoveryMetadata(contactId)
                }
            }.onSuccess {
                _effects.send(Effect.NavigateBack)
            }.onFailure {
                hasScanned.set(false)
                _uiState.update { it.copy(pendingLevel = null, error = R.string.relink_error_fallback) }
            }
        }
    }
}
