package com.deposplit.ui.secretdetail

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.shamir.ReconstructionIntegrityException
import com.deposplit.ui.home.SecretGroup
import com.deposplit.ui.home.buildSecretGroups
import com.deposplit.ui.reconstruction.ReconstructedSecret
import com.deposplit.value_objects.ReconstructionIntegrity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** One secret's own screen: its holders, and the three actions that operate on the whole secret
 * rather than on any one holder — asking the holders for copies, putting it back together, and
 * letting the collected copies go again.
 *
 * All three stay on screen in every state; what changes is whether they are enabled and what the
 * line beneath them says. The rules themselves live on [SecretGroup], so this tab's list and this
 * screen can never disagree about them.
 */
class SecretDetailViewModel(
    private val secretId: UUID,
    private val shareManagement: ShareManagement,
    private val contactManagement: ContactManagement,
) : ViewModel() {

    data class UiState(
        val group: SecretGroup? = null,
        val isLoading: Boolean = false,
        val isRequestingAll: Boolean = false,
        val isReconstructing: Boolean = false,
        val isClearing: Boolean = false,
        val reconstructedSecret: ReconstructedSecret? = null,
        val reconstructionIntegrity: ReconstructionIntegrity? = null,
        @StringRes val error: Int? = null,
        @StringRes val actionError: Int? = null,
    ) {
        // Whether the secret is on screen right now, which is all that is known: reconstruct is a
        // pure read and nothing records that a secret has been looked at. It decides only which of
        // the two clearing confirmations is shown, and neither of them claims anything about what
        // the reader remembers.
        val hasBeenShown: Boolean get() = reconstructedSecret != null
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    buildSecretGroups(
                        shareManagement.listSecrets(),
                        shareManagement.listDistributed(),
                        runCatching { shareManagement.listSentRequests() }.getOrDefault(emptyList()),
                        contactManagement.listContacts(),
                    ).find { it.secret.id == secretId } ?: error("Secret not found")
                }
            }
                .onSuccess { group -> _uiState.update { it.copy(isLoading = false, group = group) } }
                .onFailure { _uiState.update { it.copy(isLoading = false, error = R.string.secret_detail_error_load) } }
        }
    }

    fun requestAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRequestingAll = true, actionError = null) }
            withContext(Dispatchers.IO) { runCatching { shareManagement.requestAll(secretId) } }
            _uiState.update { it.copy(isRequestingAll = false) }
            load()
        }
    }

    fun reconstruct() {
        _uiState.update {
            it.copy(isReconstructing = true, actionError = null, reconstructedSecret = null, reconstructionIntegrity = null)
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { shareManagement.reconstruct(secretId) } }
                .onSuccess { result ->
                    _uiState.update {
                        it.copy(
                            isReconstructing = false,
                            // The declared type decides how the bytes are shown, and
                            // ReconstructedSecret falls back to a binary view whenever the type and
                            // the bytes disagree — so nothing here force-decodes, and the original
                            // bytes survive whichever branch runs.
                            reconstructedSecret = ReconstructedSecret.of(result.secret, result.mimeType),
                            reconstructionIntegrity = result.integrity,
                        )
                    }
                }
                .onFailure { e ->
                    val errorRes = if (e is ReconstructionIntegrityException) {
                        R.string.secret_detail_error_integrity
                    } else {
                        R.string.secret_detail_error_reconstruct
                    }
                    _uiState.update { it.copy(isReconstructing = false, actionError = errorRes) }
                }
        }
    }

    /** Hands the collected copies back — and any ask still waiting for an answer with them —
     * without touching the split. The holders keep their pieces, so what is on screen goes away
     * but the secret can be asked for again.
     */
    fun clearCollected() {
        viewModelScope.launch {
            _uiState.update { it.copy(isClearing = true, actionError = null) }
            withContext(Dispatchers.IO) { runCatching { shareManagement.clearCollectedShares(secretId) } }
            _uiState.update {
                it.copy(isClearing = false, reconstructedSecret = null, reconstructionIntegrity = null)
            }
            load()
        }
    }

    fun destroy() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { shareManagement.destroySecret(secretId) } }
            load()
        }
    }

    fun forceForget() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { shareManagement.forceForgetSecret(secretId) } }
        }
    }
}
