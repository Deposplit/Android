package com.deposplit.value_objects

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-platform interop vector for [PayloadCanonical.forOpen]'s byte construction — mirrors the
 * existing hand-derived SSS test vectors (`ShamirTest.kt` / `ShamirSecretSharingTests.swift`).
 * Ed25519 sign/verify interop across BouncyCastle/CryptoKit is already proven via the
 * transport-auth signature; what this vector
 * actually exercises is the *canonical byte construction* itself — a field-order or encoding slip
 * on any one platform would silently produce a different signature than the other two even though
 * each platform's own sign/verify round-trips fine internally.
 *
 * Identical fixed inputs, keypair, and expected outputs are checked into
 * `deposplit.com/hexagons/relay/src/test/scala/value_objects/PayloadCanonicalVectorTests.scala`
 * and `iOS/hexagon/Tests/PayloadCanonicalVectorTests.swift`. All three must produce byte-identical
 * canonical bytes and the same signature for the same 32-byte private key seed.
 */
class PayloadCanonicalVectorTest {

    private val b64url: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    // Private key seed: bytes 0x00..0x1f. Not a real identity — a fixed, reproducible fixture.
    private val privateKeySeed: ByteArray = ByteArray(32) { it.toByte() }
    private val expectedPublicKeyBase64Url = "A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg"
    private val expectedSignatureBase64Url =
        "WFKVgN38zr_3fiLZ1UpxnrvUoW0KA-XjD1ml-VyfITDuCMv9D9uT0ryaHCiHYtWc9_rSpOKDw4kjbtqHMRPwBA"

    private val secretId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val recipientKey: ByteArray = ByteArray(32) { 0x02 }
    private val label = "cross-platform test vector"
    private val secretCreatedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val ciphertext: ByteArray = byteArrayOf(1, 2, 3, 4, 5)
    // k/n, then mimeType — each appended at the end of the field sequence in turn, so the fields
    // above are byte-identical to the vector that predates them.
    private val k = 2
    private val n = 3
    private val mimeType = MimeType("text/plain")

    @Test
    fun `forOpen produces the fixed canonical bytes`() {
        val canon = PayloadCanonical.forOpen(secretId, ShareTransactionType.DEPOSIT, recipientKey, label, secretCreatedAt, ciphertext, k, n, mimeType)
        val expected =
            "11111111-1111-1111-1111-111111111111\ndeposit\nAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI\ncross-platform test vector\n1767225600000\nAQIDBAU=\n2\n3\ntext/plain"
        assertEquals(expected, String(canon, Charsets.UTF_8))
    }

    @Test
    fun `signing the canonical bytes with the fixed seed reproduces the fixed signature`() {
        val canon = PayloadCanonical.forOpen(secretId, ShareTransactionType.DEPOSIT, recipientKey, label, secretCreatedAt, ciphertext, k, n, mimeType)
        val privKey = Ed25519PrivateKeyParameters(privateKeySeed, 0)
        val pubKey = privKey.generatePublicKey()
        assertEquals(expectedPublicKeyBase64Url, b64url.encodeToString(pubKey.encoded))

        val signer = Ed25519Signer()
        signer.init(true, privKey)
        signer.update(canon, 0, canon.size)
        val sig = signer.generateSignature()
        assertEquals(expectedSignatureBase64Url, b64url.encodeToString(sig))
    }

