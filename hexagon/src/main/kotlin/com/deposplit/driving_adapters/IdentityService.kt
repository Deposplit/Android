/*
 * The MIT License
 *
 * Copyright (c) 2026 Squeng AG
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.deposplit.driving_adapters

import com.deposplit.driven_ports.IdentityStore
import com.deposplit.driving_ports.Identity
import com.deposplit.value_objects.IdentityIntegrity
import com.deposplit.value_objects.IdentityStorageUnavailableException
import com.deposplit.value_objects.KeyPairMaterial
import com.deposplit.value_objects.TransportSuite
import com.deposplit.value_objects.UnsupportedTransportSuiteException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import java.time.Instant

class IdentityService(private val identityStore: IdentityStore) : Identity, ShareEncryption {

    override fun isRegistered(): Boolean = identityStore.isRegistered()

    /* Derives each public key from its stored private half and compares it to the public key this
     * device hands out. That single question covers every way the two can come apart: key storage
     * emptied while the app's files survived a restore, a keystore blob that no longer decrypts,
     * and public keys restored without the private ones — the last of which produces no error at
     * all today, just a valid-looking QR for an identity that can no longer sign. */
    override fun integrity(): IdentityIntegrity {
        if (!identityStore.isRegistered()) return IdentityIntegrity.INTACT
        return try {
            // The private keys are read first on purpose. They are the half that can distinguish
            // locked from empty, so reading the optional public keys ahead of them would report a
            // merely locked device as KEYS_LOST — and KEYS_LOST is what offers to mint a
            // replacement identity over a working one.
            val derivedVerifyKey = Ed25519PrivateKeyParameters(identityStore.signKey()).generatePublicKey().encoded
            val derivedEncKey = X25519PrivateKeyParameters(identityStore.decKey()).generatePublicKey().encoded
            val storedVerifyKey = identityStore.verifyKey()
            val storedEncKey = identityStore.encKey()
            val matches = storedVerifyKey != null && storedEncKey != null &&
                derivedVerifyKey.contentEquals(storedVerifyKey) && derivedEncKey.contentEquals(storedEncKey)
            if (matches) IdentityIntegrity.INTACT else IdentityIntegrity.KEYS_LOST
        } catch (e: IdentityStorageUnavailableException) {
            IdentityIntegrity.UNREADABLE
        } catch (e: Exception) {
            IdentityIntegrity.KEYS_LOST
        }
    }

    override fun register(pseudonym: String) {
        val material = generateKeyPairMaterial()
        identityStore.save(pseudonym, material.verifyKey, material.signKey, material.encKey, material.decKey)
    }

    override fun generateNewKeyPair(): KeyPairMaterial = generateKeyPairMaterial()

    override fun activateKeyPair(keyPair: KeyPairMaterial) {
        identityStore.rotate(keyPair.verifyKey, keyPair.signKey, keyPair.encKey, keyPair.decKey)
    }

    private fun generateKeyPairMaterial(): KeyPairMaterial {
        val random = SecureRandom()

        val edGen = Ed25519KeyPairGenerator()
        edGen.init(Ed25519KeyGenerationParameters(random))
        val edPair = edGen.generateKeyPair()
        val verifyKey = (edPair.public as Ed25519PublicKeyParameters).encoded
        val signKey = (edPair.private as Ed25519PrivateKeyParameters).encoded

        val xGen = X25519KeyPairGenerator()
        xGen.init(X25519KeyGenerationParameters(random))
        val xPair = xGen.generateKeyPair()
        val encKey = (xPair.public as X25519PublicKeyParameters).encoded
        val decKey = (xPair.private as X25519PrivateKeyParameters).encoded

        return KeyPairMaterial(verifyKey, signKey, encKey, decKey)
    }

    override fun pseudonym(): String = identityStore.pseudonym()

    override fun identityCreatedAt(): Instant? = identityStore.identityCreatedAt()

    override fun verifyKey(): ByteArray? = identityStore.verifyKey()

    override fun encKey(): ByteArray? = identityStore.encKey()

    override fun sign(message: ByteArray): ByteArray {
        val sk = Ed25519PrivateKeyParameters(identityStore.signKey())
        val signer = Ed25519Signer()
        signer.init(true, sk)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    override fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean = try {
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        verifier.verifySignature(signature)
    } catch (e: Exception) {
        false
    }

    // Wire format is suiteTag(1) || nonce(12) || ciphertext+tag. No persistent state
    // needed: a device always encrypts with its current preferred TransportSuite, and a decrypting
    // device dispatches on the tag it reads.
    override fun encrypt(plaintext: ByteArray, recipientEncKey: ByteArray): ByteArray {
        val sk = X25519PrivateKeyParameters(identityStore.decKey())
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(sk, X25519PublicKeyParameters(recipientEncKey), nonce)
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return byteArrayOf(TransportSuite.current.tag) + nonce + out.copyOf(len)
    }

    // Falls back to the decKey displaced by the last rotation when the current one cannot open the
    // box. A share is sealed to whichever encKey the holder advertised at deposit time, so a holder
    // who rotates between a deposit and their pickup would otherwise never be able to collect it:
    // the row stays pending and every later poll fails identically. One generation is enough to
    // cover that window without keeping a keyring.
    //
    // Never used for encrypt() — this device always seals under its current key.
    override fun decrypt(noncePlusCiphertext: ByteArray, recipientEncKey: ByteArray): ByteArray {
        val tag = noncePlusCiphertext.getOrNull(0)
            ?: throw UnsupportedTransportSuiteException("ciphertext is empty — no transport suite tag")
        if (TransportSuite.fromTag(tag) == null) {
            throw UnsupportedTransportSuiteException("this share used an encryption scheme this app version doesn't support")
        }
        return try {
            open(noncePlusCiphertext, recipientEncKey, identityStore.decKey())
        } catch (e: Exception) {
            // The current key's failure is the one worth reporting — the fallback missing or failing
            // too just means there was no earlier generation this box belongs to.
            val previous = identityStore.previousDecKey() ?: throw e
            runCatching { open(noncePlusCiphertext, recipientEncKey, previous) }.getOrElse { throw e }
        }
    }

    private fun open(noncePlusCiphertext: ByteArray, recipientEncKey: ByteArray, decKey: ByteArray): ByteArray {
        val sk = X25519PrivateKeyParameters(decKey)
        val nonce = noncePlusCiphertext.copyOfRange(1, 1 + NONCE_BYTES)
        val ciphertext = noncePlusCiphertext.copyOfRange(1 + NONCE_BYTES, noncePlusCiphertext.size)
        val key = deriveKey(sk, X25519PublicKeyParameters(recipientEncKey), nonce)
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
        val out = ByteArray(cipher.getOutputSize(ciphertext.size))
        var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        len += cipher.doFinal(out, len)
        return out.copyOf(len)
    }

    private fun deriveKey(
        sk: X25519PrivateKeyParameters,
        pk: X25519PublicKeyParameters,
        nonce: ByteArray,
    ): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(sk)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pk, sharedSecret, 0)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, nonce, HKDF_INFO))
        val key = ByteArray(KEY_BYTES)
        hkdf.generateBytes(key, 0, KEY_BYTES)
        return key
    }

    companion object {
        private const val NONCE_BYTES = 12
        private const val KEY_BYTES = 32
        private const val TAG_BITS = 128
        private val HKDF_INFO = "deposplit-share".toByteArray(Charsets.UTF_8)
        private val secureRandom = SecureRandom()
    }
}
