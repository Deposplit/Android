package com.deposplit.ui.deposit

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driven_ports.PurchaseRepository
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.FreeTierLimitReachedException
import com.deposplit.value_objects.MimeType
import com.deposplit.value_objects.SecretLimits
import com.deposplit.value_objects.SecretState
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
    private val purchases: PurchaseRepository,
    prefill: Prefill? = null,
) : ViewModel() {

    // Seeds the form's initial state — used by the Repair flow to pre-fill a reconstructed
    // secret's label/value/holders/threshold into an otherwise-ordinary deposit. All fields stay
    // editable afterward; this only affects the starting values.
    //
    // The secret arrives as *bytes*, not a String. Round-tripping it through text is what used to
    // corrupt a non-text secret on re-split: it was decoded lossily and then re-encoded, so the
    // repair wrote back something other than what it reconstructed.
    data class Prefill(
        val label: String,
        val secret: ByteArray,
        val mimeType: MimeType,
        val selectedContactIds: Set<UUID>,
        val threshold: Int,
        // The secret this re-split supersedes. A repair is not a fourth secret, so it is exempt
        // from the free-tier cap — see ShareManagement.deposit.
        val replacing: UUID? = null,
    ) {
        override fun equals(other: Any?) = other is Prefill &&
            label == other.label && secret.contentEquals(other.secret) && mimeType == other.mimeType &&
            selectedContactIds == other.selectedContactIds && threshold == other.threshold &&
            replacing == other.replacing

        override fun hashCode() = listOf(label, secret.contentHashCode(), mimeType, selectedContactIds, threshold, replacing).hashCode()
    }

    data class UiState(
        val label: String = "",
        val secret: String = "",
        // Set by a picked image, or by a prefill whose payload is not editable text. When present it
        // is what gets split, verbatim, and the text field is replaced by a summary — re-encoding it
        // as a String is exactly the corruption Prefill's comment describes.
        val opaqueSecret: ByteArray? = null,
        val mimeType: MimeType = MimeType.DEFAULT,
        // True when opaqueSecret arrived from a repair rather than the picker. The two look the same
        // to deposit, but not to the user: one is something to explain, the other something to undo.
        val isCarriedThrough: Boolean = false,
        val pickError: PickError? = null,
        // Set when a free device already holds its allowance of active secrets and this deposit is
        // not a repair. The domain refuses such a deposit regardless — this is the courteous half,
        // so the form says so before anything is typed rather than after everything is.
        val freeTierFull: Boolean = false,
        val freeTierLimit: Int = SecretLimits.FREE_TIER_MAX_ACTIVE_SECRETS,
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

        // The bytes this form will split — the edited text, or the prefilled payload untouched.
        val secretBytes: ByteArray get() = opaqueSecret ?: secret.toByteArray(Charsets.UTF_8)

        // Data classes with an array member need these by hand; equals/hashCode are otherwise
        // identity-based on `opaqueSecret` and two equal states would compare unequal.
        override fun equals(other: Any?) = other is UiState &&
            label == other.label && secret == other.secret &&
            (opaqueSecret?.contentEquals(other.opaqueSecret ?: ByteArray(0)) ?: (other.opaqueSecret == null)) &&
            mimeType == other.mimeType && threshold == other.threshold && contacts == other.contacts &&
            selectedContactIds == other.selectedContactIds && isLoadingContacts == other.isLoadingContacts &&
            isDepositing == other.isDepositing && error == other.error && labelError == other.labelError &&
            secretError == other.secretError && selectionError == other.selectionError &&
            pendingWarnings == other.pendingWarnings &&
            isCarriedThrough == other.isCarriedThrough && pickError == other.pickError &&
            freeTierFull == other.freeTierFull && freeTierLimit == other.freeTierLimit

        override fun hashCode() = listOf(
            label, secret, opaqueSecret?.contentHashCode(), mimeType, threshold, contacts, selectedContactIds,
            isLoadingContacts, isDepositing, error, labelError, secretError, selectionError, pendingWarnings,
            isCarriedThrough, pickError, freeTierFull, freeTierLimit,
        ).hashCode()
    }

    // Why a picked file was refused. Carries the numbers rather than a string id alone, because the
    // message names the actual size — which is the whole point of refusing rather than shrinking.
    sealed interface PickError {
        data object UnsupportedType : PickError
        data class TooLarge(val bytes: Long, val limit: Long) : PickError
    }

    sealed interface Effect {
        data object NavigateBack : Effect
    }

    // Non-blocking "Are you sure?" warnings across the three soft axes of choosing k and n —
    // operational burden, confidentiality tail, and availability tail. Thresholds and wording are
    // UI tuning, not load-bearing spec.
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
            // Editable only when it really is text — a declared text type whose bytes are not valid
            // UTF-8 is carried through opaquely rather than mangled into the field.
            val text = if (prefill.mimeType.isText) prefill.secret.decodeToStringOrNull() else null
            UiState(
                label = prefill.label,
                secret = text ?: "",
                opaqueSecret = if (text == null) prefill.secret else null,
                mimeType = prefill.mimeType,
                isCarriedThrough = text == null,
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

    private val replacing: UUID? = prefill?.replacing

    init {
        loadContacts()
        checkFreeTier()
    }

    /**
     * Whether this device may deposit at all. A repair never counts — it supersedes an active
     * secret rather than adding one — so it is exempt here just as it is in the domain.
     */
    private fun checkFreeTier() {
        if (replacing != null || purchases.isPremium()) return
        viewModelScope.launch {
            val active = runCatching {
                withContext(Dispatchers.IO) { shareManagement.listSecrets().count { it.state == SecretState.ACTIVE } }
            }.getOrNull() ?: return@launch
            if (active >= SecretLimits.FREE_TIER_MAX_ACTIVE_SECRETS) {
                _uiState.update { it.copy(freeTierFull = true) }
            }
        }
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

    /**
     * Takes a picked file as the secret, or refuses it and says why.
     *
     * Both checks happen here rather than at deposit time so the answer arrives while the user is
     * still looking at the picker's result. The domain re-checks the size regardless — this is the
     * courteous half, not the enforcing one.
     *
     * The declared type comes from the bytes, never from the picker's claim or the file's name, so
     * what gets signed cannot disagree with what got split.
     */
    fun onFilePicked(bytes: ByteArray) {
        val sniffed = MimeType.sniffed(bytes)
        _uiState.update {
            when {
                sniffed == null -> it.copy(pickError = PickError.UnsupportedType)
                bytes.size > SecretLimits.MAX_SECRET_BYTES ->
                    it.copy(pickError = PickError.TooLarge(bytes.size.toLong(), SecretLimits.MAX_SECRET_BYTES.toLong()))
                else -> it.copy(
                    opaqueSecret = bytes,
                    mimeType = sniffed,
                    isCarriedThrough = false,
                    pickError = null,
                    secretError = null,
                )
            }
        }
    }

    /**
     * Refuses a pick whose size the picker reported up front, so an oversized file never has to be
     * read into memory just to be turned away. [Long] because a provider reports a file length,
     * which may be far larger than the limit.
     */
    fun onPickedFileTooLarge(bytes: Long) = _uiState.update {
        it.copy(pickError = PickError.TooLarge(bytes, SecretLimits.MAX_SECRET_BYTES.toLong()))
    }

    /**
     * Drops a picked image and returns the form to text entry. Whatever was typed before the pick is
     * still in [UiState.secret], so it comes back rather than being lost.
     */
    fun onPickedFileCleared() = _uiState.update {
        it.copy(opaqueSecret = null, mimeType = MimeType.DEFAULT, isCarriedThrough = false, pickError = null)
    }

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
        val secretError = if (state.secretBytes.isEmpty()) R.string.deposit_error_required else null
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
                val label = state.label.trim()
                withContext(Dispatchers.IO) {
                    shareManagement.deposit(state.secretBytes, label, selectedContacts, state.threshold, state.mimeType, replacing)
                }
            }
                .onSuccess { _effects.send(Effect.NavigateBack) }
                .onFailure { failure ->
                    // A refusal by the cap is not "Deposit failed" — it is an answer, and the form
                    // has somewhere to send the user.
                    val hitTheCap = failure is FreeTierLimitReachedException
                    _uiState.update {
                        it.copy(
                            isDepositing = false,
                            freeTierFull = it.freeTierFull || hitTheCap,
                            error = if (hitTheCap) null else R.string.deposit_error_fallback,
                        )
                    }
                }
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

/** Strict UTF-8 decode: null rather than U+FFFD, so a caller can tell "not text" from "text". */
private fun ByteArray.decodeToStringOrNull(): String? =
    runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(this)).toString() }.getOrNull()