    @Test
    fun `the fixed signature verifies against the fixed public key`() {
        val canon = PayloadCanonical.forOpen(secretId, ShareTransactionType.DEPOSIT, recipientKey, label, secretCreatedAt, ciphertext, k, n, mimeType)
        val pubKeyBytes = Base64.getUrlDecoder().decode(expectedPublicKeyBase64Url)
        val sigBytes = Base64.getUrlDecoder().decode(expectedSignatureBase64Url)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(pubKeyBytes, 0))
        verifier.update(canon, 0, canon.size)
        assertTrue(verifier.verifySignature(sigBytes))
    }

    // ── forRotation — same cross-platform-interop purpose as forOpen above, checked into
    // the relay's and iOS's PayloadCanonicalVectorTests. Uses the same fixed private key seed as
    // forOpen so both vectors are anchored to one known keypair.

    private val rotationRecipientKey: ByteArray = ByteArray(32) { 0x03 }
    private val newVerifyKey: ByteArray = ByteArray(32) { 0x04 }
    private val newEncKey: ByteArray = ByteArray(32) { 0x05 }
    // Appended as a 4th line; the fields above are byte-identical to the original vector.
    private val newCipherSuite = CipherSuite.current
    private val expectedRotationSignatureBase64Url =
        "EH45bL4chGQALZ6J9IDhfUAtPNovGHmqlJvF6HBKa8sqkF3SU1NhMGWmSTGM87isxdHIxoQCHFITplmzN1zeDg"

    @Test
    fun `forRotation produces the fixed canonical bytes`() {
        val canon = PayloadCanonical.forRotation(rotationRecipientKey, newVerifyKey, newEncKey, newCipherSuite)
        val expected =
            "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM\nBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ\nBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU\ned25519+x25519-v1"
        assertEquals(expected, String(canon, Charsets.UTF_8))
    }

    @Test
    fun `signing the rotation canonical bytes with the fixed seed reproduces the fixed signature`() {
        val canon = PayloadCanonical.forRotation(rotationRecipientKey, newVerifyKey, newEncKey, newCipherSuite)
        val privKey = Ed25519PrivateKeyParameters(privateKeySeed, 0)
        val signer = Ed25519Signer()
        signer.init(true, privKey)
        signer.update(canon, 0, canon.size)
        val sig = signer.generateSignature()
        assertEquals(expectedRotationSignatureBase64Url, b64url.encodeToString(sig))
    }

    @Test
    fun `the fixed rotation signature verifies against the fixed public key`() {
        val canon = PayloadCanonical.forRotation(rotationRecipientKey, newVerifyKey, newEncKey, newCipherSuite)
        val pubKeyBytes = Base64.getUrlDecoder().decode(expectedPublicKeyBase64Url)
        val sigBytes = Base64.getUrlDecoder().decode(expectedRotationSignatureBase64Url)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(pubKeyBytes, 0))
        verifier.update(canon, 0, canon.size)
        assertTrue(verifier.verifySignature(sigBytes))
    }

    // ── forHeartbeat — same cross-platform-interop purpose as forOpen/forRotation
    // above, checked into the relay's PayloadCanonicalVectorTests. Uses the same fixed private
    // key seed so all three vectors are anchored to one known keypair.

    private val heartbeatOwnerKey: ByteArray = ByteArray(32) { 0x06 }
    // Deliberately out of sorted order in the fixture to prove forHeartbeat sorts before joining
    // — a naive pass-through would silently disagree with a platform that assembled the list
    // differently.
    private val heartbeatSecretIds: List<UUID> = listOf(
        UUID.fromString("33333333-3333-3333-3333-333333333333"),
        UUID.fromString("11111111-1111-1111-1111-111111111111"),
        UUID.fromString("22222222-2222-2222-2222-222222222222"),
    )
    private val expectedHeartbeatSignatureBase64Url =
        "w6fmGn4t7y2RSNakPBzi57H40u5kJI6CZAhEGdzLBOwZd__jabsge2tEmIpczMqEd3ODpNUJ72Ww2KEe8LYQCw"

    @Test
    fun `forHeartbeat produces the fixed canonical bytes sorted regardless of input order`() {
        val canon = PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, false)
        val expected =
            "BgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgYGBgY\n11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222,33333333-3333-3333-3333-333333333333\nfalse"
        assertEquals(expected, String(canon, Charsets.UTF_8))
    }

    @Test
    fun `signing the heartbeat canonical bytes with the fixed seed reproduces the fixed signature`() {
        val canon = PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, false)
        val privKey = Ed25519PrivateKeyParameters(privateKeySeed, 0)
        val signer = Ed25519Signer()
        signer.init(true, privKey)
        signer.update(canon, 0, canon.size)
        val sig = signer.generateSignature()
        assertEquals(expectedHeartbeatSignatureBase64Url, b64url.encodeToString(sig))
    }

    @Test
    fun `the fixed heartbeat signature verifies against the fixed public key`() {
        val canon = PayloadCanonical.forHeartbeat(heartbeatOwnerKey, heartbeatSecretIds, false)
        val pubKeyBytes = Base64.getUrlDecoder().decode(expectedPublicKeyBase64Url)
        val sigBytes = Base64.getUrlDecoder().decode(expectedHeartbeatSignatureBase64Url)
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(pubKeyBytes, 0))
        verifier.update(canon, 0, canon.size)
        assertTrue(verifier.verifySignature(sigBytes))
    }
}
