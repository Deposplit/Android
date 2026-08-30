package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

/**
 * A signed key-rotation push addressed to this device — a contact's proactive "I am now
 * newVerifyKey, previously oldVerifyKey" notice. Deliberately not a [ShareRequest]: it carries
 * no `secretId` and has no consent phase — the recipient auto-verifies [signature] against
 * [oldVerifyKey] (the trusted key it already knows this contact by) and, on success, updates its
 * local contact record in place before deleting this notice. See
 * [PayloadCanonical.forRotation] for the exact bytes signed.
 *
 * [newCipherSuite] is the signing + key-agreement algorithm pairing [newVerifyKey]/
 * [newEncKey] use. No `oldCipherSuite` field — the recipient already has it pinned on the existing
 * contact record being rotated away from.
 */
data class KeyRotation(
    val id: UUID,
    val oldVerifyKey: ByteArray,
    val recipientKey: ByteArray,
    val newVerifyKey: ByteArray,
    val newEncKey: ByteArray,
    val newCipherSuite: CipherSuite,
    val signature: ByteArray,
    val createdAt: Instant,
) {
    override fun equals(other: Any?) = other is KeyRotation && id == other.id
    override fun hashCode() = id.hashCode()
}
