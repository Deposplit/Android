package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRelinkRepository
import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.IdentityStore
import com.deposplit.driven_ports.PurchaseRepository
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.ContactRelink
import com.deposplit.value_objects.PremiumRequiredException
import com.deposplit.value_objects.VerificationLevel
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class InMemoryContactRepositoryForContactServiceTest : ContactRepository {
    private val contacts = mutableListOf<Contact>()
    override fun getAll() = contacts.toList()
    override fun getByVerifyKey(verifyKey: ByteArray) = contacts.find { it.verifyKey.contentEquals(verifyKey) }
    override fun getById(id: UUID) = contacts.find { it.id == id }
    override fun save(contact: Contact) {
        contacts.removeAll { it.id == contact.id }
        contacts.add(contact)
    }
    override fun delete(contactId: UUID) { contacts.removeAll { it.id == contactId } }
}

private class FakePurchaseRepositoryForContactServiceTest(var premium: Boolean = false) : PurchaseRepository {
    override fun isPremium() = premium
}

private class InMemoryContactRelinkRepositoryForContactServiceTest : ContactRelinkRepository {
    private val relinks = mutableListOf<ContactRelink>()
    override fun getAll() = relinks.toList()
    override fun get(contactId: UUID) = relinks.find { it.contactId == contactId }
    override fun save(relink: ContactRelink) {
        relinks.removeAll { it.contactId == relink.contactId }
        relinks.add(relink)
    }
}

/** Only identityCreatedAt() is read by ContactService; the rest of the port is never reached. */
private class FakeIdentityStoreForContactServiceTest(var createdAt: Instant? = null) : IdentityStore {
    override fun isRegistered() = createdAt != null
    override fun save(pseudonym: String, verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray) = Unit
    override fun rotate(verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray) = Unit
    override fun pseudonym() = ""
    override fun identityCreatedAt() = createdAt
    override fun verifyKey(): ByteArray? = null
    override fun signKey() = ByteArray(0)
    override fun encKey(): ByteArray? = null
    override fun decKey() = ByteArray(0)
    override fun previousDecKey(): ByteArray? = null
}

private fun makeContact() = Contact(
    id = UUID.randomUUID(),
    pseudonym = "bob",
    verifyKey = ByteArray(32) { 0x01 },
    encKey = ByteArray(32) { 0x02 },
    verificationLevel = VerificationLevel.VERY_HIGH,
    verifiedAt = Instant.EPOCH,
    addedAt = Instant.EPOCH,
)

// updateContact — contact-update-in-place, preserving contactId, used both for benign key
// rotation and holder-driven recovery relinking.
class ContactServiceTest {

