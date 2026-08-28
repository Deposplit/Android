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

interface ShareEncryption {
    /** Encrypts [plaintext] to [recipientEncKey] via X25519+HKDF-SHA-256+ChaCha20-Poly1305. Returns nonce(12) || ciphertext+tag. */
    fun encrypt(plaintext: ByteArray, recipientEncKey: ByteArray): ByteArray
    /** Decrypts [noncePlusCiphertext] (nonce(12) || ciphertext+tag) using [recipientEncKey] via X25519+HKDF-SHA-256+ChaCha20-Poly1305. */
    fun decrypt(noncePlusCiphertext: ByteArray, recipientEncKey: ByteArray): ByteArray
}
