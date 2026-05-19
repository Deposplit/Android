package com.deposplit.value_objects

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
    val createdAt: String,
    val pickedUpAt: String? = null,
) {
    override fun equals(other: Any?) = other is ShareMetadata && id == other.id
    override fun hashCode() = id.hashCode()
}

data class ShareRequest(
    val id: UUID,
    val share: ShareMetadata,
    val requestType: ShareRequestType,
    val state: ShareRequestState,
    val requestedAt: String,
    val respondedAt: String?,
    val ciphertext: ByteArray?,
) {
    override fun equals(other: Any?) = other is ShareRequest && id == other.id
    override fun hashCode() = id.hashCode()
}
