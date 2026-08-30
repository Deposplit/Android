package com.deposplit.driving_ports

import com.deposplit.value_objects.Catalog

// Optional catalog export/import — a convenience backup of the *non-secret* catalog,
// never shares or private keys.
interface CatalogManagement {
    fun exportCatalog(): Catalog

    // Merges contacts/secrets/shareMetadata from catalog into local storage — upsert-if-absent
    // only, by id; an existing local record is never overwritten by an imported one, since a
    // stale backup could otherwise clobber more-current local state. Returns the number of newly
    // added contacts.
    fun importCatalog(catalog: Catalog): Int
}
