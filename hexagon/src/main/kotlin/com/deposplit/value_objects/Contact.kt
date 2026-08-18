package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

// Four-level ordinal verification model — see deposplit.com/CLAUDE.md "What is next" item 6.
// Derived from a trusted-channel x proof-of-life lattice; the two incomparable middle cells are
// merged into LOW, so the order is simply the count of independent assurances present (0/1/2),
// or 3 for physical co-presence. Kotlin enums are Comparable by declaration (ordinal) order, so
// this order is load-bearing — do not reorder the cases.
enum class VerificationLevel { VERY_LOW, LOW, HIGH, VERY_HIGH }

data class Contact(
    val id: UUID,
    val pseudonym: String,
    val edPublicKey: ByteArray,
    val xPublicKey: ByteArray,
    val verificationLevel: VerificationLevel,
    val verifiedAt: Instant?,
    val addedAt: Instant,
    // BYOR override — null means "use the device's configured default relay". A pinned snapshot
    // at contact-add time, not a live pointer, same TOFU trust model as the public keys.
    val relayBaseUrl: String? = null,
    // Item 10 — historical Ed25519 keys locally flagged compromised via
    // ContactManagement.markKeyCompromised, out-of-band. A signed rotation notice claiming
    // continuity from any key in this set is refused auto-accept (see ShareService's
    // rotation-processing) — revocation is socially anchored, so only a fresh human-verified
    // relink can move the contact forward once a key lands here. Never cleared automatically.
    val revokedEdKeys: List<ByteArray> = emptyList(),
    // Item 10 — when edPublicKey (or xPublicKey) last changed via updateContact, whether through a
    // human-verified relink (item 8) or an auto-accepted rotation (item 9). Null until the first
    // key change. Surfaced on the retrieve-approval screen as "this requester's key changed N days
    // ago" — the attack signature item 10 hardens against is key change followed by a quick
    // retrieval request.
    val keyChangedAt: Instant? = null,
) {
    override fun equals(other: Any?) = other is Contact && id == other.id
    override fun hashCode() = id.hashCode()
}
