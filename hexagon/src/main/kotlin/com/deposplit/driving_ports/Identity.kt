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

package com.deposplit.driving_ports

import com.deposplit.value_objects.IdentityIntegrity
import com.deposplit.value_objects.KeyPairMaterial

interface Identity {
    fun isRegistered(): Boolean

    /**
     * Whether the private keys this device believes it has are still there and still match the
     * public keys it advertises. Total and non-throwing: a device that cannot answer the question
     * reports [IdentityIntegrity.UNREADABLE] rather than failing, and an unregistered device
     * reports [IdentityIntegrity.INTACT] because it has nothing to have lost.
     */
    fun integrity(): IdentityIntegrity

    fun register(pseudonym: String)
    fun pseudonym(): String

    /**
     * This device's own public keys, or null when they are gone or cannot be read — an ordinary
     * state on a phone restored from a backup, whose files came across without the keys. Optional
     * rather than throwing, for the same reason as [com.deposplit.driven_ports.IdentityStore.previousDecKey]:
     * absence is a state to handle, not an exception. Callers wanting the fuller answer — gone
     * versus merely locked — ask [integrity].
     */
    fun verifyKey(): ByteArray?
    fun encKey(): ByteArray?
    fun sign(message: ByteArray): ByteArray

    /**
     * Verifies an Ed25519 [signature] over [message] against [publicKey] (someone else's, not
     * this identity's own). Used to independently re-verify the senderSignature/recipientSignature
     * that ride with a ShareRequest row — see [com.deposplit.value_objects.PayloadCanonical].
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean

    /**
     * Generates a fresh Ed25519 + X25519 keypair without touching storage. The caller
     * (see [com.deposplit.driving_ports.ShareManagement.regenerateIdentity]) is expected to push
     * a rotation notice signed by the *current* (soon-to-be-old) identity before calling
     * [activateKeyPair], proving continuity of key control to every contact.
     */
    fun generateNewKeyPair(): KeyPairMaterial

    /**
     * Persists [keyPair] as this device's identity, preserving the existing pseudonym.
     * After this call, [sign]/[verifyKey]/[encKey] all reflect the new keys.
     *
     * The displaced decKey is kept one generation deep, so a share sealed to the old encKey while
     * it was still current can still be opened at pickup. The displaced signKey is not: this
     * device stops being able to sign as its former self the moment it rotates.
     */
    fun activateKeyPair(keyPair: KeyPairMaterial)
}
