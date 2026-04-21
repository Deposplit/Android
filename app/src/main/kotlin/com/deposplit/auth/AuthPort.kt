package com.deposplit.auth

interface AuthPort {
    fun isRegistered(): Boolean
    fun register(pseudonym: String)
    fun pseudonym(): String
    fun edPublicKey(): ByteArray
    fun xPublicKey(): ByteArray
    fun sign(message: ByteArray): ByteArray
    /** Encrypts [plaintext] to [recipientXPublicKey] using crypto_box_easy. Returns nonce || ciphertext. */
    fun encrypt(plaintext: ByteArray, recipientXPublicKey: ByteArray): ByteArray
}
