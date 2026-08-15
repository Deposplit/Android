package com.deposplit.driving_ports

import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareRequestType
import java.util.UUID

interface ShareManagement {
    // ─── Sender ───────────────────────────────────────────────────────────────
    fun deposit(secret: ByteArray, label: String, contacts: List<Contact>, threshold: Int)
    fun listSecrets(): List<Secret>
    fun syncDistributed()
    fun listDistributed(): List<ShareMetadata>
    fun listSentRequests(): List<ShareRequest>
    fun requestAll(secretId: UUID)
    fun openRequest(shareId: UUID, type: ShareRequestType): ShareRequest
    // Pure read (item 11) — collects k approved retrieve shares and decrypts them. Never tears
    // down local ShareMetadata or relay rows; use discardSecret for that.
    fun reconstruct(secretId: UUID): ByteArray
    // Fans out a sender-initiated delete request to every known holder of secretId and flips the
    // Secret to DISCARDING immediately (before any holder responds).
    fun discardSecret(secretId: UUID)
    // Local-only teardown for a DISCARDING secret whose holders will never all respond (e.g. a
    // permanently dark holder). Does not wait for or require relay confirmation.
    fun forceForgetSecret(secretId: UUID)

    // ─── Recipient ────────────────────────────────────────────────────────────
    fun syncInbox()
    fun listHeld(): List<HeldShare>
    fun listPendingRequests(): List<ShareRequest>
    fun respond(requestId: UUID, approved: Boolean)
    fun deleteHeldShare(shareId: UUID)
    fun deleteAllHeldFromSender(contactId: UUID)
}
