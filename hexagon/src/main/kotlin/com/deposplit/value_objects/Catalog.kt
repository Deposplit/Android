package com.deposplit.value_objects

// A self-managed export of the *non-secret* catalog — contact public keys, pseudonyms,
// verification levels, and sender-side ShareMetadata/Secret records. Eases "who are my holders"
// during identity recovery (item 8) without weakening anything: none of this is a share or a
// private key. See deposplit.com/CLAUDE.md "What is next" item 8, "Optional catalog backup".
//
// Deliberately plain data, not a serialized format — the hexagon has no JSON dependency
// (Ports & Adapters: serialization is an adapter concern). The app layer's Settings screen
// encodes/decodes this to/from JSON via kotlinx.serialization, already used by the app's
// Local*Repository adapters.
data class Catalog(
    val contacts: List<Contact>,
    val secrets: List<Secret>,
    val shareMetadata: List<ShareMetadata>,
)
