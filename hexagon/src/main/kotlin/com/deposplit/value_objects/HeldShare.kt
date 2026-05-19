package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

data class HeldShare(
    val id: UUID,
    val secretId: UUID,
    val label: String,
    val senderKey: ByteArray,
    val createdAt: Instant,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?) = other is HeldShare && id == other.id
    override fun hashCode() = id.hashCode()
}
