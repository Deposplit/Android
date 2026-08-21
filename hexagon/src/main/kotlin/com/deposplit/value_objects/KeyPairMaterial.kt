package com.deposplit.value_objects

// A freshly generated keypair, not yet persisted as this device's identity — see item 9's
// "regenerate my own identity" trigger. Kept separate from IdentityStore.save's parameters so a
// caller can push a signed rotation notice (proving continuity from the *old* key) before
// activating the new one. Field names follow item 14's rename (verifyKey/signKey/encKey/decKey) —
// the same vocabulary whether the keys are mine or a contact's.
data class KeyPairMaterial(
    val verifyKey: ByteArray,
    val signKey: ByteArray,
    val encKey: ByteArray,
    val decKey: ByteArray,
) {
    override fun equals(other: Any?) = other is KeyPairMaterial &&
        verifyKey.contentEquals(other.verifyKey) &&
        signKey.contentEquals(other.signKey) &&
        encKey.contentEquals(other.encKey) &&
        decKey.contentEquals(other.decKey)

    override fun hashCode(): Int {
        var result = verifyKey.contentHashCode()
        result = 31 * result + signKey.contentHashCode()
        result = 31 * result + encKey.contentHashCode()
        result = 31 * result + decKey.contentHashCode()
        return result
    }
}
