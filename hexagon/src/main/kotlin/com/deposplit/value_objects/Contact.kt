package com.deposplit.value_objects

import java.util.UUID

enum class VerificationLevel { UNVERIFIED, VERIFIED }

data class Contact(
    val id: UUID,
    val pseudonym: String,
    val edPublicKey: ByteArray,
    val xPublicKey: ByteArray,
    val verificationLevel: VerificationLevel,
    val verifiedAt: String?,
    val addedAt: String,
) {
    override fun equals(other: Any?) = other is Contact && id == other.id
    override fun hashCode() = id.hashCode()
}
