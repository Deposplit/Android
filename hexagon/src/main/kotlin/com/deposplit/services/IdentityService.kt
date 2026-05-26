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

package com.deposplit.services

import com.deposplit.driven_ports.IdentityStore
import com.deposplit.driving_ports.Identity
import com.deposplit.driving_ports.RequestSigner
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

class IdentityService(private val identityStore: IdentityStore) : Identity, ShareEncryption, RequestSigner {

    override fun isRegistered(): Boolean = identityStore.isRegistered()

    override fun register(pseudonym: String) {
        val random = SecureRandom()

        val edGen = Ed25519KeyPairGenerator()
        edGen.init(Ed25519KeyGenerationParameters(random))
        val edPair = edGen.generateKeyPair()
        val edPk = (edPair.public as Ed25519PublicKeyParameters).encoded
        val edSk = (edPair.private as Ed25519PrivateKeyParameters).encoded

        val xGen = X25519KeyPairGenerator()
        xGen.init(X25519KeyGenerationParameters(random))
        val xPair = xGen.generateKeyPair()
        val xPk = (xPair.public as X25519PublicKeyParameters).encoded
        val xSk = (xPair.private as X25519PrivateKeyParameters).encoded

        identityStore.save(pseudonym, edPk, edSk, xPk, xSk)
    }

    override fun pseudonym(): String = identityStore.pseudonym()

    override fun edPublicKey(): ByteArray = identityStore.edPublicKey()

    override fun xPublicKey(): ByteArray = identityStore.xPublicKey()

    override fun sign(message: ByteArray): ByteArray {
        val sk = Ed25519PrivateKeyParameters(identityStore.edPrivateKey())
        val signer = Ed25519Signer()
        signer.init(true, sk)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    override fun encrypt(plaintext: ByteArray, recipientXPublicKey: ByteArray): ByteArray {
        val sk = X25519PrivateKeyParameters(identityStore.xPrivateKey())
        val nonce = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
        val key = deriveKey(sk, X25519PublicKeyParameters(recipientXPublicKey), nonce)
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), TAG_BITS, nonce))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        return nonce + out.copyOf(len)
    }

    override fun decrypt(noncePlusCiphertext: ByteArray, recipientXPublicKey: ByteArray): ByteArray {
        val sk = X25519PrivateKeyParameters(identityStore.xPrivateKey())
        val nonce = noncePlusCiphertext.copyOfRange(0, NONCE_BYTES)
        val ciphertext = noncePlusCiphertext.copyOfRange(NONCE_BYTES, noncePlusCiphertext.size)
        val key = deriveKey(sk, X25519PublicKeyParameters(recipientXPublicKey), nonce)
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
