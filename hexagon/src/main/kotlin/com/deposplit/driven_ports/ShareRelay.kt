package com.deposplit.driven_ports

import com.deposplit.value_objects.Role
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareTransactionType
import java.time.Instant
import java.util.UUID

interface ShareRelay {
    fun openShareRequest(
        secretId: UUID,
        recipientKey: ByteArray,
        label: String,
        secretCreatedAt: Instant,
        transactionType: ShareTransactionType,
        shareId: UUID?,
        ciphertext: ByteArray?,
        k: Int? = null,
        n: Int? = null,
        senderSignature: ByteArray,
    ): ShareRequest
    fun listShareRequests(role: Role, transactionType: ShareTransactionType? = null, state: ShareRequestState? = null): List<ShareRequest>
    fun getShareRequest(requestId: UUID): ShareRequest
    fun respondToShareRequest(
        requestId: UUID,
        approved: Boolean,
        ciphertext: ByteArray? = null,
        recipientSignature: ByteArray,
    ): ShareRequest
    fun deleteShareRequest(requestId: UUID)
    fun deleteShareRequests(senderKey: ByteArray? = null, secretId: UUID? = null)
}
