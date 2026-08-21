package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
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
    override fun getByEdKey(verifyKey: ByteArray) = contacts.find { it.verifyKey.contentEquals(verifyKey) }
    override fun getById(id: UUID) = contacts.find { it.id == id }
    override fun save(contact: Contact) {
        contacts.removeAll { it.id == contact.id }
        contacts.add(contact)
    }
    override fun delete(contactId: UUID) { contacts.removeAll { it.id == contactId } }
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

// updateContact (item 8) — contact-update-in-place, preserving contactId, used both for benign
// key rotation and holder-driven recovery relinking. See deposplit.com/CLAUDE.md item 8.
class ContactServiceTest {

    @Test
    fun `updateContact preserves contactId while changing keys and level`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)
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
        val svc = ContactService(repo)
        val original = makeContact()
        repo.save(original)

        assertFailsWith<IllegalArgumentException> {
            svc.updateContact(original.id, verifyKey = ByteArray(32) { 0x03 })
        }
    }

    @Test
    fun `updateContact can change only the level without touching keys`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)
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
        val svc = ContactService(repo)

        assertFailsWith<IllegalStateException> {
            svc.updateContact(UUID.randomUUID(), verificationLevel = VerificationLevel.HIGH)
        }
    }

    // ── Item 10: stolen-key revocation ────────────────────────────────────────

    @Test
    fun `updateContact sets keyChangedAt only when keys actually change`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)
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
        val svc = ContactService(repo)
        val original = makeContact()
        repo.save(original)

        svc.markKeyCompromised(original.id)

        val updated = repo.getById(original.id)!!
        assertEquals(1, updated.revokedEdKeys.size)
        assertTrue(updated.revokedEdKeys.first().contentEquals(original.verifyKey))
    }

    @Test
    fun `markKeyCompromised is idempotent for an already-flagged key`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)
        val original = makeContact()
        repo.save(original.copy(revokedEdKeys = listOf(original.verifyKey)))

        svc.markKeyCompromised(original.id)

        assertEquals(1, repo.getById(original.id)!!.revokedEdKeys.size)
    }

    @Test
    fun `markKeyCompromised can flag an explicit key other than the current one`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)
        val original = makeContact()
        repo.save(original)
        val oldKey = ByteArray(32) { 0x07 }

        svc.markKeyCompromised(original.id, verifyKey = oldKey)

        val updated = repo.getById(original.id)!!
        assertEquals(1, updated.revokedEdKeys.size)
        assertTrue(updated.revokedEdKeys.first().contentEquals(oldKey))
        assertTrue(!updated.revokedEdKeys.first().contentEquals(original.verifyKey))
    }

    // ── Item 14: crypto agility — cipher suite threading + suite-aware length validation ────────

    @Test
    fun `addFromQr stores the asserted cipherSuite`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)

        svc.addFromQr("bob", ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }, CipherSuite.current, VerificationLevel.VERY_HIGH)

        assertEquals(CipherSuite.current, repo.getAll().single().cipherSuite)
    }

    @Test
    fun `addManually defaults to the current cipherSuite`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)

        svc.addManually("bob", ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 }, VerificationLevel.LOW)

        assertEquals(CipherSuite.current, repo.getAll().single().cipherSuite)
    }

    @Test
    fun `addFromQr rejects a verify key whose length does not match the asserted cipherSuite`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)

        assertFailsWith<IllegalArgumentException> {
            svc.addFromQr("bob", ByteArray(16) { 0x01 }, ByteArray(32) { 0x02 }, CipherSuite.current, VerificationLevel.VERY_HIGH)
        }
    }

    @Test
    fun `updateContact rejects a new key whose length does not match the effective cipherSuite`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)
        val original = makeContact()
        repo.save(original)

        assertFailsWith<IllegalArgumentException> {
            svc.updateContact(original.id, verifyKey = ByteArray(16) { 0x01 }, verificationLevel = VerificationLevel.LOW)
        }
    }

    @Test
    fun `updateContact forces a fresh verification level on a cipherSuite-only change`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)
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
}
