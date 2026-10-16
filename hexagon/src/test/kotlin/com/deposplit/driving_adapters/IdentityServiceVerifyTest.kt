package com.deposplit.driving_adapters

import com.deposplit.driven_ports.IdentityStore
import com.deposplit.value_objects.KeyPairMaterial
import com.deposplit.value_objects.TransportSuite
import com.deposplit.value_objects.UnsupportedTransportSuiteException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/** In-memory IdentityStore — no Keystore/file I/O needed for these tests. */
private class InMemoryIdentityStore : IdentityStore {
    private var registered = false
    private var _pseudonym = ""
    private var _verifyKey = ByteArray(0)
    private var _signKey = ByteArray(0)
    private var _encKey = ByteArray(0)
    private var _decKey = ByteArray(0)
    private var _previousDecKey: ByteArray? = null
    private var _identityCreatedAt: java.time.Instant? = null

    override fun isRegistered() = registered

    override fun save(pseudonym: String, verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray) {
        this._pseudonym = pseudonym
        this._verifyKey = verifyKey
        this._signKey = signKey
        this._encKey = encKey
        this._decKey = decKey
        this._previousDecKey = null
        // Mirrors the real adapters: registration starts a new identity, rotation continues one.
        this._identityCreatedAt = java.time.Instant.now()
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
    override fun identityCreatedAt(): java.time.Instant? = _identityCreatedAt
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

        assertTrue(alice.decrypt(sealedToAlicesOldKey).contentEquals(share))
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
        assertFailsWith<Exception> { alice.decrypt(sealedToAlicesOldestKey) }
    }

    @Test
    fun `sealing involves no key of the sender's own`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val sealedBeforeAlicesRotations = alice.encrypt("outgoing".encodeToByteArray(), bob.encKey()!!)
        alice.activateKeyPair(alice.generateNewKeyPair())
        alice.activateKeyPair(alice.generateNewKeyPair())

