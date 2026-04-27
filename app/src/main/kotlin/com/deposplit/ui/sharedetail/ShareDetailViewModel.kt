package com.deposplit.ui.sharedetail

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deposplit.R
import com.deposplit.api.Role
import com.deposplit.api.ShareMetadata
import com.deposplit.api.ShareRequest
import com.deposplit.api.ShareRequestState
import com.deposplit.api.ShareRequestType
import com.deposplit.api.ShareTransport
import com.deposplit.auth.AuthPort
import com.deposplit.contacts.Contact
import com.deposplit.contacts.ContactRepository
import com.deposplit.shamir.combine
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
    private val auth: AuthPort,
    private val transport: ShareTransport,
    private val contactRepository: ContactRepository,
) : ViewModel() {

    data class UiState(
        val share: ShareMetadata? = null,
        val contacts: List<Contact> = emptyList(),
        val retrieveRequest: ShareRequest? = null,
        val deleteRequest: ShareRequest? = null,
        val approvedRetrieveCount: Int = 0,
        val isLoading: Boolean = false,
        val isOpeningRetrieve: Boolean = false,
        val isOpeningDelete: Boolean = false,
        val isReconstructing: Boolean = false,
        val reconstructedSecret: String? = null,
        @StringRes val error: Int? = null,
        @StringRes val actionError: Int? = null,
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
                    val shares = transport.listShares(Role.SENDER)
                    val share = shares.find { it.id == shareId }
                        ?: error("Share not found")
                    val allRequests = transport.listShareRequests(Role.SENDER)
                    val contacts = contactRepository.getAll()
                    Triple(share, allRequests, contacts)
                }
            }
                .onSuccess { (share, allRequests, contacts) ->
                    val forThisShare = allRequests.filter { it.share.id == shareId }
                    val retrieveReq = forThisShare
                        .filter { it.requestType == ShareRequestType.RETRIEVE }
                        .maxByOrNull { it.requestedAt }
                    val deleteReq = forThisShare
                        .filter { it.requestType == ShareRequestType.DELETE }
                        .maxByOrNull { it.requestedAt }
                    val approvedCount = allRequests.count {
                        it.share.secretId == share.secretId &&
                            it.requestType == ShareRequestType.RETRIEVE &&
                            it.state == ShareRequestState.APPROVED &&
                            it.ciphertext != null
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            share = share,
                            contacts = contacts,
                            retrieveRequest = retrieveReq,
                            deleteRequest = deleteReq,
                            approvedRetrieveCount = approvedCount,
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(isLoading = false, error = R.string.share_detail_error_load) }
                }
        }
    }

    fun openRetrieveRequest() = openRequest(ShareRequestType.RETRIEVE)

    fun openDeleteRequest() = openRequest(ShareRequestType.DELETE)

    private fun openRequest(type: ShareRequestType) {
        val isRetrieve = type == ShareRequestType.RETRIEVE
        viewModelScope.launch {
            _uiState.update {
                if (isRetrieve) it.copy(isOpeningRetrieve = true, actionError = null)
                else it.copy(isOpeningDelete = true, actionError = null)
            }
            runCatching {
                withContext(Dispatchers.IO) { transport.openShareRequest(shareId, type) }
            }
                .onSuccess { load() }
                .onFailure {
                    _uiState.update {
                        if (isRetrieve) it.copy(isOpeningRetrieve = false, actionError = R.string.share_detail_error_open_request)
                        else it.copy(isOpeningDelete = false, actionError = R.string.share_detail_error_open_request)
                    }
                }
        }
    }

    fun reconstruct() {
        val state = _uiState.value
        val share = state.share ?: return
        _uiState.update { it.copy(isReconstructing = true, actionError = null, reconstructedSecret = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val allRequests = transport.listShareRequests(Role.SENDER)
                    val approvedShares = allRequests.filter {
                        it.share.secretId == share.secretId &&
                            it.requestType == ShareRequestType.RETRIEVE &&
                            it.state == ShareRequestState.APPROVED &&
                            it.ciphertext != null
                    }
                    check(approvedShares.size >= 2) {
                        "Need at least 2 approved shares to reconstruct (have ${approvedShares.size})"
                    }
                    val contacts = contactRepository.getAll()
                    val decryptedShares = approvedShares.map { req ->
                        val contact = contacts.find { it.edPublicKey.contentEquals(req.share.recipientKey) }
                            ?: error("Contact not found for recipient key — cannot decrypt share")
                        auth.decrypt(req.ciphertext!!, contact.xPublicKey)
                    }
                    val secretBytes = combine(decryptedShares)
                    // Best-effort relay cleanup — sender deletes each share row after pickup
                    for (req in approvedShares) {
                        runCatching { transport.deleteShare(req.share.id) }
                    }
                    secretBytes.toString(Charsets.UTF_8)
                }
            }
                .onSuccess { secret ->
                    _uiState.update { it.copy(isReconstructing = false, reconstructedSecret = secret) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(isReconstructing = false, actionError = R.string.share_detail_error_reconstruct)
                    }
                }
        }
    }
}
