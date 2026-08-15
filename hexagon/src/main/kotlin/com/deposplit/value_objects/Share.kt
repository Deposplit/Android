package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

enum class Role { SENDER, RECIPIENT }

// RECOVERY_METADATA is a holder-initiated metadata-only push during identity recovery — not
// consent-gated, unlike the other three. See deposplit.com/CLAUDE.md "What is next" item 8.
enum class ShareRequestType { PICK_UP, RETRIEVE, DELETE, RECOVERY_METADATA }
enum class ShareRequestState { PENDING, APPROVED, DENIED }

// Per-share record on the sender's device — one per holder of a Secret. Normalized to reference
// its parent Secret (by secretId) rather than duplicating label/secretCreatedAt — see
// deposplit.com/CLAUDE.md "What is next" item 11.
data class ShareMetadata(
    val id: UUID,           // PickUp request ID
    val secretId: UUID,
    // The holder's stable local contact id — not their Ed25519 key — so this record survives a
    // holder key rotation/recovery (see deposplit.com/CLAUDE.md "What is next" item 7).
    val contactId: UUID,
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
    // SSS threshold/share-count — populated for PICK_UP/RECOVERY_METADATA, null for
    // RETRIEVE/DELETE. See deposplit.com/CLAUDE.md "What is next" items 8 and 11.
    val k: Int? = null,
    val n: Int? = null,
    // Ed25519 signature over PayloadCanonical.forOpen — see that object for what's signed.
    val senderSignature: ByteArray,
    // Ed25519 signature over PayloadCanonical.forRespond; null while pending.
    val recipientSignature: ByteArray?,
) {
    override fun equals(other: Any?) = other is ShareRequest && id == other.id
    override fun hashCode() = id.hashCode()
}
