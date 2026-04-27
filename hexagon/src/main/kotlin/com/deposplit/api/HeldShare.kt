package com.deposplit.api

import java.util.UUID

data class HeldShare(
    val id: UUID,
    val secretId: UUID,
    val label: String,
    val senderKey: ByteArray,
    val createdAt: String,
    val ciphertext: ByteArray,
) {
    override fun equals(other: Any?) = other is HeldShare && id == other.id
    override fun hashCode() = id.hashCode()
}

interface ShareRepository {
    fun getAll(): List<HeldShare>
    fun getCiphertext(shareId: UUID): ByteArray?
    fun save(share: HeldShare)
    fun delete(shareId: UUID)
}
