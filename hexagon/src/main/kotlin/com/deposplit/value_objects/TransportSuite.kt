package com.deposplit.value_objects

/**
 * A box-only, 1-byte transport tag — deliberately lighter-weight than [CipherSuite], which names
 * the key types, and not JSON-facing, since it never rides the wire except as the leading byte of
 * a box: `suiteTag(1) || enc(32) || ciphertext+tag(16)`. Needs no persistent state or trust
 * mechanism — each box is sealed fresh, so a device just always seals with its current preferred
 * suite and an opening device dispatches on the tag it reads.
 */
enum class TransportSuite(val tag: Byte) {
    // HPKE base mode (RFC 9180): DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, with
    // a fresh ephemeral sender key per box. Tag 1 was a static-static box the sender could reopen,
    // and is gone rather than kept readable, so a leftover box from it fails loudly instead of
    // misparsing.
    HPKE_X25519_SHA256_CHACHA20POLY1305(2);

    companion object {
        fun fromTag(tag: Byte): TransportSuite? = entries.find { it.tag == tag }

        /** The only construction this codebase's encrypt/decrypt can produce today. */
        val current: TransportSuite = HPKE_X25519_SHA256_CHACHA20POLY1305
    }
}
