package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

/**
 * A signed custodial-heartbeat push addressed to this device — a holder's proactive
 * "still guarding {secretIds} for you" notice (or, when [optedOut] is true, a signed "my silence
 * from here on is not a loss signal" notice). Deliberately not a [ShareRequest]: no singular
 * `secretId`, no consent phase. Like [KeyRotation] it is consumed and deleted once read: what it
 * says is kept on this device, and the holder re-emits every interval.
 *
 * [emittedAt] is the holder's own clock at signing, covered by the signature — the time custody
 * is dated from. The relay's receipt time is deliberately not carried: it is unsigned, and a relay
 * that could set it could keep a lost holder looking fresh. See [PayloadCanonical.forHeartbeat]
 * for the exact bytes signed.
 */
data class CustodyHeartbeat(
    val id: UUID,
    val holderKey: ByteArray,
    val ownerKey: ByteArray,
    val secretIds: List<UUID>,
    val optedOut: Boolean,
    val emittedAt: Instant,
    val signature: ByteArray,
) {
    override fun equals(other: Any?) = other is CustodyHeartbeat && id == other.id
    override fun hashCode() = id.hashCode()
}
