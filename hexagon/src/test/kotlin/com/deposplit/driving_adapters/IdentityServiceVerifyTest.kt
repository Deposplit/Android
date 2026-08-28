package com.deposplit.driving_adapters

import com.deposplit.driven_ports.IdentityStore
import com.deposplit.value_objects.TransportSuite
import com.deposplit.value_objects.UnsupportedTransportSuiteException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** In-memory IdentityStore — no Keystore/file I/O needed for these tests. */
private class InMemoryIdentityStore : IdentityStore {
    private var registered = false
    private var _pseudonym = ""
    private var edPk = ByteArray(0)
    private var edSk = ByteArray(0)
    private var xPk = ByteArray(0)
    private var xSk = ByteArray(0)

    override fun isRegistered() = registered

    override fun save(pseudonym: String, edPk: ByteArray, edSk: ByteArray, xPk: ByteArray, xSk: ByteArray) {
        this._pseudonym = pseudonym
        this.edPk = edPk
        this.edSk = edSk
        this.xPk = xPk
        this.xSk = xSk
        registered = true
    }

    override fun pseudonym() = _pseudonym
    override fun verifyKey() = edPk
    override fun signKey() = edSk
    override fun encKey() = xPk
    override fun decKey() = xSk
}

/**
 * Mirrors deposplit.com's PublicKeyTests valid/tampered/wrong-key trio, for
 * [IdentityService.verify] — the recipient-side counterpart of the server's `PublicKey.verify`,
 * used to independently re-verify senderSignature/recipientSignature (see
 * [com.deposplit.value_objects.PayloadCanonical]).
 */
class IdentityServiceVerifyTest {

    private fun newIdentity(): IdentityService {
        val svc = IdentityService(InMemoryIdentityStore())
        svc.register("test")
        return svc
    }

    @Test
    fun `verify returns true for a valid signature`() {
        val alice = newIdentity()
        val message = "hello deposplit".encodeToByteArray()
        val sig = alice.sign(message)
        assertTrue(alice.verify(message, sig, alice.verifyKey()))
    }

    @Test
    fun `verify returns false for a tampered message`() {
        val alice = newIdentity()
        val message = "hello deposplit".encodeToByteArray()
        val sig = alice.sign(message)
        val tampered = "hello depospliz".encodeToByteArray()
        assertFalse(alice.verify(tampered, sig, alice.verifyKey()))
    }

    @Test
    fun `verify returns false when checked against a different key`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val message = "hello deposplit".encodeToByteArray()
        val sig = alice.sign(message)
        assertFalse(bob.verify(message, sig, bob.verifyKey()))
    }

    // -------------------------------------------------------------------------
    // generateNewKeyPair() / activateKeyPair() — item 9's identity-regen trigger
    // -------------------------------------------------------------------------

    @Test
    fun `generateNewKeyPair does not touch storage`() {
        val alice = newIdentity()
        val originalVerifyKey = alice.verifyKey()
        val originalEncKey = alice.encKey()
        val candidate = alice.generateNewKeyPair()
        assertTrue(!candidate.verifyKey.contentEquals(originalVerifyKey))
        assertTrue(!candidate.encKey.contentEquals(originalEncKey))
        // Unpersisted — the live identity hasn't moved.
        assertTrue(alice.verifyKey().contentEquals(originalVerifyKey))
        assertTrue(alice.encKey().contentEquals(originalEncKey))
    }

    @Test
    fun `activateKeyPair persists the new keys and preserves the pseudonym`() {
        val alice = newIdentity()
        val candidate = alice.generateNewKeyPair()
        alice.activateKeyPair(candidate)
        assertTrue(alice.verifyKey().contentEquals(candidate.verifyKey))
        assertTrue(alice.encKey().contentEquals(candidate.encKey))
        assertTrue(alice.pseudonym() == "test")
    }

    @Test
    fun `sign after activateKeyPair verifies against the new key not the old`() {
        val alice = newIdentity()
        val oldVerifyKey = alice.verifyKey()
        val candidate = alice.generateNewKeyPair()
        alice.activateKeyPair(candidate)
        val message = "post-rotation message".encodeToByteArray()
        val sig = alice.sign(message)
        assertTrue(alice.verify(message, sig, candidate.verifyKey))
        assertFalse(alice.verify(message, sig, oldVerifyKey))
    }

    // -------------------------------------------------------------------------
    // encrypt()/decrypt() suite-tag wire format — item 14
    // -------------------------------------------------------------------------

    @Test
    fun `encrypt prepends the current TransportSuite tag and decrypt round-trips`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val plaintext = "a share of a secret".encodeToByteArray()

        // Alice (sender) encrypts to Bob's public enc key; Bob (recipient) decrypts with his own
        // private key + Alice's public enc key — the same static-static DH shape ShareService uses.
        val ciphertext = alice.encrypt(plaintext, bob.encKey())

        assertEquals(TransportSuite.current.tag, ciphertext[0])
        val decrypted = bob.decrypt(ciphertext, alice.encKey())
        assertTrue(decrypted.contentEquals(plaintext))
    }

    @Test
    fun `decrypt throws UnsupportedTransportSuiteException for an unrecognized suite tag`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val ciphertext = alice.encrypt("hi".encodeToByteArray(), bob.encKey())
        val tampered = byteArrayOf(0x7f) + ciphertext.copyOfRange(1, ciphertext.size)

        assertFailsWith<UnsupportedTransportSuiteException> {
            bob.decrypt(tampered, alice.encKey())
        }
    }

    @Test
    fun `decrypt throws UnsupportedTransportSuiteException for empty ciphertext`() {
        val alice = newIdentity()
        val bob = newIdentity()

        assertFailsWith<UnsupportedTransportSuiteException> {
            bob.decrypt(ByteArray(0), alice.encKey())
        }
    }
}
