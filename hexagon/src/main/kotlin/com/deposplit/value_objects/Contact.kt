package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

// Four-level ordinal verification model, derived from a trusted-channel x proof-of-life
// lattice; the two incomparable middle cells are
// merged into LOW, so the order is simply the count of independent assurances present (0/1/2),
// or 3 for physical co-presence. Kotlin enums are Comparable by declaration (ordinal) order, so
// this order is load-bearing — do not reorder the cases.
enum class VerificationLevel { VERY_LOW, LOW, HIGH, VERY_HIGH }

data class Contact(
    val id: UUID,
    val pseudonym: String,
    val verifyKey: ByteArray,
    val encKey: ByteArray,
    val verificationLevel: VerificationLevel,
    val verifiedAt: Instant?,
    val addedAt: Instant,
    // BYOR override — null means "use the device's configured default relay". A pinned snapshot
    // at contact-add time, not a live pointer, same TOFU trust model as the public keys.
    val relayBaseUrl: String? = null,
    // Historical verify keys locally flagged compromised via
    // ContactManagement.markKeyCompromised, out-of-band. A signed rotation notice claiming
    // continuity from any key in this set is refused auto-accept (see ShareService's
    // rotation-processing) — revocation is socially anchored, so only a fresh human-verified
    // relink can move the contact forward once a key lands here. Never cleared automatically.
    val revokedVerifyKeys: List<ByteArray> = emptyList(),
    // When verifyKey (or encKey) last changed via updateContact, whether through a
    // human-verified relink or an auto-accepted rotation. Null until the first key change.
    // Surfaced on the retrieve-approval screen as "this requester's key changed N days ago" —
    // the attack signature this hardens against is a key change followed by a quick retrieval
    // request.
    val keyChangedAt: Instant? = null,
    // The owner role: this contact (as a holder of one of my secrets) sent a signed opt-out
    // notice at this time: "my silence from here on is not a loss signal". Null means either
    // never opted out, or opted back in (cleared on the next non-opted-out heartbeat). Durable
    // and local — captured the instant the notice is observed, since the relay may lose its
    // state at any time and must never be relied on to keep this alert alive.
    val heartbeatOptedOutAt: Instant? = null,
    // The holder role: when this device last pushed a custodial heartbeat *to* this
    // contact (who is the owner in that relationship). Drives ShareService's opportunistic
    // per-sender emission cadence; reset to null by setHeartbeatEmissionOptedOut so a toggled
    // preference reaches the contact on the very next poll rather than waiting out the interval.
    val lastHeartbeatSentAt: Instant? = null,
    // The holder role: this device's own choice to stop heartbeating this contact (who is
    // the owner in that relationship). Defaults to false (heartbeating is opt-out, not opt-in).
    // When true, ShareService's emission loop still visits this contact on its normal cadence
    // but sends a signed opt-out notice instead of a normal heartbeat.
    val heartbeatEmissionOptedOut: Boolean = false,
    // The signing + key-agreement algorithm pairing this contact currently uses. Defaulted (not
    // required) purely to keep the crypto-agility rename from also being a "thread a new value
    // through every call site" exercise; the default is correct today (every contact really is
    // on this one suite), not a placeholder.
    val cipherSuite: CipherSuite = CipherSuite.current,
    // A purely local, optional label to disambiguate contacts who share the same
    // sender-asserted pseudonym (e.g. two different "Paul"s). Never transmitted anywhere — not in
    // the QR/link payload, any relay row, or any rotation/heartbeat/inventory push. Trimmed and
    // blank-collapsed-to-null by ContactService before it ever reaches this field.
    val nickname: String? = null,
) {
    override fun equals(other: Any?) = other is Contact && id == other.id
    override fun hashCode() = id.hashCode()
}

// The display-precedence policy ("nickname when set, else pseudonym") lives once on the
// domain object rather than being re-derived at every one of the app's render call sites.
val Contact.displayName: String get() = nickname ?: pseudonym
