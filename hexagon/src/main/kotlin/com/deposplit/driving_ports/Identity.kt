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

interface Identity {
    fun isRegistered(): Boolean
    fun register(pseudonym: String)
    fun pseudonym(): String
    fun edPublicKey(): ByteArray
    fun xPublicKey(): ByteArray
    fun sign(message: ByteArray): ByteArray

    /**
     * Verifies an Ed25519 [signature] over [message] against [publicKey] (someone else's, not
     * this identity's own). Used to independently re-verify the senderSignature/recipientSignature
     * that ride with a ShareRequest row — see [com.deposplit.value_objects.PayloadCanonical].
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
}
