package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

enum class Role { SENDER, RECIPIENT }
enum class ShareRequestType { PICK_UP, RETRIEVE, DELETE }
enum class ShareRequestState { PENDING, APPROVED, DENIED }

data class ShareMetadata(
    val id: UUID,           // PickUp request ID
    val secretId: UUID,
    val label: String,
    val recipientKey: ByteArray,
    val secretCreatedAt: Instant,
) {
    override fun equals(other: Any?) = other is ShareMetadata && id == other.id
    override fun hashCode() = id.hashCode()
}

data class ShareRequest(
    val id: UUID,
    val secretId: UUID,
    val senderKey: ByteArray,
    val recipientKey: ByteArray,
    val label: String,
    val secretCreatedAt: Instant,
    val requestType: ShareRequestType,
    val state: ShareRequestState,
    val shareId: UUID?,
    val requestedAt: Instant,
    val respondedAt: Instant?,
    val ciphertext: ByteArray?,
    // Ed25519 signature over PayloadCanonical.forOpen — see that object for what's signed.
    val senderSignature: ByteArray,
    // Ed25519 signature over PayloadCanonical.forRespond; null while pending.
    val recipientSignature: ByteArray?,
) {
    override fun equals(other: Any?) = other is ShareRequest && id == other.id
    override fun hashCode() = id.hashCode()
}
