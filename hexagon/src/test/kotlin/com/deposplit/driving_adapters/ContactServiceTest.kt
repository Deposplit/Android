package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
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
    override fun getByEdKey(edPublicKey: ByteArray) = contacts.find { it.edPublicKey.contentEquals(edPublicKey) }
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
    edPublicKey = ByteArray(32) { 0x01 },
    xPublicKey = ByteArray(32) { 0x02 },
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

        svc.updateContact(original.id, edPublicKey = newEd, xPublicKey = newX, verificationLevel = VerificationLevel.LOW)

        val updated = repo.getById(original.id)!!
        assertEquals(original.id, updated.id)
        assertEquals(original.pseudonym, updated.pseudonym)
        assertTrue(updated.edPublicKey.contentEquals(newEd))
        assertTrue(updated.xPublicKey.contentEquals(newX))
        assertEquals(VerificationLevel.LOW, updated.verificationLevel)
    }

    @Test
    fun `updateContact throws when changing keys without supplying a level`() {
        val repo = InMemoryContactRepositoryForContactServiceTest()
        val svc = ContactService(repo)
        val original = makeContact()
        repo.save(original)

        assertFailsWith<IllegalArgumentException> {
            svc.updateContact(original.id, edPublicKey = ByteArray(32) { 0x03 })
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
        assertTrue(updated.edPublicKey.contentEquals(original.edPublicKey))
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
}
