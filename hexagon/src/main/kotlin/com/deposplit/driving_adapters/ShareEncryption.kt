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

/**
 * Every box is HPKE base mode under the current [com.deposplit.value_objects.TransportSuite]: a
 * fresh ephemeral key per box, discarded once it is sealed, so only the recipient can open it —
 * not even the device that sealed it. Wire format: suiteTag(1) || enc(32) || ciphertext+tag(16).
 * The box proves nothing about who sealed it; the Ed25519 signature covering it does, and is
 * checked before every open.
 */
interface ShareEncryption {
    /** Seals [plaintext] (a share, or a share being handed back) to [recipientEncKey]. */
    fun encrypt(plaintext: ByteArray, recipientEncKey: ByteArray): ByteArray

    /**
     * Opens a box sealed to this device, dispatching on the leading transport-suite tag. Throws
     * [com.deposplit.value_objects.UnsupportedTransportSuiteException] for an unrecognized tag —
     * never a silent misparse.
     */
    fun decrypt(box: ByteArray): ByteArray

    /**
     * Seals a secret's label to [recipientEncKey] with the same box as [encrypt], under HPKE info
     * of its own, so a label box and a share box never share a key. The relay stores the result
     * without being able to read it.
     */
    fun sealLabel(label: String, recipientEncKey: ByteArray): ByteArray

    /**
     * Opens a label sealed to this device by [sealLabel], with the same fallback to the previous
     * decKey as [decrypt].
     */
    fun openLabel(sealedLabel: ByteArray): String
}
