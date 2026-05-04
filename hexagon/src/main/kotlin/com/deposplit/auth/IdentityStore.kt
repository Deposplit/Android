package com.deposplit.auth

interface IdentityStore {
    fun isRegistered(): Boolean
    fun save(pseudonym: String, edPk: ByteArray, edSk: ByteArray, xPk: ByteArray, xSk: ByteArray)
    fun pseudonym(): String
    fun edPublicKey(): ByteArray
    fun edPrivateKey(): ByteArray
    fun xPublicKey(): ByteArray
    fun xPrivateKey(): ByteArray
}
