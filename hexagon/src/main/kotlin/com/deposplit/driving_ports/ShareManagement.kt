package com.deposplit.driving_ports

import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestType
import java.util.UUID

interface ShareManagement {
    // ─── Sender ───────────────────────────────────────────────────────────────
    fun deposit(secret: ByteArray, label: String, contacts: List<Contact>, threshold: Int)
    fun listDistributed(): List<ShareMetadata>
    fun listSentRequests(): List<ShareRequest>
    fun requestAll(secretId: UUID)
    fun openRequest(shareId: UUID, type: ShareRequestType): ShareRequest
    fun reconstruct(secretId: UUID): ByteArray

    // ─── Recipient ────────────────────────────────────────────────────────────
    fun syncInbox()
    fun listHeld(): List<HeldShare>
    fun listPendingRequests(): List<ShareRequest>
    fun respond(requestId: UUID, approved: Boolean)
    fun deleteHeldShare(shareId: UUID)
    fun deleteAllHeldFromSender(senderKey: ByteArray)
}