    @Test
    fun `updateContact preserves contactId while changing keys and level`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)
        val newEd = ByteArray(32) { 0x03 }
        val newX = ByteArray(32) { 0x04 }

        svc.updateContact(original.id, verifyKey = newEd, encKey = newX, verificationLevel = VerificationLevel.LOW)

        val updated = repo.getById(original.id)!!
        assertEquals(original.id, updated.id)
        assertEquals(original.pseudonym, updated.pseudonym)
        assertTrue(updated.verifyKey.contentEquals(newEd))
        assertTrue(updated.encKey.contentEquals(newX))
        assertEquals(VerificationLevel.LOW, updated.verificationLevel)
    }

    @Test
    fun `updateContact throws when changing keys without supplying a level`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)

        assertFailsWith<IllegalArgumentException> {
            svc.updateContact(original.id, verifyKey = ByteArray(32) { 0x03 })
        }
    }

    @Test
    fun `updateContact can change only the level without touching keys`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)

        svc.updateContact(original.id, verificationLevel = VerificationLevel.HIGH)

        val updated = repo.getById(original.id)!!
        assertTrue(updated.verifyKey.contentEquals(original.verifyKey))
        assertEquals(VerificationLevel.HIGH, updated.verificationLevel)
    }

    @Test
    fun `updateContact throws for an unknown contactId`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        assertFailsWith<IllegalStateException> {
            svc.updateContact(UUID.randomUUID(), verificationLevel = VerificationLevel.HIGH)
        }
    }

    // ── Stolen-key revocation ─────────────────────────────────────────────────

    @Test
    fun `updateContact sets keyChangedAt only when keys actually change`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)
        assertEquals(null, original.keyChangedAt)

        svc.updateContact(original.id, verificationLevel = VerificationLevel.HIGH)
        assertEquals(null, repo.getById(original.id)!!.keyChangedAt)

        svc.updateContact(original.id, verifyKey = ByteArray(32) { 0x05 }, verificationLevel = VerificationLevel.LOW)
        assertTrue(repo.getById(original.id)!!.keyChangedAt != null)
    }

    @Test
    fun `markKeyCompromised flags the contact's current key by default`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)

        svc.markKeyCompromised(original.id)

        val updated = repo.getById(original.id)!!
        assertEquals(1, updated.revokedVerifyKeys.size)
        assertTrue(updated.revokedVerifyKeys.first().contentEquals(original.verifyKey))
    }

    @Test
    fun `markKeyCompromised is idempotent for an already-flagged key`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original.copy(revokedVerifyKeys = listOf(original.verifyKey)))

        svc.markKeyCompromised(original.id)

        assertEquals(1, repo.getById(original.id)!!.revokedVerifyKeys.size)
    }

    @Test
    fun `markKeyCompromised can flag an explicit key other than the current one`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)
        val oldKey = ByteArray(32) { 0x07 }

        svc.markKeyCompromised(original.id, verifyKey = oldKey)

        val updated = repo.getById(original.id)!!
        assertEquals(1, updated.revokedVerifyKeys.size)
        assertTrue(updated.revokedVerifyKeys.first().contentEquals(oldKey))
        assertTrue(!updated.revokedVerifyKeys.first().contentEquals(original.verifyKey))
    }

    // ── Crypto agility: cipher suite threading + suite-aware length validation ──────────────────

    @Test
    fun `addFromQr stores the asserted cipherSuite`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        svc.addFromQr("bob", ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }, CipherSuite.current, VerificationLevel.VERY_HIGH)

        assertEquals(CipherSuite.current, repo.getAll().single().cipherSuite)
    }

    @Test
    fun `addManually defaults to the current cipherSuite`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        svc.addManually("bob", ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }, VerificationLevel.LOW)

        assertEquals(CipherSuite.current, repo.getAll().single().cipherSuite)
    }

    @Test
    fun `addFromQr rejects a verify key whose length does not match the asserted cipherSuite`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        assertFailsWith<IllegalArgumentException> {
            svc.addFromQr("bob", ByteArray(16) { 0x01 }, ByteArray(32) { 0x02 }, CipherSuite.current, VerificationLevel.VERY_HIGH)
        }
    }

    @Test
    fun `updateContact rejects a new key whose length does not match the effective cipherSuite`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)

        assertFailsWith<IllegalArgumentException> {
            svc.updateContact(original.id, verifyKey = ByteArray(16) { 0x01 }, verificationLevel = VerificationLevel.LOW)
        }
    }

    @Test
    fun `updateContact forces a fresh verification level on a cipherSuite-only change`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)

        assertFailsWith<IllegalArgumentException> {
            svc.updateContact(original.id, cipherSuite = CipherSuite.current)
        }

        svc.updateContact(original.id, cipherSuite = CipherSuite.current, verificationLevel = VerificationLevel.LOW)
        val updated = repo.getById(original.id)!!
        assertEquals(CipherSuite.current, updated.cipherSuite)
        assertEquals(VerificationLevel.LOW, updated.verificationLevel)
        assertTrue(updated.keyChangedAt != null)
    }

    // ── Local contact nicknames ───────────────────────────────────────────────

    @Test
    fun `renameContact sets a nickname without touching keys, level, or keyChangedAt`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)

        svc.renameContact(original.id, "Coworker Paul")

        val updated = repo.getById(original.id)!!
        assertEquals("Coworker Paul", updated.nickname)
        assertEquals(original.pseudonym, updated.pseudonym)
        assertTrue(updated.verifyKey.contentEquals(original.verifyKey))
        assertTrue(updated.encKey.contentEquals(original.encKey))
        assertEquals(original.verificationLevel, updated.verificationLevel)
        assertEquals(original.verifiedAt, updated.verifiedAt)
        assertEquals(null, updated.keyChangedAt)
    }

    @Test
    fun `renameContact trims and collapses a blank nickname to null`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact()
        repo.save(original)

        svc.renameContact(original.id, "  Paul  ")
        assertEquals("Paul", repo.getById(original.id)!!.nickname)

        svc.renameContact(original.id, "   ")
        assertEquals(null, repo.getById(original.id)!!.nickname)
    }

    @Test
    fun `renameContact can clear an existing nickname`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())
        val original = makeContact().copy(nickname = "Paul")
        repo.save(original)

        svc.renameContact(original.id, null)

        assertEquals(null, repo.getById(original.id)!!.nickname)
    }

    @Test
    fun `renameContact throws for an unknown contactId`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        assertFailsWith<IllegalStateException> {
            svc.renameContact(UUID.randomUUID(), "Paul")
        }
    }

    @Test
    fun `addManually and addFromQr trim and normalize the nickname`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        svc.addManually("bob", ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }, VerificationLevel.LOW, nickname = "  Bobby  ")
        svc.addFromQr("carol", ByteArray(32) { 0x03 }, ByteArray(32) { 0x04 }, CipherSuite.current, VerificationLevel.VERY_HIGH, nickname = "   ")

        val contacts = repo.getAll()
        assertEquals("Bobby", contacts.single { it.pseudonym == "bob" }.nickname)
        assertEquals(null, contacts.single { it.pseudonym == "carol" }.nickname)
    }

    @Test
    fun `addManually and addFromQr default the nickname to null when omitted`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        svc.addManually("bob", ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }, VerificationLevel.LOW)

        assertEquals(null, repo.getAll().single().nickname)
    }

    // ── Free tier ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `addManually refuses a relay override without Premium`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        assertFailsWith<PremiumRequiredException> {
            svc.addManually("bob", ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }, VerificationLevel.LOW, relayBaseUrl = "https://relay.example")
        }

        assertTrue(repo.getAll().isEmpty())
    }

    @Test
    fun `addManually accepts a relay override with Premium`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(premium = true), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        svc.addManually("bob", ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }, VerificationLevel.LOW, relayBaseUrl = "https://relay.example")

        assertEquals("https://relay.example", repo.getAll().single().relayBaseUrl)
    }

    /**
     * The free half of BYOR. A relay named in a scanned QR code is the contact saying where their
     * own mailbox is, so refusing it would mean a free device cannot share with a self-hoster at
     * all — a different product, not a paywall.
     */
    @Test
    fun `addFromQr accepts a relay override without Premium`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo, FakePurchaseRepositoryForContactServiceTest(), FakeIdentityStoreForContactServiceTest(), InMemoryContactRelinkRepositoryForContactServiceTest())

        svc.addFromQr("bob", ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }, CipherSuite.current, VerificationLevel.VERY_HIGH, relayBaseUrl = "https://relay.example")

        assertEquals("https://relay.example", repo.getAll().single().relayBaseUrl)
    }

    // -------------------------------------------------------------------------
    // contactsAwaitingRelink() — who still holds a key this device no longer signs with
    // -------------------------------------------------------------------------

    private fun awaitingSetup(identityCreatedAt: Instant?): Triple<ContactService, InMemoryContactRepositoryForContactServiceTest, InMemoryContactRelinkRepositoryForContactServiceTest> {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val relinks = InMemoryContactRelinkRepositoryForContactServiceTest()
        val svc = ContactService(
            repo,
            FakePurchaseRepositoryForContactServiceTest(),
            FakeIdentityStoreForContactServiceTest(identityCreatedAt),
            relinks,
        )
        return Triple(svc, repo, relinks)
    }

    private val identityBorn: Instant = Instant.parse("2026-06-01T00:00:00Z")

    @Test
    fun `a contact added before the current identity is awaiting relink`() {
        val (svc, repo, _) = awaitingSetup(identityBorn)
        val older = makeContact().copy(id = UUID.randomUUID(), addedAt = identityBorn.minusSeconds(60))
        repo.save(older)
        assertEquals(listOf(older.id), svc.contactsAwaitingRelink().map { it.id })
    }

    @Test
    fun `a contact added after the current identity is not`() {
        val (svc, repo, _) = awaitingSetup(identityBorn)
        repo.save(makeContact().copy(id = UUID.randomUUID(), addedAt = identityBorn.plusSeconds(60)))
        assertTrue(svc.contactsAwaitingRelink().isEmpty())
    }

    // Anything arriving from a contact is proof, since the relay only returns rows addressed to
    // the caller's current key.
    @Test
    fun `a relink recorded since the current identity clears the contact`() {
        val (svc, repo, _) = awaitingSetup(identityBorn)
        val older = makeContact().copy(id = UUID.randomUUID(), addedAt = identityBorn.minusSeconds(60))
        repo.save(older)
        svc.markRelinked(older.id)
        assertTrue(svc.contactsAwaitingRelink().isEmpty())
    }

    // A relink from before this identity existed was to the key that is gone, so it proves nothing.
    @Test
    fun `a relink older than the current identity does not clear the contact`() {
        val (svc, repo, relinks) = awaitingSetup(identityBorn)
        val older = makeContact().copy(id = UUID.randomUUID(), addedAt = identityBorn.minusSeconds(120))
        repo.save(older)
        relinks.save(ContactRelink(older.id, identityBorn.minusSeconds(60)))
        assertEquals(listOf(older.id), svc.contactsAwaitingRelink().map { it.id })
    }

    // No recorded start is no basis to judge — flagging every contact on a guess would be a false
    // alarm on a device that never lost anything.
    @Test
    fun `an unrecorded identity start puts nobody on the list`() {
        val (svc, repo, _) = awaitingSetup(null)
        repo.save(makeContact().copy(id = UUID.randomUUID(), addedAt = Instant.EPOCH))
        assertTrue(svc.contactsAwaitingRelink().isEmpty())
    }

    @Test
    fun `markRelinked is idempotent`() {
        val (svc, repo, relinks) = awaitingSetup(identityBorn)
        val older = makeContact().copy(id = UUID.randomUUID(), addedAt = identityBorn.minusSeconds(60))
        repo.save(older)
        svc.markRelinked(older.id)
        svc.markRelinked(older.id)
        assertEquals(1, relinks.getAll().size)
    }
}
