package com.deposplit.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.api.RelayDefaults
import com.deposplit.driven_ports.RelaySettings
import com.deposplit.driving_ports.CatalogManagement
import com.deposplit.settings.CatalogCodec
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
) : ViewModel() {

    data class UiState(
        val relayBaseUrl: String = "",
        @StringRes val catalogMessage: Int? = null,
        val catalogMessageArg: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState(relayBaseUrl = relaySettings.getDefaultRelayBaseUrl()))
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
