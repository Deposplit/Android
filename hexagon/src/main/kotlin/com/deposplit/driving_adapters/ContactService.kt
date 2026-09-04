package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRelinkRepository
import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.IdentityStore
import com.deposplit.driven_ports.PurchaseRepository
import com.deposplit.driving_ports.ContactManagement
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.ContactRelink
import com.deposplit.value_objects.PremiumRequiredException
import com.deposplit.value_objects.VerificationLevel
import java.time.Instant
import java.util.UUID

class ContactService(
    private val contactRepository: ContactRepository,
    private val purchases: PurchaseRepository,
    private val identityStore: IdentityStore,
    private val relinkRepository: ContactRelinkRepository,
) : ContactManagement {

    override fun listContacts(): List<Contact> = contactRepository.getAll()

    override fun contactsAwaitingRelink(): List<Contact> {
        // No recorded start means no basis to judge, and flagging every contact on a guess would be
        // a false alarm on a device that never lost anything.
        val identityCreatedAt = identityStore.identityCreatedAt() ?: return emptyList()
        return contactRepository.getAll().filter { contact ->
            if (!contact.addedAt.isBefore(identityCreatedAt)) return@filter false
            val relinkedAt = relinkRepository.get(contact.id)?.observedAt
            relinkedAt == null || relinkedAt.isBefore(identityCreatedAt)
        }
    }

    override fun markRelinked(contactId: UUID) {
        // Called once per inbound row as well as by the user, so it skips the write when the answer
        // would not change — otherwise a single poll would rewrite the store for every row it reads.
        val identityCreatedAt = identityStore.identityCreatedAt()
        val alreadyRecorded = relinkRepository.get(contactId)?.observedAt
        if (identityCreatedAt != null && alreadyRecorded != null && !alreadyRecorded.isBefore(identityCreatedAt)) return
        relinkRepository.save(ContactRelink(contactId, Instant.now()))
    }

    // No cipherSuite parameter: manual entry has no wire payload to read one from, and only one
    // suite exists to assume — see ContactManagement.addManually.
    override fun addManually(pseudonym: String, verifyKey: ByteArray, encKey: ByteArray, verificationLevel: VerificationLevel, relayBaseUrl: String?, nickname: String?) {
        val cipherSuite = CipherSuite.current
        require(pseudonym.isNotBlank()) { "Pseudonym must not be blank" }
        require(verifyKey.size == cipherSuite.verifyKeyLength) { "Verify key must be ${cipherSuite.verifyKeyLength} bytes for $cipherSuite" }
        require(encKey.size == cipherSuite.encKeyLength) { "Enc key must be ${cipherSuite.encKeyLength} bytes for $cipherSuite" }
        // Physical co-presence can't be asserted by typing a key in by hand — that's what the
        // in-person QR scan flow is for.
        require(verificationLevel != VerificationLevel.VERY_HIGH) { "Very High verification requires an in-person QR scan" }
        // Typing a relay by hand is the paid half of BYOR. Reading one out of a scanned QR code is
        // not, and addFromQr below is deliberately left open: that URL is the contact stating where
        // their own mailbox is, and refusing it would mean a free device simply cannot share with
        // anyone who self-hosts.
        if (relayBaseUrl != null && !purchases.isPremium()) throw PremiumRequiredException()
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
                nickname = normalizeNickname(nickname),
            )
        )
    }

    override fun addFromQr(pseudonym: String, verifyKey: ByteArray, encKey: ByteArray, cipherSuite: CipherSuite, verificationLevel: VerificationLevel, relayBaseUrl: String?, nickname: String?) {
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
                nickname = normalizeNickname(nickname),
            )
        )
    }

    override fun updateContact(contactId: UUID, verifyKey: ByteArray?, encKey: ByteArray?, cipherSuite: CipherSuite?, verificationLevel: VerificationLevel?) {
        val existing = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        // A cipher-suite-only change (no key-value change) forces the same fresh-level
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
                revokedVerifyKeys = existing.revokedVerifyKeys,
                keyChangedAt = if (changingIdentity) Instant.now() else existing.keyChangedAt,
            )
        )
    }

    // Deliberately separate from updateContact: never touches keys, cipherSuite,
    // verificationLevel, verifiedAt, or keyChangedAt. Pass null to clear an existing nickname.
    override fun renameContact(contactId: UUID, nickname: String?) {
        val existing = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        contactRepository.save(existing.copy(nickname = normalizeNickname(nickname)))
    }

    override fun deleteContact(contactId: UUID) = contactRepository.delete(contactId)

    // Idempotent: a no-op if the key is already in revokedVerifyKeys.
    override fun markKeyCompromised(contactId: UUID, verifyKey: ByteArray?) {
        val existing = contactRepository.getById(contactId) ?: error("Contact not found for id $contactId")
        val keyToFlag = verifyKey ?: existing.verifyKey
        if (existing.revokedVerifyKeys.any { it.contentEquals(keyToFlag) }) return
        contactRepository.save(existing.copy(revokedVerifyKeys = existing.revokedVerifyKeys + keyToFlag))
    }

    // Trim, then collapse blank to absent. Lives here (not the UI layer) so every
    // caller — UI, tests, a future relink flow — gets consistent normalization for free.
    private fun normalizeNickname(nickname: String?): String? = nickname?.trim()?.ifBlank { null }
}
