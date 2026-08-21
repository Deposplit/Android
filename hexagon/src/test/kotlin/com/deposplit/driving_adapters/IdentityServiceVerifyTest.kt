package com.deposplit.driving_adapters

import com.deposplit.driven_ports.IdentityStore
import kotlin.test.Test
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
    override fun edPublicKey() = edPk
    override fun edPrivateKey() = edSk
    override fun xPublicKey() = xPk
    override fun xPrivateKey() = xSk
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
        assertTrue(alice.verify(message, sig, alice.edPublicKey()))
    }

    @Test
    fun `verify returns false for a tampered message`() {
        val alice = newIdentity()
        val message = "hello deposplit".encodeToByteArray()
        val sig = alice.sign(message)
        val tampered = "hello depospliz".encodeToByteArray()
        assertFalse(alice.verify(tampered, sig, alice.edPublicKey()))
    }

    @Test
    fun `verify returns false when checked against a different key`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val message = "hello deposplit".encodeToByteArray()
        val sig = alice.sign(message)
        assertFalse(bob.verify(message, sig, bob.edPublicKey()))
    }

    // -------------------------------------------------------------------------
    // generateNewKeyPair() / activateKeyPair() — item 9's identity-regen trigger
    // -------------------------------------------------------------------------

    @Test
    fun `generateNewKeyPair does not touch storage`() {
        val alice = newIdentity()
        val originalEdKey = alice.edPublicKey()
        val originalXKey = alice.xPublicKey()
        val candidate = alice.generateNewKeyPair()
        assertTrue(!candidate.edPublicKey.contentEquals(originalEdKey))
        assertTrue(!candidate.xPublicKey.contentEquals(originalXKey))
        // Unpersisted — the live identity hasn't moved.
        assertTrue(alice.edPublicKey().contentEquals(originalEdKey))
        assertTrue(alice.xPublicKey().contentEquals(originalXKey))
    }

    @Test
    fun `activateKeyPair persists the new keys and preserves the pseudonym`() {
        val alice = newIdentity()
        val candidate = alice.generateNewKeyPair()
        alice.activateKeyPair(candidate)
        assertTrue(alice.edPublicKey().contentEquals(candidate.edPublicKey))
        assertTrue(alice.xPublicKey().contentEquals(candidate.xPublicKey))
        assertTrue(alice.pseudonym() == "test")
    }

    @Test
    fun `sign after activateKeyPair verifies against the new key not the old`() {
        val alice = newIdentity()
        val oldEdKey = alice.edPublicKey()
        val candidate = alice.generateNewKeyPair()
        alice.activateKeyPair(candidate)
        val message = "post-rotation message".encodeToByteArray()
        val sig = alice.sign(message)
        assertTrue(alice.verify(message, sig, candidate.edPublicKey))
        assertFalse(alice.verify(message, sig, oldEdKey))
    }
}
