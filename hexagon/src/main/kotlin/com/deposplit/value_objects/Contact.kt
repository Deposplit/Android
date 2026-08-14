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
) {
    override fun equals(other: Any?) = other is Contact && id == other.id
    override fun hashCode() = id.hashCode()
}
