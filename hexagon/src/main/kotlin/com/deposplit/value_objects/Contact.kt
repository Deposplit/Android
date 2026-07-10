package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

enum class VerificationLevel { UNVERIFIED, VERIFIED }

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
