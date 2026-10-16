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
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
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

    // Sealing involves no key of this device's own: HPKE draws a fresh ephemeral key for every box
    // and discards it once the box is sealed, so not even this device can open what it sealed.
    override fun encrypt(plaintext: ByteArray, recipientEncKey: ByteArray): ByteArray =
        seal(plaintext, recipientEncKey, SHARE_INFO)

    override fun sealLabel(label: String, recipientEncKey: ByteArray): ByteArray =
        seal(label.toByteArray(Charsets.UTF_8), recipientEncKey, LABEL_INFO)

    // Malformed UTF-8 decodes to replacement characters rather than failing, as on the other
    // platforms. A label is only ever shown, and the signature over it has already proved who
    // sealed it.
    override fun openLabel(sealedLabel: ByteArray): String =
        String(unseal(sealedLabel, LABEL_INFO), Charsets.UTF_8)

    private fun seal(plaintext: ByteArray, recipientEncKey: ByteArray, info: ByteArray): ByteArray {
        val hpke = hpke()
        val (ciphertext, encapsulatedKey) =
            hpke.seal(hpke.deserializePublicKey(recipientEncKey), info, NO_AAD, plaintext, null, null, null)
        return byteArrayOf(TransportSuite.current.tag) + encapsulatedKey + ciphertext
    }

    // Falls back to the decKey displaced by the last rotation when the current one cannot open the
    // box. A share is sealed to whichever encKey the holder advertised at deposit time, so a holder
    // who rotates between a deposit and their pickup would otherwise never be able to collect it:
    // the row stays pending and every later poll fails identically. One generation is enough to
    // cover that window without keeping a keyring.
    override fun decrypt(box: ByteArray): ByteArray = unseal(box, SHARE_INFO)

    private fun unseal(box: ByteArray, info: ByteArray): ByteArray {
        val tag = box.getOrNull(0)
            ?: throw UnsupportedTransportSuiteException("ciphertext is empty — no transport suite tag")
        if (TransportSuite.fromTag(tag) == null) {
            throw UnsupportedTransportSuiteException("this share used an encryption scheme this app version doesn't support")
        }
        return try {
            open(box, identityStore.decKey(), info)
        } catch (e: Exception) {
            // The current key's failure is the one worth reporting — the fallback missing or failing
            // too just means there was no earlier generation this box belongs to.
            val previous = identityStore.previousDecKey() ?: throw e
            runCatching { open(box, previous, info) }.getOrElse { throw e }
        }
    }

    private fun open(box: ByteArray, decKey: ByteArray, info: ByteArray): ByteArray {
        require(box.size > 1 + ENCAPSULATED_KEY_BYTES) { "box too short to carry an encapsulated key" }
        val encapsulatedKey = box.copyOfRange(1, 1 + ENCAPSULATED_KEY_BYTES)
        val ciphertext = box.copyOfRange(1 + ENCAPSULATED_KEY_BYTES, box.size)
        val hpke = hpke()
        val publicKey = X25519PrivateKeyParameters(decKey, 0).generatePublicKey().encoded
        val recipient = hpke.deserializePrivateKey(decKey, publicKey)
        return hpke.open(encapsulatedKey, recipient, info, NO_AAD, ciphertext, null, null, null)
    }

    companion object {
        // DHKEM(X25519)'s encapsulated key: the ephemeral public key, 32 bytes.
        private const val ENCAPSULATED_KEY_BYTES = 32
        private val NO_AAD = ByteArray(0)
        private val SHARE_INFO = "deposplit-share".toByteArray(Charsets.UTF_8)

        // HPKE info of its own, so a sealed label can never be opened as a sealed share.
        private val LABEL_INFO = "deposplit-label".toByteArray(Charsets.UTF_8)

        private fun hpke() =
            HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_CHACHA20_POLY1305)
    }
}
