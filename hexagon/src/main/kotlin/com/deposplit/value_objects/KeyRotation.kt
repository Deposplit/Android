package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

/**
 * A signed key-rotation push addressed to this device (item 9) — a contact's proactive "I am now
 * newEd25519Key, previously oldEd25519Key" notice. Deliberately not a [ShareRequest]: it carries
 * no `secretId` and has no consent phase — the recipient auto-verifies [signature] against
 * [oldEd25519Key] (the trusted key it already knows this contact by) and, on success, updates its
 * local contact record in place before deleting this notice. See
 * [PayloadCanonical.forRotation] for the exact bytes signed, and deposplit.com/CLAUDE.md "What is
 * next" item 9.
 */
data class KeyRotation(
    val id: UUID,
    val oldEd25519Key: ByteArray,
    val recipientKey: ByteArray,
    val newEd25519Key: ByteArray,
    val newX25519Key: ByteArray,
    val signature: ByteArray,
    val createdAt: Instant,
) {
    override fun equals(other: Any?) = other is KeyRotation && id == other.id
    override fun hashCode() = id.hashCode()
}
