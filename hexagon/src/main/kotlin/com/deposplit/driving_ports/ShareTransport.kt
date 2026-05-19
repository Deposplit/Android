package com.deposplit.driving_ports

import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestState
import com.deposplit.value_objects.ShareRequestType
import com.deposplit.value_objects.Role
import java.util.UUID

interface ShareTransport {
    fun depositShare(
        secretId: UUID,
        label: String,
        recipientKey: ByteArray,
        ciphertext: ByteArray,
    ): ShareMetadata

    fun listShares(role: Role, counterpartyKey: ByteArray? = null): List<ShareMetadata>

    fun pickUpShare(shareId: UUID): ByteArray

    fun deleteShare(shareId: UUID)

    fun openShareRequest(shareId: UUID, type: ShareRequestType): ShareRequest

    fun listShareRequests(role: Role, state: ShareRequestState? = null): List<ShareRequest>

    fun getShareRequest(requestId: UUID): ShareRequest

    fun respondToShareRequest(requestId: UUID, approved: Boolean, ciphertext: ByteArray? = null): ShareRequest
}
