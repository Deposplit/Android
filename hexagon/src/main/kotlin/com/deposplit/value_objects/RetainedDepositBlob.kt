package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

/**
 * The deposit-retention rule — the sender retains each per-holder encrypted blob locally
 * until that holder's pickup is confirmed (relay-observed or heartbeat-attested), then discards
 * it. This is what makes a relay GC before pickup a cheap re-deposit rather than a lost share:
 * each blob is encrypted to the *holder's* X25519 key, so the sender cannot decrypt
 * it herself — retaining all `n` is `n` opaque forward-only blobs, not a reconstructable secret
 * sitting on her device. [id] matches the originating deposit [ShareRequest]'s id (and therefore
 * [ShareMetadata.id]), so the record can be looked up and discarded by the same key
 * `syncDistributed` already keys off of.
 */
data class RetainedDepositBlob(
    val id: UUID,
    val secretId: UUID,
    val contactId: UUID,
    val label: String,
    val secretCreatedAt: Instant,
    val ciphertext: ByteArray,
    val k: Int,
    val n: Int,
) {
    override fun equals(other: Any?) = other is RetainedDepositBlob && id == other.id
    override fun hashCode() = id.hashCode()
}
