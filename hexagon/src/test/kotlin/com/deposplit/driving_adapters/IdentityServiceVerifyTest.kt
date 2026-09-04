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
    private var _verifyKey = ByteArray(0)
    private var _signKey = ByteArray(0)
    private var _encKey = ByteArray(0)
    private var _decKey = ByteArray(0)
    private var _previousDecKey: ByteArray? = null

    override fun isRegistered() = registered

    override fun save(pseudonym: String, verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray) {
        this._pseudonym = pseudonym
        this._verifyKey = verifyKey
        this._signKey = signKey
        this._encKey = encKey
        this._decKey = decKey
        this._previousDecKey = null
        registered = true
    }

    override fun rotate(verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray) {
        this._previousDecKey = _decKey
        this._verifyKey = verifyKey
        this._signKey = signKey
        this._encKey = encKey
        this._decKey = decKey
    }

    override fun pseudonym() = _pseudonym
    override fun verifyKey(): ByteArray? = _verifyKey
    override fun signKey() = _signKey
    override fun encKey(): ByteArray? = _encKey
    override fun decKey() = _decKey
    override fun previousDecKey() = _previousDecKey
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
        assertTrue(alice.verify(message, sig, alice.verifyKey()!!))
    }

    @Test
    fun `verify returns false for a tampered message`() {
        val alice = newIdentity()
        val message = "hello deposplit".encodeToByteArray()
        val sig = alice.sign(message)
        val tampered = "hello depospliz".encodeToByteArray()
        assertFalse(alice.verify(tampered, sig, alice.verifyKey()!!))
    }

    @Test
    fun `verify returns false when checked against a different key`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val message = "hello deposplit".encodeToByteArray()
        val sig = alice.sign(message)
        assertFalse(bob.verify(message, sig, bob.verifyKey()!!))
    }

    // -------------------------------------------------------------------------
    // generateNewKeyPair() / activateKeyPair() — the identity-regeneration trigger
    // -------------------------------------------------------------------------

    @Test
    fun `generateNewKeyPair does not touch storage`() {
        val alice = newIdentity()
        val originalVerifyKey = alice.verifyKey()!!
        val originalEncKey = alice.encKey()!!
        val candidate = alice.generateNewKeyPair()
        assertTrue(!candidate.verifyKey.contentEquals(originalVerifyKey))
        assertTrue(!candidate.encKey.contentEquals(originalEncKey))
        // Unpersisted — the live identity hasn't moved.
        assertTrue(alice.verifyKey()!!.contentEquals(originalVerifyKey))
        assertTrue(alice.encKey()!!.contentEquals(originalEncKey))
    }

    @Test
    fun `activateKeyPair persists the new keys and preserves the pseudonym`() {
        val alice = newIdentity()
        val candidate = alice.generateNewKeyPair()
        alice.activateKeyPair(candidate)
        assertTrue(alice.verifyKey()!!.contentEquals(candidate.verifyKey))
        assertTrue(alice.encKey()!!.contentEquals(candidate.encKey))
        assertTrue(alice.pseudonym() == "test")
    }

    // A share is sealed to whichever encKey the holder advertised at deposit time. If rotating
    // destroyed the matching decKey outright, a holder who rotates between a deposit and their
    // pickup could never collect it — the row would stay pending and every later poll would fail
    // identically.

    @Test
    fun `decrypt falls back to the decKey displaced by the last rotation`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val share = "one share".encodeToByteArray()
        val sealedToAlicesOldKey = bob.encrypt(share, alice.encKey()!!)

        alice.activateKeyPair(alice.generateNewKeyPair())

        assertTrue(alice.decrypt(sealedToAlicesOldKey, bob.encKey()!!).contentEquals(share))
    }

    @Test
    fun `decrypt does not reach back past one generation`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val sealedToAlicesOldestKey = bob.encrypt("one share".encodeToByteArray(), alice.encKey()!!)

        alice.activateKeyPair(alice.generateNewKeyPair())
        alice.activateKeyPair(alice.generateNewKeyPair())

        // Deliberate: one generation covers the deposit-to-pickup window, and no more key material
        // than that lingers at rest.
        assertFailsWith<Exception> { alice.decrypt(sealedToAlicesOldestKey, bob.encKey()!!) }
    }

    @Test
    fun `encrypt never seals under the displaced key`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val alicesOldEncKey = alice.encKey()!!
        alice.activateKeyPair(alice.generateNewKeyPair())

        val sealed = alice.encrypt("outgoing".encodeToByteArray(), bob.encKey()!!)

        assertTrue(bob.decrypt(sealed, alice.encKey()!!).contentEquals("outgoing".encodeToByteArray()))
        assertFailsWith<Exception> { bob.decrypt(sealed, alicesOldEncKey) }
    }

    @Test
    fun `registering a fresh identity drops the retained key`() {
        val store = InMemoryIdentityStore()
        val alice = IdentityService(store)
        alice.register("test")
        val bob = newIdentity()
        val sealedToAlicesOldKey = bob.encrypt("one share".encodeToByteArray(), alice.encKey()!!)
        alice.activateKeyPair(alice.generateNewKeyPair())

        // Registration is a new identity, not a continuation of the old one, so nothing carries over.
        alice.register("test")

        assertFailsWith<Exception> { alice.decrypt(sealedToAlicesOldKey, bob.encKey()!!) }
    }

    @Test
    fun `sign after activateKeyPair verifies against the new key not the old`() {
        val alice = newIdentity()
        val oldVerifyKey = alice.verifyKey()!!
        val candidate = alice.generateNewKeyPair()
        alice.activateKeyPair(candidate)
        val message = "post-rotation message".encodeToByteArray()
        val sig = alice.sign(message)
        assertTrue(alice.verify(message, sig, candidate.verifyKey))
        assertFalse(alice.verify(message, sig, oldVerifyKey))
    }

    // -------------------------------------------------------------------------
    // encrypt()/decrypt() suite-tag wire format
    // -------------------------------------------------------------------------

    @Test
    fun `encrypt prepends the current TransportSuite tag and decrypt round-trips`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val plaintext = "a share of a secret".encodeToByteArray()

        // Alice (sender) encrypts to Bob's public enc key; Bob (recipient) decrypts with his own
        // private key + Alice's public enc key — the same static-static DH shape ShareService uses.
        val ciphertext = alice.encrypt(plaintext, bob.encKey()!!)

        assertEquals(TransportSuite.current.tag, ciphertext[0])
        val decrypted = bob.decrypt(ciphertext, alice.encKey()!!)
        assertTrue(decrypted.contentEquals(plaintext))
    }

    @Test
    fun `decrypt throws UnsupportedTransportSuiteException for an unrecognized suite tag`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val ciphertext = alice.encrypt("hi".encodeToByteArray(), bob.encKey()!!)
        val tampered = byteArrayOf(0x7f) + ciphertext.copyOfRange(1, ciphertext.size)

        assertFailsWith<UnsupportedTransportSuiteException> {
            bob.decrypt(tampered, alice.encKey()!!)
        }
    }

    @Test
    fun `decrypt throws UnsupportedTransportSuiteException for empty ciphertext`() {
        val alice = newIdentity()
        val bob = newIdentity()

        assertFailsWith<UnsupportedTransportSuiteException> {
            bob.decrypt(ByteArray(0), alice.encKey()!!)
        }
    }
}
