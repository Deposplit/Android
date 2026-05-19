package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

enum class Role { SENDER, RECIPIENT }
enum class ShareRequestType { RETRIEVE, DELETE }
enum class ShareRequestState { PENDING, APPROVED, DENIED }

data class ShareMetadata(
    val id: UUID,
    val secretId: UUID,
    val label: String,
    val senderKey: ByteArray,
    val recipientKey: ByteArray,
    val createdAt: Instant,
    val pickedUpAt: Instant? = null,
) {
    override fun equals(other: Any?) = other is ShareMetadata && id == other.id
    override fun hashCode() = id.hashCode()
}

data class ShareRequest(
    val id: UUID,
    val share: ShareMetadata,
    val requestType: ShareRequestType,
    val state: ShareRequestState,
    val requestedAt: Instant,
    val respondedAt: Instant?,
    val ciphertext: ByteArray?,
) {
    override fun equals(other: Any?) = other is ShareRequest && id == other.id
    override fun hashCode() = id.hashCode()
}
