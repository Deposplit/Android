package com.deposplit.auth

interface AuthPort {
    fun isRegistered(): Boolean
    fun register(pseudonym: String)
    fun pseudonym(): String
    fun edPublicKey(): ByteArray
    fun xPublicKey(): ByteArray
    fun sign(message: ByteArray): ByteArray
    /** Encrypts [plaintext] to [recipientXPublicKey] via X25519+HKDF-SHA-256+ChaCha20-Poly1305. Returns nonce(12) || ciphertext+tag. */
    fun encrypt(plaintext: ByteArray, recipientXPublicKey: ByteArray): ByteArray

    /** Decrypts [noncePlusCiphertext] (nonce(12) || ciphertext+tag) using [recipientXPublicKey] via X25519+HKDF-SHA-256+ChaCha20-Poly1305. */
    fun decrypt(noncePlusCiphertext: ByteArray, recipientXPublicKey: ByteArray): ByteArray
}
