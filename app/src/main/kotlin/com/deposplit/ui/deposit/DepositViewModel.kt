package com.deposplit.ui.deposit

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.value_objects.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class DepositViewModel(
    private val shareManagement: ShareManagement,
    private val contactManagement: ContactManagement,
    prefill: Prefill? = null,
) : ViewModel() {

    // Seeds the form's initial state — used by the Repair flow to pre-fill a reconstructed
    // secret's label/value/holders/threshold into an otherwise-ordinary deposit. All fields stay
    // editable afterward; this only affects the starting values.
    data class Prefill(
        val label: String,
        val secretText: String,
        val selectedContactIds: Set<UUID>,
        val threshold: Int,
    )

    data class UiState(
        val label: String = "",
        val secret: String = "",
        val threshold: Int = 2,
        val contacts: List<Contact> = emptyList(),
        val selectedContactIds: Set<UUID> = emptySet(),
        val isLoadingContacts: Boolean = false,
        val isDepositing: Boolean = false,
        @StringRes val error: Int? = null,
        @StringRes val labelError: Int? = null,
        @StringRes val secretError: Int? = null,
        @StringRes val selectionError: Int? = null,
        val pendingWarnings: List<SplitTimeWarning> = emptyList(),
    ) {
        val selectedCount: Int get() = selectedContactIds.size
    }

    sealed interface Effect {
        data object NavigateBack : Effect
    }

    // Non-blocking "Are you sure?" warnings across item 11's three soft axes — operational
    // burden, confidentiality tail, and availability tail. Thresholds/wording are UI tuning, not
    // load-bearing spec — see deposplit.com/CLAUDE.md "What is next" item 11.
    sealed interface SplitTimeWarning {
        data class OperationalLarge(val n: Int) : SplitTimeWarning
        data class OperationalMedium(val n: Int) : SplitTimeWarning
        data class ConfidentialityLow(val k: Int, val n: Int) : SplitTimeWarning
        data class ConfidentialityMedium(val k: Int, val n: Int) : SplitTimeWarning
        data object AvailabilityNone : SplitTimeWarning
        data object AvailabilityOne : SplitTimeWarning
    }

    private val _uiState = MutableStateFlow(
        if (prefill != null) {
            UiState(
                label = prefill.label,
                secret = prefill.secretText,
                threshold = prefill.threshold,
                selectedContactIds = prefill.selectedContactIds,
            )
        } else {
            UiState()
        }
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        loadContacts()
    }

    private fun loadContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingContacts = true) }
            runCatching { withContext(Dispatchers.IO) { contactManagement.listContacts() } }
                .onSuccess { contacts -> _uiState.update { it.copy(isLoadingContacts = false, contacts = contacts) } }
                .onFailure { _uiState.update { it.copy(isLoadingContacts = false, error = R.string.deposit_error_load_contacts) } }
        }
    }

    fun onLabelChange(value: String) = _uiState.update { it.copy(label = value, labelError = null) }
    fun onSecretChange(value: String) = _uiState.update { it.copy(secret = value, secretError = null) }

    fun onToggleContact(contactId: UUID) {
        _uiState.update { state ->
            val newIds = if (contactId in state.selectedContactIds)
                state.selectedContactIds - contactId
            else
                state.selectedContactIds + contactId
            val newN = newIds.size
            val newThreshold = state.threshold.coerceIn(2, newN.coerceAtLeast(2))
            state.copy(selectedContactIds = newIds, threshold = newThreshold, selectionError = null)
        }
    }

    fun onThresholdIncrement() = _uiState.update { state ->
        state.copy(threshold = (state.threshold + 1).coerceAtMost(state.selectedCount.coerceAtLeast(2)))
    }

    fun onThresholdDecrement() = _uiState.update { state ->
        state.copy(threshold = (state.threshold - 1).coerceAtLeast(2))
    }

    fun onDepositClick() {
        val state = _uiState.value
        val labelError = if (state.label.isBlank()) R.string.deposit_error_required else null
        val secretError = if (state.secret.isBlank()) R.string.deposit_error_required else null
        val selectionError = if (state.selectedCount < 2) R.string.deposit_error_select_at_least_2 else null

        if (labelError != null || secretError != null || selectionError != null) {
            _uiState.update { it.copy(labelError = labelError, secretError = secretError, selectionError = selectionError) }
            return
        }

        val warnings = splitTimeWarnings(state.threshold, state.selectedCount)
        if (warnings.isNotEmpty()) {
            _uiState.update { it.copy(pendingWarnings = warnings) }
        } else {
            deposit()
        }
    }

    fun confirmDespiteWarnings() {
        _uiState.update { it.copy(pendingWarnings = emptyList()) }
        deposit()
    }

    fun dismissWarnings() {
        _uiState.update { it.copy(pendingWarnings = emptyList()) }
    }

    private fun deposit() {
        val state = _uiState.value
        _uiState.update { it.copy(isDepositing = true, error = null) }

        viewModelScope.launch {
            runCatching {
                val selectedContacts = state.contacts.filter { it.id in state.selectedContactIds }
                val secretBytes = state.secret.toByteArray(Charsets.UTF_8)
                val label = state.label.trim()
                withContext(Dispatchers.IO) {
                    shareManagement.deposit(secretBytes, label, selectedContacts, state.threshold)
                }
            }
                .onSuccess { _effects.send(Effect.NavigateBack) }
                .onFailure { _uiState.update { it.copy(isDepositing = false, error = R.string.deposit_error_fallback) } }
        }
    }

    private fun splitTimeWarnings(k: Int, n: Int): List<SplitTimeWarning> {
        if (n <= 0) return emptyList()
        val warnings = mutableListOf<SplitTimeWarning>()
        when {
            n >= 20 -> warnings.add(SplitTimeWarning.OperationalLarge(n))
            n >= 10 -> warnings.add(SplitTimeWarning.OperationalMedium(n))
        }
        when {
            k < n / 3 -> warnings.add(SplitTimeWarning.ConfidentialityLow(k, n))
            k < n / 2 -> warnings.add(SplitTimeWarning.ConfidentialityMedium(k, n))
        }
        when {
            k == n -> warnings.add(SplitTimeWarning.AvailabilityNone)
            k == n - 1 -> warnings.add(SplitTimeWarning.AvailabilityOne)
        }
        return warnings
    }
}
