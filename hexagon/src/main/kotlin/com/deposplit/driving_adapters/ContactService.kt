package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.VerificationLevel
import java.time.Instant
import java.util.UUID

class ContactService(
    private val contactRepository: ContactRepository,
) : ContactManagement {

    override fun listContacts(): List<Contact> = contactRepository.getAll()

    override fun addManually(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray, verificationLevel: VerificationLevel, relayBaseUrl: String?) {
        require(pseudonym.isNotBlank()) { "Pseudonym must not be blank" }
        require(edPublicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        require(xPublicKey.size == 32) { "X25519 public key must be 32 bytes" }
        // Physical co-presence can't be asserted by typing a key in by hand — that's what the
        // in-person QR scan flow is for. See CLAUDE.md item 6.
        require(verificationLevel != VerificationLevel.VERY_HIGH) { "Very High verification requires an in-person QR scan" }
        val now = Instant.now()
        contactRepository.save(
            Contact(
                id = UUID.randomUUID(),
                pseudonym = pseudonym.trim(),
                edPublicKey = edPublicKey,
                xPublicKey = xPublicKey,
                verificationLevel = verificationLevel,
                verifiedAt = now,
                addedAt = now,
                relayBaseUrl = relayBaseUrl,
            )
        )
    }

    override fun addFromQr(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray, verificationLevel: VerificationLevel, relayBaseUrl: String?) {
        require(pseudonym.isNotBlank()) { "Pseudonym must not be blank" }
        require(edPublicKey.size == 32) { "Ed25519 public key must be 32 bytes" }
        require(xPublicKey.size == 32) { "X25519 public key must be 32 bytes" }
        val now = Instant.now()
        contactRepository.save(
            Contact(
                id = UUID.randomUUID(),
                pseudonym = pseudonym.trim(),
                edPublicKey = edPublicKey,
                xPublicKey = xPublicKey,
                verificationLevel = verificationLevel,
                verifiedAt = now,
                addedAt = now,
                relayBaseUrl = relayBaseUrl,
            )
        )
    }

    override fun updateContact(contactId: UUID, edPublicKey: ByteArray?, xPublicKey: ByteArray?, verificationLevel: VerificationLevel?) {
        val existing = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        val changingKeys = edPublicKey != null || xPublicKey != null
        require(!changingKeys || verificationLevel != null) {
            "A verification level must be chosen fresh whenever a contact's keys change"
        }
        edPublicKey?.let { require(it.size == 32) { "Ed25519 public key must be 32 bytes" } }
        xPublicKey?.let { require(it.size == 32) { "X25519 public key must be 32 bytes" } }
        contactRepository.save(
            existing.copy(
                edPublicKey = edPublicKey ?: existing.edPublicKey,
                xPublicKey = xPublicKey ?: existing.xPublicKey,
                verificationLevel = verificationLevel ?: existing.verificationLevel,
                verifiedAt = if (verificationLevel != null) Instant.now() else existing.verifiedAt,
                revokedEdKeys = existing.revokedEdKeys,
                keyChangedAt = if (changingKeys) Instant.now() else existing.keyChangedAt,
            )
        )
    }

    override fun deleteContact(contactId: UUID) = contactRepository.delete(contactId)

    // Item 10 — idempotent: a no-op if the key is already in revokedEdKeys.
    override fun markKeyCompromised(contactId: UUID, edPublicKey: ByteArray?) {
        val existing = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        val keyToFlag = edPublicKey ?: existing.edPublicKey
        if (existing.revokedEdKeys.any { it.contentEquals(keyToFlag) }) return
        contactRepository.save(existing.copy(revokedEdKeys = existing.revokedEdKeys + keyToFlag))
    }
}
