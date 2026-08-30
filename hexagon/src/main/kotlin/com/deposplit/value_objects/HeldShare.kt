package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

data class HeldShare(
    val id: UUID,
    val secretId: UUID,
    val label: String,
    // The sender's stable local contact id — not their Ed25519 key — so this record survives a
    // sender key rotation/recovery.
    val contactId: UUID,
    // Denormalized snapshot of the sender's pseudonym at pickup time, so a share from a
    // since-deleted contact still renders sensibly.
    val senderPseudonym: String,
    val createdAt: Instant,
    val pickedUpAt: Instant,
    // The decrypted share, plaintext at rest: a single holder's share is
    // information-theoretically empty on its own, so this is safe to store unencrypted.
    val plaintextShare: ByteArray,
    // SSS threshold/share-count, carried on the deposit that produced this share — reported back
    // during identity recovery so a recovering owner can rebuild her Secret record.
    val k: Int,
    val n: Int,
) {
    override fun equals(other: Any?) = other is HeldShare && id == other.id
    override fun hashCode() = id.hashCode()
}
