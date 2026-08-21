package com.deposplit.value_objects

/**
 * A ciphertext-only, 1-byte transport tag — deliberately lighter-weight than [CipherSuite] and not
 * JSON-facing, since it never rides the wire except as the leading byte of a ciphertext blob. See
 * deposplit.com/CLAUDE.md item 14: the ciphertext wire format becomes
 * `suiteTag(1) || nonce(12) || ciphertext+tag`. Needs no persistent state or trust mechanism — item
 * 7 already re-derives each deposit/retrieval leg fresh, so a device just always encrypts with its
 * current preferred suite and a decrypting device dispatches on the tag it reads.
 */
enum class TransportSuite(val tag: Byte) {
    X25519_HKDF_SHA256_CHACHA20POLY1305(1);

    companion object {
        fun fromTag(tag: Byte): TransportSuite? = entries.find { it.tag == tag }

        /** The only construction this codebase's encrypt/decrypt can produce today. */
        val current: TransportSuite = X25519_HKDF_SHA256_CHACHA20POLY1305
    }
}