        // Bob never needs Alice's key to open it, so however often she rotates, what she sealed opens.
        assertTrue(bob.decrypt(sealedBeforeAlicesRotations).contentEquals("outgoing".encodeToByteArray()))
    }

    // The property the retained deposits rest on: a box sealed for a holder stays closed to the owner.
    @Test
    fun `a sender cannot open a box she sealed for somebody else`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val sealed = alice.encrypt("Bob's share".encodeToByteArray(), bob.encKey()!!)

        assertFailsWith<Exception> { alice.decrypt(sealed) }
    }

    @Test
    fun `a box is the tag the encapsulated key and the ciphertext with a fresh ephemeral key each time`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val plaintext = "shhh".encodeToByteArray()
        val first = alice.encrypt(plaintext, bob.encKey()!!)
        val second = alice.encrypt(plaintext, bob.encKey()!!)

        assertEquals(1 + 32 + plaintext.size + 16, first.size)
        assertFalse(first.copyOfRange(1, 33).contentEquals(second.copyOfRange(1, 33)))
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

        assertFailsWith<Exception> { alice.decrypt(sealedToAlicesOldKey) }
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
        val decrypted = bob.decrypt(ciphertext)
        assertTrue(decrypted.contentEquals(plaintext))
    }

    @Test
    fun `decrypt throws UnsupportedTransportSuiteException for an unrecognized suite tag`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val ciphertext = alice.encrypt("hi".encodeToByteArray(), bob.encKey()!!)
        val tampered = byteArrayOf(0x7f) + ciphertext.copyOfRange(1, ciphertext.size)

        assertFailsWith<UnsupportedTransportSuiteException> {
            bob.decrypt(tampered)
        }
    }

    @Test
    fun `decrypt throws UnsupportedTransportSuiteException for empty ciphertext`() {
        val alice = newIdentity()
        val bob = newIdentity()

        assertFailsWith<UnsupportedTransportSuiteException> {
            bob.decrypt(ByteArray(0))
        }
    }

    // Sealed labels — the same box as a share, under a key derivation of its own.

    @Test
    fun `sealLabel then openLabel round-trips`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val sealed = alice.sealLabel("Bitcoin seed \uD83D\uDD11", bob.encKey()!!)

        assertEquals(TransportSuite.current.tag, sealed[0])
        assertEquals("Bitcoin seed \uD83D\uDD11", bob.openLabel(sealed))
    }

    @Test
    fun `a sealed label does not carry its text in the clear`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val plaintext = "Bitcoin seed".encodeToByteArray().toList()
        val sealed = alice.sealLabel("Bitcoin seed", bob.encKey()!!).toList()

        assertFalse(sealed.windowed(plaintext.size).any { it == plaintext })
    }

    @Test
    fun `a share box does not open as a label nor a label box as a share`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val shareBox = alice.encrypt("one share".encodeToByteArray(), bob.encKey()!!)
        val labelBox = alice.sealLabel("one label", bob.encKey()!!)

        assertFailsWith<Exception> { bob.openLabel(shareBox) }
        assertFailsWith<Exception> { bob.decrypt(labelBox) }
    }

    @Test
    fun `openLabel falls back to the decKey displaced by the last rotation`() {
        val alice = newIdentity()
        val bob = newIdentity()
        val sealedToAlicesOldKey = bob.sealLabel("passport", alice.encKey()!!)

        alice.activateKeyPair(alice.generateNewKeyPair())

        assertEquals("passport", alice.openLabel(sealedToAlicesOldKey))
    }

    // Cross-platform box vectors — the same four boxes, sealed once by CryptoKit and once by
    // BouncyCastle to one fixed recipient key, are opened by iOS, Android and phon alike. HPKE draws
    // a fresh ephemeral key per box, so a box cannot be reproduced byte for byte; what each platform
    // proves is that it opens what the other library sealed. Identical fixtures are checked into
    // iOS's IdentityServiceVerifyTests.swift and phon's IdentityServiceVerifyTests.scala.

    private val vectorRecipientDecKey = ByteArray(32) { (0x20 + it).toByte() }
    private val vectorRecipientEncKey = hex("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254")
    private val cryptoKitShareBox = hex("028645dabe9fc9135324fc4b60c60d562a5b91cbba3a4e7767363138c411154465cfaff103793c5427240d1c797a5c7f3019b453158117d32c409c073aa86d828ce754b2")
    private val cryptoKitLabelBox = hex("02182112460138f7f6314a553918a4f46d85148ccd8a7ab322c0cb2eb34ac97f528f0673b03dac84898ee14fbc6cff3c2318acb1fe4ef3d036a797f294ce8cef4274")
    private val bouncyCastleShareBox = hex("02b09f312abb31cbf78fc7c41f3912006d25d966d2be78a376bd388cdd30e685446a75dadf1450dade7e955dd6879fa1e9fd1aa7e62e721912fa5bcec7854e7397b80767")
    private val bouncyCastleLabelBox = hex("02e859635cccd28e42b3f26bfacdf98db69242fd81c5faa67dec62de46714b270f48bdce7f66c707226e73396b84cb1d9fa4e5b9cf7b008df9fed2163a14f8be83fc")

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun vectorRecipient(): IdentityService {
        val recipient = newIdentity()
        val current = recipient.generateNewKeyPair()
        recipient.activateKeyPair(KeyPairMaterial(current.verifyKey, current.signKey, vectorRecipientEncKey, vectorRecipientDecKey))
        return recipient
    }

    @Test
    fun `the fixed recipient key derives the fixed public key`() {
        val derived = X25519PrivateKeyParameters(vectorRecipientDecKey, 0).generatePublicKey().encoded
        assertTrue(derived.contentEquals(vectorRecipientEncKey))
    }

    @Test
    fun `the CryptoKit vector boxes open`() {
        val recipient = vectorRecipient()
        assertEquals("a share of a secret", String(recipient.decrypt(cryptoKitShareBox), Charsets.UTF_8))
        assertEquals("Bitcoin seed \uD83D\uDD11", recipient.openLabel(cryptoKitLabelBox))
    }

    @Test
    fun `the BouncyCastle vector boxes open`() {
        val recipient = vectorRecipient()
        assertEquals("a share of a secret", String(recipient.decrypt(bouncyCastleShareBox), Charsets.UTF_8))
        assertEquals("Bitcoin seed \uD83D\uDD11", recipient.openLabel(bouncyCastleLabelBox))
    }
}
