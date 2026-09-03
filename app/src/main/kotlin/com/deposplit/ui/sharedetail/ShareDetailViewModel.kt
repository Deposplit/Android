package com.deposplit.ui.sharedetail

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.driving_ports.ShareManagement
import com.deposplit.shamir.ReconstructionIntegrityException
import com.deposplit.ui.reconstruction.ReconstructedSecret
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.ReconstructionIntegrity
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareTransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ShareDetailViewModel(
    private val shareId: UUID,
    private val shareManagement: ShareManagement,
    private val contactManagement: ContactManagement,
) : ViewModel() {

    data class UiState(
        val share: ShareMetadata? = null,
        val secret: Secret? = null,
        val contacts: List<Contact> = emptyList(),
        val retrievalRequest: ShareRequest? = null,
        val removalRequest: ShareRequest? = null,
        val approvedRetrievalCount: Int = 0,
        val isLoading: Boolean = false,
        val isOpeningRetrieval: Boolean = false,
        val isOpeningRemoval: Boolean = false,
        val isReconstructing: Boolean = false,
        val reconstructedSecret: ReconstructedSecret? = null,
        val reconstructionIntegrity: ReconstructionIntegrity? = null,
        @StringRes val error: Int? = null,
        @StringRes val actionError: Int? = null,
    )

    private data class LoadResult(
        val share: ShareMetadata,
        val secret: Secret,
        val allRequests: List<ShareRequest>,
        val contacts: List<Contact>,
    )

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
                    val shares = shareManagement.listDistributed()
                    val share = shares.find { it.id == shareId }
                        ?: error("Share not found")
                    val secret = shareManagement.listSecrets().find { it.id == share.secretId }
                        ?: error("Secret not found")
                    val allRequests = shareManagement.listSentRequests()
                    val contacts = contactManagement.listContacts()
                    LoadResult(share, secret, allRequests, contacts)
                }
            }
                .onSuccess { (share, secret, allRequests, contacts) ->
                    val holderKey = contacts.find { it.id == share.contactId }?.verifyKey
                    val forThisShare = holderKey?.let { key ->
                        allRequests.filter { it.secretId == share.secretId && it.recipientKey.contentEquals(key) }
                    }.orEmpty()
                    val retrievalReq = forThisShare
                        .filter { it.transactionType == ShareTransactionType.RETRIEVAL }
                        .maxByOrNull { it.requestedAt }
                    val removalReq = forThisShare
                        .filter { it.transactionType == ShareTransactionType.REMOVAL }
                        .maxByOrNull { it.requestedAt }
                    val approvedCount = allRequests.count {
                        it.secretId == share.secretId &&
                            it.transactionType == ShareTransactionType.RETRIEVAL &&
                            it.state == ShareRequestState.APPROVED &&
                            it.ciphertext != null
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            share = share,
                            secret = secret,
                            contacts = contacts,
                            retrievalRequest = retrievalReq,
                            removalRequest = removalReq,
                            approvedRetrievalCount = approvedCount,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, error = R.string.share_detail_error_load) }
                }
        }
    }

    fun openRetrievalRequest() = openRequest(ShareTransactionType.RETRIEVAL)

    fun openRemovalRequest() = openRequest(ShareTransactionType.REMOVAL)

    private fun openRequest(type: ShareTransactionType) {
        val isRetrieval = type == ShareTransactionType.RETRIEVAL
        viewModelScope.launch {
            _uiState.update {
                if (isRetrieval) it.copy(isOpeningRetrieval = true, actionError = null)
                else it.copy(isOpeningRemoval = true, actionError = null)
            }
            runCatching {
                withContext(Dispatchers.IO) { shareManagement.openRequest(shareId, type) }
            }
                .onSuccess { load() }
                .onFailure {
                    _uiState.update {
                        if (isRetrieval) it.copy(isOpeningRetrieval = false, actionError = R.string.share_detail_error_open_request)
                        else it.copy(isOpeningRemoval = false, actionError = R.string.share_detail_error_open_request)
                    }
                }
        }
    }

    fun reconstruct() {
        val share = _uiState.value.share ?: return
        _uiState.update {
            it.copy(isReconstructing = true, actionError = null, reconstructedSecret = null, reconstructionIntegrity = null)
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { shareManagement.reconstruct(share.secretId) }
            }
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
                        R.string.share_detail_error_integrity
                    } else {
                        R.string.share_detail_error_reconstruct
                    }
                    _uiState.update { it.copy(isReconstructing = false, actionError = errorRes) }
                }
        }
    }
}
