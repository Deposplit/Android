package com.deposplit.contacts

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

interface ContactRepository {
    fun getAll(): List<Contact>
    fun getByEdKey(edPublicKey: ByteArray): Contact?
    fun save(contact: Contact)
    fun delete(contactId: UUID)
}
