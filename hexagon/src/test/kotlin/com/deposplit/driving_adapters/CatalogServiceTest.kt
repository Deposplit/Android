package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.SecretRepository
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.value_objects.Catalog
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.SecretState
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.VerificationLevel
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

private class InMemoryContactRepositoryForCatalogTest : ContactRepository {
    private val contacts = mutableListOf<Contact>()
    override fun getAll() = contacts.toList()
    override fun getByEdKey(edPublicKey: ByteArray) = contacts.find { it.verifyKey.contentEquals(edPublicKey) }
    override fun getById(id: UUID) = contacts.find { it.id == id }
    override fun save(contact: Contact) {
        contacts.removeAll { it.id == contact.id }
        contacts.add(contact)
    }
    override fun delete(contactId: UUID) { contacts.removeAll { it.id == contactId } }
}

private class InMemorySecretRepositoryForCatalogTest : SecretRepository {
    private val secrets = mutableListOf<Secret>()
    override fun getAll() = secrets.toList()
    override fun save(secret: Secret) {
        secrets.removeAll { it.id == secret.id }
        secrets.add(secret)
    }
    override fun delete(secretId: UUID) { secrets.removeAll { it.id == secretId } }
}

private class InMemoryShareMetadataRepositoryForCatalogTest : ShareMetadataRepository {
    private val metas = mutableListOf<ShareMetadata>()
    override fun getAll() = metas.toList()
    override fun save(share: ShareMetadata) {
        metas.removeAll { it.id == share.id }
        metas.add(share)
    }
    override fun delete(shareId: UUID) { metas.removeAll { it.id == shareId } }
}

private fun makeContact(name: String) = Contact(
    id = UUID.randomUUID(),
    pseudonym = name,
    verifyKey = ByteArray(32) { 0x01 },
    encKey = ByteArray(32) { 0x02 },
    verificationLevel = VerificationLevel.VERY_HIGH,
    verifiedAt = Instant.now(),
    addedAt = Instant.now(),
)

class CatalogServiceTest {

    @Test
    fun `exportCatalog then importCatalog round-trips contacts secrets and shareMetadata`() {
        val contactRepo = InMemoryContactRepositoryForCatalogTest()
        val secretRepo = InMemorySecretRepositoryForCatalogTest()
        val metaRepo = InMemoryShareMetadataRepositoryForCatalogTest()
        val exporter = CatalogService(contactRepo, secretRepo, metaRepo)

        val contact = makeContact("alice")
        contactRepo.save(contact)
        val secret = Secret(UUID.randomUUID(), "test", 2, 3, Instant.now(), SecretState.ACTIVE)
        secretRepo.save(secret)
        val meta = ShareMetadata(UUID.randomUUID(), secret.id, contact.id)
        metaRepo.save(meta)

        val catalog = exporter.exportCatalog()

        val freshContactRepo = InMemoryContactRepositoryForCatalogTest()
        val freshSecretRepo = InMemorySecretRepositoryForCatalogTest()
        val freshMetaRepo = InMemoryShareMetadataRepositoryForCatalogTest()
        val importer = CatalogService(freshContactRepo, freshSecretRepo, freshMetaRepo)

        val added = importer.importCatalog(catalog)

        assertEquals(1, added)
        assertEquals(listOf(contact.id), freshContactRepo.getAll().map { it.id })
        assertEquals(listOf(secret.id), freshSecretRepo.getAll().map { it.id })
        assertEquals(listOf(meta.id), freshMetaRepo.getAll().map { it.id })
    }

    @Test
    fun `importCatalog does not overwrite an existing local contact`() {
        val contactRepo = InMemoryContactRepositoryForCatalogTest()
        val secretRepo = InMemorySecretRepositoryForCatalogTest()
        val metaRepo = InMemoryShareMetadataRepositoryForCatalogTest()
        val svc = CatalogService(contactRepo, secretRepo, metaRepo)

        val localContact = makeContact("locally-edited-name")
        contactRepo.save(localContact)
        val staleImportedVersion = localContact.copy(pseudonym = "stale-backup-name", verificationLevel = VerificationLevel.VERY_LOW)
        val catalog = Catalog(contacts = listOf(staleImportedVersion), secrets = emptyList(), shareMetadata = emptyList())

        val added = svc.importCatalog(catalog)

        assertEquals(0, added)
        assertEquals("locally-edited-name", contactRepo.getById(localContact.id)?.pseudonym)
    }
}
