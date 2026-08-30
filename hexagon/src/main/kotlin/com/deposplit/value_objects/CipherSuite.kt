package com.deposplit.value_objects

/**
 * The matched pairing of signing algorithm + key-agreement algorithm an identity currently uses —
 * the crypto-agility mechanism. One case exists today;
 * the point of naming it explicitly is making a future fleet-wide algorithm swap an additive new
 * case rather than a breaking wire-format migration. Bundled as one value (not two independent
 * per-algorithm tags) because both of a device's keypairs are generated together and rotate
 * together — nothing today expresses "signing algorithm A with agreement algorithm B" as a valid
 * combination distinct from this one.
 *
 * [wireValue] follows [ShareTransactionType]'s pattern: a string, not an ordinal, since ordinals
 * aren't safe across independently hand-ported enums on three other platforms.
 * [verifyKeyLength]/[encKeyLength] are what let key-length validation be suite-driven instead of a
 * bare hardcoded constant — see [ContactManagement]'s length checks.
 */
enum class CipherSuite(val wireValue: String, val verifyKeyLength: Int, val encKeyLength: Int) {
    ED25519_X25519_V1("ed25519+x25519-v1", verifyKeyLength = 32, encKeyLength = 32);

    companion object {
        fun fromWire(value: String): CipherSuite? = entries.find { it.wireValue == value }

        /** The only suite this codebase's key generation can produce today. */
        val current: CipherSuite = ED25519_X25519_V1
    }
}
