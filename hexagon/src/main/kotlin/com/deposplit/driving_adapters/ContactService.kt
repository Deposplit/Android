package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.VerificationLevel
import java.time.Instant
import java.util.UUID

class ContactService(
    private val contactRepository: ContactRepository,
) : ContactManagement {

    override fun listContacts(): List<Contact> = contactRepository.getAll()

    // No cipherSuite parameter: manual entry has no wire payload to read one from, and only one
    // suite exists to assume — see ContactManagement.addManually.
    override fun addManually(pseudonym: String, verifyKey: ByteArray, encKey: ByteArray, verificationLevel: VerificationLevel, relayBaseUrl: String?) {
        val cipherSuite = CipherSuite.current
        require(pseudonym.isNotBlank()) { "Pseudonym must not be blank" }
        require(verifyKey.size == cipherSuite.verifyKeyLength) { "Verify key must be ${cipherSuite.verifyKeyLength} bytes for $cipherSuite" }
        require(encKey.size == cipherSuite.encKeyLength) { "Enc key must be ${cipherSuite.encKeyLength} bytes for $cipherSuite" }
        // Physical co-presence can't be asserted by typing a key in by hand — that's what the
        // in-person QR scan flow is for. See CLAUDE.md item 6.
        require(verificationLevel != VerificationLevel.VERY_HIGH) { "Very High verification requires an in-person QR scan" }
        val now = Instant.now()
        contactRepository.save(
            Contact(
                id = UUID.randomUUID(),
                pseudonym = pseudonym.trim(),
                verifyKey = verifyKey,
                encKey = encKey,
                verificationLevel = verificationLevel,
                verifiedAt = now,
                addedAt = now,
                relayBaseUrl = relayBaseUrl,
                cipherSuite = cipherSuite,
            )
        )
    }

    override fun addFromQr(pseudonym: String, verifyKey: ByteArray, encKey: ByteArray, cipherSuite: CipherSuite, verificationLevel: VerificationLevel, relayBaseUrl: String?) {
        require(pseudonym.isNotBlank()) { "Pseudonym must not be blank" }
        require(verifyKey.size == cipherSuite.verifyKeyLength) { "Verify key must be ${cipherSuite.verifyKeyLength} bytes for $cipherSuite" }
        require(encKey.size == cipherSuite.encKeyLength) { "Enc key must be ${cipherSuite.encKeyLength} bytes for $cipherSuite" }
        val now = Instant.now()
        contactRepository.save(
            Contact(
                id = UUID.randomUUID(),
                pseudonym = pseudonym.trim(),
                verifyKey = verifyKey,
                encKey = encKey,
                verificationLevel = verificationLevel,
                verifiedAt = now,
                addedAt = now,
                relayBaseUrl = relayBaseUrl,
                cipherSuite = cipherSuite,
            )
        )
    }

    override fun updateContact(contactId: UUID, verifyKey: ByteArray?, encKey: ByteArray?, cipherSuite: CipherSuite?, verificationLevel: VerificationLevel?) {
        val existing = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        // Item 14 — a cipher-suite-only change (no key-value change) forces the same fresh-level
        // rule as a key change: it's still continuity of key control, not a fresh personhood check.
        val changingIdentity = verifyKey != null || encKey != null || cipherSuite != null
        require(!changingIdentity || verificationLevel != null) {
            "A verification level must be chosen fresh whenever a contact's keys or cipher suite change"
        }
        val effectiveSuite = cipherSuite ?: existing.cipherSuite
        verifyKey?.let { require(it.size == effectiveSuite.verifyKeyLength) { "Verify key must be ${effectiveSuite.verifyKeyLength} bytes for $effectiveSuite" } }
        encKey?.let { require(it.size == effectiveSuite.encKeyLength) { "Enc key must be ${effectiveSuite.encKeyLength} bytes for $effectiveSuite" } }
        contactRepository.save(
            existing.copy(
                verifyKey = verifyKey ?: existing.verifyKey,
                encKey = encKey ?: existing.encKey,
                cipherSuite = effectiveSuite,
                verificationLevel = verificationLevel ?: existing.verificationLevel,
                verifiedAt = if (verificationLevel != null) Instant.now() else existing.verifiedAt,
                revokedEdKeys = existing.revokedEdKeys,
                keyChangedAt = if (changingIdentity) Instant.now() else existing.keyChangedAt,
            )
        )
    }

    override fun deleteContact(contactId: UUID) = contactRepository.delete(contactId)

    // Item 10 — idempotent: a no-op if the key is already in revokedEdKeys.
    override fun markKeyCompromised(contactId: UUID, verifyKey: ByteArray?) {
        val existing = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        val keyToFlag = verifyKey ?: existing.verifyKey
        if (existing.revokedEdKeys.any { it.contentEquals(keyToFlag) }) return
        contactRepository.save(existing.copy(revokedEdKeys = existing.revokedEdKeys + keyToFlag))
    }
}
