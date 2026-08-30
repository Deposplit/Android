package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

/**
 * A signed custodial-heartbeat push addressed to this device — a holder's proactive
 * "still guarding {secretIds} for you" notice (or, when [optedOut] is true, a signed "my silence
 * from here on is not a loss signal" notice). Deliberately not a [ShareRequest]: no singular
 * `secretId`, no consent phase — and unlike [KeyRotation], never consumed-and-deleted: the relay
 * keeps only the latest heartbeat per (holder, owner) pair, so this is read repeatedly, not
 * drained. See [PayloadCanonical.forHeartbeat] for the exact bytes signed, and
 */
data class CustodyHeartbeat(
    val id: UUID,
    val holderKey: ByteArray,
    val ownerKey: ByteArray,
    val secretIds: List<UUID>,
    val optedOut: Boolean,
    val signature: ByteArray,
    val createdAt: Instant,
) {
    override fun equals(other: Any?) = other is CustodyHeartbeat && id == other.id
    override fun hashCode() = id.hashCode()
}
