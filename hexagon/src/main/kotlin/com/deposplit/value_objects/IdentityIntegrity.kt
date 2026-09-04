package com.deposplit.value_objects

/**
 * Whether this device can still act as the identity it believes it has.
 *
 * The question exists because local files and private keys do not travel together. App storage
 * migrates to a new phone; keys held by the platform keystore do not, and public key material
 * kept beside them may. A restored device can therefore believe it is registered, advertise the
 * right public keys, and be unable to sign or decrypt as them — which surfaces today as unrelated
 * errors on two phones rather than as one explicable state.
 *
 * [UNREADABLE] is not padding. Key storage that is merely locked must never be mistaken for key
 * storage that is empty: [KEYS_LOST] is what offers to mint a new identity, and doing that over an
 * identity that was only temporarily unreadable would destroy a working one.
 */
enum class IdentityIntegrity {
    /** The private keys are present and match the advertised public keys. Nothing to say. */
    INTACT,

    /**
     * This device is registered, but the private keys are gone or no longer match. Everything else
     * — contacts, secrets, share metadata, the shares held for other people — is untouched.
     */
    KEYS_LOST,

    /** Key storage cannot be read at this moment. Say nothing, change nothing, ask again later. */
    UNREADABLE,
}
