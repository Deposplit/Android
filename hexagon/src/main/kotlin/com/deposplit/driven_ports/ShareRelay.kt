/*
 * The MIT License
 *
 * Copyright (c) 2026 Squeng AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

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

/**
 * An answer with an error status is thrown as [com.deposplit.value_objects.RelayRefusedException],
 * carrying the status. Anything else thrown means the relay did not answer, and the two are
 * reported differently.
 */
interface ShareRelay {
    /** The URL this relay answers at — what a warning names when it does not. */
    val baseUrl: String

    fun openShareRequest(
        secretId: UUID,
        recipientKey: ByteArray,
        sealedLabel: ByteArray,
        secretCreatedAt: Instant,
        transactionType: ShareTransactionType,
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
    /** Deletes a single request by id, and nothing else with it. */
    fun deleteShareRequest(requestId: UUID)

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
     * Pushes (upserts) a signed heartbeat for one owner, replacing any unread heartbeat this
     * device sent to that owner. [signature] must verify against the caller's own current
     * Ed25519 key (the relay's `holderKey`) over
     * [com.deposplit.value_objects.PayloadCanonical.forHeartbeat], [emittedAt] included.
     */
    fun pushHeartbeat(ownerKey: ByteArray, secretIds: List<UUID>, optedOut: Boolean, emittedAt: Instant, signature: ByteArray)
    /** The unread heartbeat (or opt-out) from each holder addressed to this device. */
    fun listHeartbeats(): List<CustodyHeartbeat>
    /** Deletes a heartbeat once read — see [CustodyHeartbeat]. */
    fun deleteHeartbeat(id: UUID)
}
