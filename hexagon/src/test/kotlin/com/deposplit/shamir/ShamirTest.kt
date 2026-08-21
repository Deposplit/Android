package com.deposplit.shamir

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShamirTest {

    // -------------------------------------------------------------------------
    // Round-trip tests
    // -------------------------------------------------------------------------

    @Test
    fun `split then combine recovers secret for 2-of-3`() {
        val secret = "Hello, Deposplit!".encodeToByteArray()
        val shares = split(secret, shares = 3, threshold = 2)
        assertContentEquals(secret, combine(shares.take(2)))
        assertContentEquals(secret, combine(shares.takeLast(2)))
        assertContentEquals(secret, combine(listOf(shares[0], shares[2])))
    }

    @Test
    fun `split then combine recovers secret for 3-of-5`() {
        val secret = ByteArray(32) { it.toByte() }
        val shares = split(secret, shares = 5, threshold = 3)
        assertContentEquals(secret, combine(shares.take(3)))
        assertContentEquals(secret, combine(shares.takeLast(3)))
        assertContentEquals(secret, combine(listOf(shares[0], shares[2], shares[4])))
    }

    @Test
    fun `split then combine recovers secret for 255-of-255`() {
        val secret = byteArrayOf(0x42)
        val shares = split(secret, shares = 255, threshold = 255)
        assertContentEquals(secret, combine(shares))
    }

    @Test
    fun `split then combine recovers single-byte secret`() {
        val secret = byteArrayOf(0xff.toByte())
        val shares = split(secret, shares = 2, threshold = 2)
        assertContentEquals(secret, combine(shares))
    }

    @Test
    fun `combining all shares also works when more than threshold are provided`() {
        val secret = "extra shares are fine".encodeToByteArray()
        val shares = split(secret, shares = 5, threshold = 3)
        assertContentEquals(secret, combine(shares))
    }

    // -------------------------------------------------------------------------
    // Cross-platform test vectors
    //
    // These vectors are derived by hand from the GF(2^8) arithmetic and verify
    // that combine() is implemented correctly. They use the polynomial
    // f(x) = secret_byte + 0x01·x in GF(2^8) with x-coordinates [1, 2],
    // which is the simplest non-trivial 2-of-2 case (threshold = 2, degree = 1,
    // leading coefficient c₁ = 0x01).
    //
    // These same vectors must be used in the Swift port to confirm byte-for-byte
    // cross-platform compatibility of combine().
    // -------------------------------------------------------------------------

    @Test
    fun `cross-platform vector 1 - zero secret byte`() {
        // secret = [0x00]
        // f(x) = 0x00 + 0x01·x  →  f(1) = 0x01, f(2) = 0x02
        val shares = listOf(
            byteArrayOf(0x01, 0x01),  // y = 0x01, x = 0x01
            byteArrayOf(0x02, 0x02),  // y = 0x02, x = 0x02
        )
        assertContentEquals(byteArrayOf(0x00), combine(shares))
    }

    @Test
    fun `cross-platform vector 2 - non-zero secret byte`() {
        // secret = [0x41]  ('A')
        // f(x) = 0x41 + 0x01·x  →  f(1) = 0x40, f(2) = 0x43
        val shares = listOf(
            byteArrayOf(0x40, 0x01),  // y = 0x40, x = 0x01
            byteArrayOf(0x43, 0x02),  // y = 0x43, x = 0x02
        )
        assertContentEquals(byteArrayOf(0x41), combine(shares))
    }

    @Test
    fun `cross-platform vector 3 - multi-byte secret`() {
        // secret = [0x00, 0x41]
        // Byte 0: f(x) = 0x00 + 0x01·x  →  f(1) = 0x01, f(2) = 0x02
        // Byte 1: f(x) = 0x41 + 0x01·x  →  f(1) = 0x40, f(2) = 0x43
        val shares = listOf(
            byteArrayOf(0x01, 0x40, 0x01),  // [y₀, y₁, x] for x = 0x01
            byteArrayOf(0x02, 0x43, 0x02),  // [y₀, y₁, x] for x = 0x02
        )
        assertContentEquals(byteArrayOf(0x00, 0x41), combine(shares))
    }

    // -------------------------------------------------------------------------
    // Input validation — split()
    // -------------------------------------------------------------------------

    @Test
    fun `split rejects empty secret`() {
        assertFailsWith<IllegalArgumentException> {
            split(ByteArray(0), shares = 2, threshold = 2)
        }
    }

    @Test
    fun `split rejects shares below 2`() {
        assertFailsWith<IllegalArgumentException> {
            split(byteArrayOf(0x01), shares = 1, threshold = 1)
        }
    }

    @Test
    fun `split rejects shares above 255`() {
        assertFailsWith<IllegalArgumentException> {
            split(byteArrayOf(0x01), shares = 256, threshold = 2)
        }
    }

    @Test
    fun `split rejects threshold below 2`() {
        assertFailsWith<IllegalArgumentException> {
            split(byteArrayOf(0x01), shares = 2, threshold = 1)
        }
    }

    @Test
    fun `split rejects threshold above 255`() {
        assertFailsWith<IllegalArgumentException> {
            split(byteArrayOf(0x01), shares = 255, threshold = 256)
        }
    }

    @Test
    fun `split rejects threshold greater than shares`() {
        assertFailsWith<IllegalArgumentException> {
            split(byteArrayOf(0x01), shares = 2, threshold = 3)
        }
    }

    // -------------------------------------------------------------------------
    // Input validation — combine()
    // -------------------------------------------------------------------------

    @Test
    fun `combine rejects fewer than 2 shares`() {
        assertFailsWith<IllegalArgumentException> {
            combine(listOf(byteArrayOf(0x01, 0x02)))
        }
    }

    @Test
    fun `combine rejects shares shorter than 2 bytes`() {
        assertFailsWith<IllegalArgumentException> {
            combine(listOf(byteArrayOf(0x01), byteArrayOf(0x02)))
        }
    }

    @Test
    fun `combine rejects shares with mismatched lengths`() {
        assertFailsWith<IllegalArgumentException> {
            combine(listOf(byteArrayOf(0x01, 0x02), byteArrayOf(0x01, 0x02, 0x03)))
        }
    }

    @Test
    fun `combine rejects duplicate x-coordinates`() {
        // Both shares have x = 0x05 (last byte)
        assertFailsWith<IllegalArgumentException> {
            combine(listOf(byteArrayOf(0x01, 0x05), byteArrayOf(0x02, 0x05)))
        }
    }

    // -------------------------------------------------------------------------
    // combineWithIntegrity() — item 13 (reconstruction integrity via over-determination)
    // -------------------------------------------------------------------------

    // Corrupts every secret byte of a share (leaving its x-coordinate intact), simulating a
    // tampered/forged/bit-flipped holder response — wrong as a whole, not selectively per-byte.
    private fun tamper(share: ByteArray): ByteArray {
        val tampered = share.copyOf()
        for (i in 0 until tampered.size - 1) {
            tampered[i] = (tampered[i] + 1).toByte()
        }
        return tampered
    }

    @Test
    fun `combineWithIntegrity at exactly threshold has no margin`() {
        val secret = "no margin here".encodeToByteArray()
        val shares = split(secret, 4, 4)
        val result = combineWithIntegrity(shares, 4)
        assertContentEquals(secret, result.secret)
        assertEquals(false, result.hasIntegrityMargin)
        assertEquals(emptySet(), result.excludedIndices)
    }

    @Test
    fun `combineWithIntegrity with surplus all consistent is confirmed`() {
        val secret = "all agree".encodeToByteArray()
        val shares = split(secret, 5, 4)
        val result = combineWithIntegrity(shares, 4)
        assertContentEquals(secret, result.secret)
        assertEquals(true, result.hasIntegrityMargin)
        assertEquals(emptySet(), result.excludedIndices)
    }

    @Test
    fun `combineWithIntegrity at margin 1 with one bad share detects but cannot correct`() {
        // threshold+1 collected, one bad: can only *detect* a problem exists (CLAUDE.md item 13),
        // never identify which side is at fault — must throw rather than guess.
        val secret = "margin one".encodeToByteArray()
        val shares = split(secret, 5, 4).toMutableList()
        shares[0] = tamper(shares[0])
        val e = assertFailsWith<ReconstructionIntegrityException> {
            combineWithIntegrity(shares, 4)
        }
        assertTrue(e.message!!.contains("5"))
    }

    @Test
    fun `combineWithIntegrity at margin 2 with one bad share excludes it and reconstructs`() {
        val secret = "margin two corrects one bad share".encodeToByteArray()
        val shares = split(secret, 6, 4).toMutableList()
        shares[2] = tamper(shares[2])
        val result = combineWithIntegrity(shares, 4)
        assertContentEquals(secret, result.secret)
        assertEquals(setOf(2), result.excludedIndices)
    }

    @Test
    fun `combineWithIntegrity at margin 3 with two bad shares exceeds correctable bound`() {
        // floor(margin/2) = floor(3/2) = 1 correctable — two simultaneous bad shares exceed it,
        // so this must refuse to guess rather than silently pick a spurious "majority".
        val secret = "margin three cannot correct two bad".encodeToByteArray()
        val shares = split(secret, 7, 4).toMutableList()
        shares[1] = tamper(shares[1])
        shares[5] = tamper(shares[5])
        assertFailsWith<ReconstructionIntegrityException> {
            combineWithIntegrity(shares, 4)
        }
    }

    @Test
    fun `combineWithIntegrity at margin 4 with two bad shares excludes both and reconstructs`() {
        val secret = "margin four corrects two bad shares".encodeToByteArray()
        val shares = split(secret, 8, 4).toMutableList()
        shares[0] = tamper(shares[0])
        shares[7] = tamper(shares[7])
        val result = combineWithIntegrity(shares, 4)
        assertContentEquals(secret, result.secret)
        assertEquals(setOf(0, 7), result.excludedIndices)
    }

    @Test
    fun `combineWithIntegrity validates like combine`() {
        assertFailsWith<IllegalArgumentException> {
            combineWithIntegrity(listOf(byteArrayOf(0x01, 0x02)), 2)
        }
        assertFailsWith<IllegalArgumentException> {
            combineWithIntegrity(listOf(byteArrayOf(0x01), byteArrayOf(0x02)), 2)
        }
        assertFailsWith<IllegalArgumentException> {
            combineWithIntegrity(listOf(byteArrayOf(0x01, 0x02), byteArrayOf(0x01, 0x02, 0x03)), 2)
        }
        assertFailsWith<IllegalArgumentException> {
            combineWithIntegrity(listOf(byteArrayOf(0x01, 0x05), byteArrayOf(0x02, 0x05), byteArrayOf(0x03, 0x05)), 2)
        }
    }
}
