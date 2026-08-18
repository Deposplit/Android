package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

enum class Role { SENDER, RECIPIENT }

// The kind of thing that happened (or is being asked to happen) to a share, phrased as a neutral
// transaction noun rather than either party's verb — see deposplit.com/CLAUDE.md "Cross-cutting
// implementation chores" for why: naming from a single named actor's point of view (Alice's, or
// Bob's) breaks down because the actor genuinely alternates — Alice always opens
// DEPOSIT/RETRIEVAL/REMOVAL, but the *holder* opens INVENTORY (holder → owner).
//
// INVENTORY is a holder-initiated metadata-only push during identity recovery — not
// consent-gated, unlike the other three. See deposplit.com/CLAUDE.md "What is next" item 8.
//
// wireValue is the single source of truth for this type's wire representation — the JSON
// transactionType value and the string PayloadCanonical signs are both the same wireValue,
// looked up here rather than re-derived by each adapter (mirrors the relay's ShareTransactionType
// and iOS's rawValue-based enum).
enum class ShareTransactionType(val wireValue: String) {
    DEPOSIT("deposit"),
    RETRIEVAL("retrieval"),
    REMOVAL("removal"),
    INVENTORY("inventory");

    companion object {
        fun fromWire(value: String): ShareTransactionType? = entries.find { it.wireValue == value }
    }
}
// WITHDRAWN is deposit-only (item 9): the recipient unilaterally stopped holding the share. A
// best-effort tombstone, not authoritative — see ShareRelay.withdrawShareRequests.
enum class ShareRequestState { PENDING, APPROVED, DENIED, WITHDRAWN }

// Per-share record on the sender's device — one per holder of a Secret. Normalized to reference
// its parent Secret (by secretId) rather than duplicating label/secretCreatedAt — see
// deposplit.com/CLAUDE.md "What is next" item 11.
data class ShareMetadata(
    val id: UUID,           // Deposit request ID
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
    val transactionType: ShareTransactionType,
    val state: ShareRequestState,
    val shareId: UUID?,
    val requestedAt: Instant,
    val respondedAt: Instant?,
    val ciphertext: ByteArray?,
    // SSS threshold/share-count — populated for DEPOSIT/INVENTORY, null for
    // RETRIEVAL/REMOVAL. See deposplit.com/CLAUDE.md "What is next" items 8 and 11.
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
