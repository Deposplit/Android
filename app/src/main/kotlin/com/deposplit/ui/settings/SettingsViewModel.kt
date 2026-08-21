package com.deposplit.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.api.RelayDefaults
import com.deposplit.driven_ports.RelaySettings
import com.deposplit.driving_ports.CatalogManagement
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.settings.CatalogCodec
import com.deposplit.value_objects.RegenerateIdentityResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val relaySettings: RelaySettings,
    private val catalogManagement: CatalogManagement,
    private val shareManagement: ShareManagement,
    private val contactManagement: ContactManagement,
) : ViewModel() {

    data class UiState(
        val relayBaseUrl: String = "",
        @StringRes val catalogMessage: Int? = null,
        val catalogMessageArg: String? = null,
        // Item 9's identity-regen trigger. contactCount is pre-fetched so the confirmation
        // dialog can tell the user how many contacts will be notified before they commit.
        val contactCount: Int = 0,
        val isRegeneratingIdentity: Boolean = false,
        val regenerateResult: RegenerateIdentityResult? = null,
        @StringRes val regenerateError: Int? = null,
    )

    private val _uiState = MutableStateFlow(
        UiState(
            relayBaseUrl = relaySettings.getDefaultRelayBaseUrl(),
            contactCount = runCatching { contactManagement.listContacts().size }.getOrDefault(0),
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun onRelayBaseUrlChange(value: String) = _uiState.update { it.copy(relayBaseUrl = value) }

    fun save() {
        relaySettings.setDefaultRelayBaseUrl(_uiState.value.relayBaseUrl.trim().ifBlank { null })
    }

    fun resetToDefault() {
        relaySettings.setDefaultRelayBaseUrl(null)
        _uiState.update { it.copy(relayBaseUrl = RelayDefaults.FALLBACK_BASE_URL) }
    }

    /** Encodes the current catalog to JSON bytes for the caller to write to a SAF-picked file. */
    fun exportCatalogBytes(): ByteArray = CatalogCodec.encode(catalogManagement.exportCatalog())

    /** Item 9's identity-regen trigger. Best-effort drains pending relay state under the *old*
     * identity, notifies every contact of the new key, then activates it — see
     * ShareService.regenerateIdentity's doc comment for why the ordering matters. Any request
     * still pending with a counterparty at this exact moment may become unreachable afterward
     * (surfaced in the confirmation dialog's copy, not repeated here).
     */
    fun regenerateIdentity() {
        _uiState.update { it.copy(isRegeneratingIdentity = true, regenerateResult = null, regenerateError = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { shareManagement.regenerateIdentity() }
            }
                .onSuccess { result ->
                    _uiState.update { it.copy(isRegeneratingIdentity = false, regenerateResult = result) }
                }
                .onFailure {
                    _uiState.update { it.copy(isRegeneratingIdentity = false, regenerateError = R.string.settings_regenerate_error) }
                }
        }
    }

    fun importCatalogBytes(bytes: ByteArray) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val catalog = CatalogCodec.decode(bytes)
                    catalogManagement.importCatalog(catalog)
                }
            }.onSuccess { added ->
                _uiState.update { it.copy(catalogMessage = R.string.settings_catalog_imported, catalogMessageArg = added.toString()) }
            }.onFailure {
                _uiState.update { it.copy(catalogMessage = R.string.settings_catalog_import_error, catalogMessageArg = null) }
            }
        }
    }
}
