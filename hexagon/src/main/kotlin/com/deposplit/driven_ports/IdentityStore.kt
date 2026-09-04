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

package com.deposplit.driven_ports

import java.time.Instant


/* IdentityStore manages exactly one thing: the current user's keypair and pseudonym. There's no list, no ID-based
 * lookup, no getAll(). The interface is essentially a typed credential store — save(...) once at registration, then
 * read individual fields. Calling it IdentityRepository would imply a collection of identities could exist, which
 * doesn't match the model (one device = one identity).
 */
interface IdentityStore {
    fun isRegistered(): Boolean

    /* Registration. Establishes a brand-new identity, so any retained previous decKey is cleared — it belonged to an
     * identity this device is no longer continuous with. */
    fun save(pseudonym: String, verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray)

    /* Rotation. Persists the new keys and moves the displaced decKey into the previous slot, preserving the pseudonym.
     * Separate from save() because only rotation is continuous with what came before, and only rotation may leave an
     * old key readable.
     *
     * Only the decKey is kept. The old signKey is destroyed here: retaining it would let someone who extracts an
     * unlocked device sign a rotation notice as the *previous* identity, which every contact would auto-accept as
     * proof of key continuity. */
    fun rotate(verifyKey: ByteArray, signKey: ByteArray, encKey: ByteArray, decKey: ByteArray)

    fun pseudonym(): String

    /* When the identity this device holds today was established — set by save(), and deliberately
     * left alone by rotate(). Rotation is continuous with what came before and propagates itself
     * through signed notices every contact auto-accepts; registration is a break every contact has
     * to be told about by hand. Null on a device registered before this was recorded. */
    fun identityCreatedAt(): Instant?

    /* This device's own public keys, or null when they are gone or cannot be read. Optional rather
     * than throwing, for the same reason as previousDecKey() below: absence is an ordinary state on
     * a restored device, not an exception. Contrast signKey()/decKey(), where a caller who wants to
     * sign has no fallback to fall back on. */
    fun verifyKey(): ByteArray?

    /* signKey() and decKey() must distinguish key material that is *absent or unusable* from key
     * material that merely cannot be read at this moment — a locked device, a keystore not yet
     * available. The former is any exception; the latter is specifically
     * IdentityStorageUnavailableException. Only an adapter sees the platform's own error, so only
     * an adapter can tell them apart, and IdentityIntegrity depends on the answer: it is what
     * decides whether the app offers to mint a replacement identity over the top. */
    fun signKey(): ByteArray
    fun encKey(): ByteArray?
    fun decKey(): ByteArray

    /* The decKey displaced by the most recent rotate(), or null on an identity that has never rotated. Does not throw:
     * absence is the ordinary case, and a storage read that fails should cost the fallback, never the decryption that
     * was going to succeed anyway. */
    fun previousDecKey(): ByteArray?
}
