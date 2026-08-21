package com.deposplit.value_objects

// A freshly generated Ed25519 + X25519 keypair, not yet persisted as this device's identity — see
// item 9's "regenerate my own identity" trigger. Kept separate from IdentityStore.save's
// parameters so a caller can push a signed rotation notice (proving continuity from the *old*
// key) before activating the new one.
data class KeyPairMaterial(
    val edPublicKey: ByteArray,
    val edPrivateKey: ByteArray,
    val xPublicKey: ByteArray,
    val xPrivateKey: ByteArray,
) {
    override fun equals(other: Any?) = other is KeyPairMaterial &&
        edPublicKey.contentEquals(other.edPublicKey) &&
        edPrivateKey.contentEquals(other.edPrivateKey) &&
        xPublicKey.contentEquals(other.xPublicKey) &&
        xPrivateKey.contentEquals(other.xPrivateKey)

    override fun hashCode(): Int {
        var result = edPublicKey.contentHashCode()
        result = 31 * result + edPrivateKey.contentHashCode()
        result = 31 * result + xPublicKey.contentHashCode()
        result = 31 * result + xPrivateKey.contentHashCode()
        return result
    }
}
