package com.deposplit.driven_ports

import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.CustodyHeartbeat
import com.deposplit.value_objects.KeyRotation
import com.deposplit.value_objects.MimeType
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
        mimeType: MimeType? = null,
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

    // Recipient-initiated unilateral withdrawal — flips matching approved Deposit rows
    // to WITHDRAWN on the relay instead of deleting them, so the sender's next poll can observe
    // the tombstone. Best-effort and fire-and-forget.
    fun withdrawShareRequests(senderKey: ByteArray? = null, secretId: UUID? = null)

    // The signed rotate(K_old -> K_new) push. Grouped onto this interface rather than a
    // separate port: it's the same physical relay endpoint and the same BYOR per-contact routing
    // as every other ShareRelay call. deposplit.com's own backend keeps rotation pushes in a
    // dedicated key_rotations table/KeyRotations service for domain-purity reasons (no secretId,
    // no consent phase) that are about server-side schema shape, not about this client-side
    // HTTP-calling port, so no equivalent split is needed here.

    /**
     * Pushes a signed rotation notice to one contact. [signature] must verify against the
     * caller's own current verify key (the relay's `oldVerifyKey`) over
     * [com.deposplit.value_objects.PayloadCanonical.forRotation]. [newCipherSuite] is
     * the signing + key-agreement algorithm pairing [newVerifyKey]/[newEncKey] use.
     */
    fun pushRotation(recipientKey: ByteArray, newVerifyKey: ByteArray, newEncKey: ByteArray, newCipherSuite: CipherSuite, signature: ByteArray)
    /** Rotation notices addressed to this device. */
    fun listRotations(): List<KeyRotation>
    /** Deletes a rotation notice once consumed. */
    fun deleteRotation(id: UUID)

    // The signed custodial-heartbeat push — same "grouped onto this interface" reasoning as
    // the rotation push above: one physical relay, one BYOR routing scheme.

    /**
     * Pushes (upserts) a signed heartbeat for one owner, replacing any previous heartbeat this
     * device sent to that owner. [signature] must verify against the caller's own current
     * Ed25519 key (the relay's `holderKey`) over
     * [com.deposplit.value_objects.PayloadCanonical.forHeartbeat].
     */
    fun pushHeartbeat(ownerKey: ByteArray, secretIds: List<UUID>, optedOut: Boolean, signature: ByteArray)
    /** The latest heartbeat (or opt-out) from each holder addressed to this device. */
    fun listHeartbeats(): List<CustodyHeartbeat>
}
