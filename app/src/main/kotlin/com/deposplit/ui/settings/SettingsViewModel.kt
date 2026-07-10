package com.deposplit.ui.settings

import androidx.lifecycle.ViewModel
import com.deposplit.api.RelayDefaults
import com.deposplit.driven_ports.RelaySettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsViewModel(private val relaySettings: RelaySettings) : ViewModel() {

    data class UiState(val relayBaseUrl: String = "")

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
}
