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

    override fun deleteContact(contactId: UUID) = contactRepository.delete(contactId)
}
