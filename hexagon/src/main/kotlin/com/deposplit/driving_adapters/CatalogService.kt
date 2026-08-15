package com.deposplit.driving_adapters

import com.deposplit.driven_ports.ContactRepository
import com.deposplit.driven_ports.SecretRepository
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.driving_ports.CatalogManagement
import com.deposplit.value_objects.Catalog

class CatalogService(
    private val contactRepository: ContactRepository,
    private val secretRepository: SecretRepository,
    private val shareMetadataRepository: ShareMetadataRepository,
) : CatalogManagement {

    override fun exportCatalog(): Catalog = Catalog(
        contacts = contactRepository.getAll(),
        secrets = secretRepository.getAll(),
        shareMetadata = shareMetadataRepository.getAll(),
    )

    override fun importCatalog(catalog: Catalog): Int {
        val existingContactIds = contactRepository.getAll().map { it.id }.toSet()
        var added = 0
        for (contact in catalog.contacts) {
            if (contact.id in existingContactIds) continue
            contactRepository.save(contact)
            added++
        }

        val existingSecretIds = secretRepository.getAll().map { it.id }.toSet()
        for (secret in catalog.secrets) {
            if (secret.id in existingSecretIds) continue
            runCatching { secretRepository.save(secret) }
        }

        val existingMetaIds = shareMetadataRepository.getAll().map { it.id }.toSet()
        for (meta in catalog.shareMetadata) {
            if (meta.id in existingMetaIds) continue
            runCatching { shareMetadataRepository.save(meta) }
        }

        return added
    }
}
