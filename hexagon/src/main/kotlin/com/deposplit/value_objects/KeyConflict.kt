package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

/**
 * Item 10 — captured the instant a rotation notice's [oldVerifyKey] is found in a contact's
 * [Contact.revokedEdKeys]. Durable and local: the relay may lose its state at any time, so this is
 * saved before the corresponding relay notice is deleted, never re-derived from the relay later.
 * Never auto-resolved — the only path forward is a fresh human-verified relink (the existing item-8
 * "Relink (Key Changed)" flow); this record only exists to be surfaced and dismissed.
 */
data class KeyConflict(
    val id: UUID,
    val contactId: UUID,
    val oldVerifyKey: ByteArray,
    val newVerifyKey: ByteArray,
    val newEncKey: ByteArray,
    val detectedAt: Instant,
) {
    override fun equals(other: Any?) = other is KeyConflict && id == other.id
    override fun hashCode() = id.hashCode()
}
