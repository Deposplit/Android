package com.deposplit.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driven_ports.RelaySettings
import com.deposplit.driving_ports.Identity
import com.deposplit.value_objects.IdentityIntegrity
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class QrDisplayViewModel(private val auth: Identity, private val relaySettings: RelaySettings) : ViewModel() {

    data class UiState(
        val bitmap: Bitmap? = null,
        @StringRes val error: Int? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        generate()
    }

    private fun generate() {
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                // The QR is the one path by which a broken identity reaches another person's
                // phone, and the public keys survive a restore that the private ones do not — so
                // a code encoded here would scan cleanly and name an identity nobody can use.
                // The launch gate should have caught that already; this is the second lock.
                require(auth.integrity() == IdentityIntegrity.INTACT) { "identity is not usable" }
                val payload = encodeQrPayload(auth.pseudonym(), auth.verifyKey(), auth.encKey(), relaySettings.getDefaultRelayBaseUrl())
                val bitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 512, 512)
                val w = bitMatrix.width
                val h = bitMatrix.height
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
                bitmap
            }.onSuccess { bitmap ->
                _uiState.value = UiState(bitmap = bitmap)
            }.onFailure {
                _uiState.value = UiState(error = R.string.qr_display_error_fallback)
            }
        }
    }
}
