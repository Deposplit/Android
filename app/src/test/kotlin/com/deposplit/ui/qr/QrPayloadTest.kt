package com.deposplit.ui.qr

import com.deposplit.value_objects.CipherSuite
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The QR/link payload is the only contact-exchange wire format, and the scanning device is a
 * different app on a different platform. A change here that Android and iOS do not make together
 * breaks key exchange between them, silently and in person.
 *
 * These tests pin the current contract, including the parts that are deliberately permissive.
 */
class QrPayloadTest {

    private val verifyKey = ByteArray(32) { 0x01 }
    private val encKey = ByteArray(32) { 0xF0.toByte() }

    @Test
    fun `round trip preserves every field`() {
        val raw = encodeQrPayload(
            pseudonym = "bob",
            verifyKey = verifyKey,
            encKey = encKey,
            relayBaseUrl = "https://relay.example.com",
            cipherSuite = CipherSuite.current,
        )
        val decoded = decodeQrPayload(raw)

        assertEquals("bob", decoded.pseudonym)
        assertEquals("https://relay.example.com", decoded.relay)
        assertEquals(CipherSuite.current.wireValue, decoded.cipherSuite)
        assertTrue(Base64.getUrlDecoder().decode(decoded.verifyKey).contentEquals(verifyKey))
        assertTrue(Base64.getUrlDecoder().decode(decoded.encKey).contentEquals(encKey))
    }

    @Test
    fun `keys are URL-safe base64 without padding`() {
        // This is what lets the payload travel in the link form as well as the QR: '+', '/' and
        // '=' would need escaping. encKey is deliberately 0xF0 bytes, which encode to characters
        // that differ between the standard and URL-safe alphabets.
        val decoded = decodeQrPayload(encodeQrPayload("bob", verifyKey, encKey))

        for (field in listOf(decoded.verifyKey, decoded.encKey)) {
            assertFalse("unexpected '+' in $field", field.contains('+'))
            assertFalse("unexpected '/' in $field", field.contains('/'))
            assertFalse("unexpected '=' padding in $field", field.contains('='))
        }
        // Round-trips through the URL-safe decoder, which is what the scanning side uses.
        assertTrue(Base64.getUrlDecoder().decode(decoded.encKey).contentEquals(encKey))
    }

    @Test
    fun `an absent relay means use the scanning device's own default`() {
        val decoded = decodeQrPayload(encodeQrPayload("bob", verifyKey, encKey, relayBaseUrl = null))
        assertNull(decoded.relay)
    }

    @Test
    fun `a payload written without a relay key decodes`() {
        val raw = """
            {"v":1,"pseudonym":"bob","verifyKey":"AQE","encKey":"AgI","cipherSuite":"${CipherSuite.current.wireValue}"}
        """.trimIndent()
        assertNull(decodeQrPayload(raw).relay)
    }

    @Test
    fun `encode always writes v as 1`() {
        // v is deliberately frozen: Deposplit is pre-launch and never decodes an older shape, so
        // a payload missing a newly required field already fails on its own. Both platforms
        // hardcode 1 — iOS's QrPayload.swift does the same.
        assertEquals(1, decodeQrPayload(encodeQrPayload("bob", verifyKey, encKey)).v)
    }

    @Test
    fun `v is not validated on decode`() {
        // Pins the permissiveness rather than endorsing it: nothing inspects v, so a payload
        // claiming any version decodes. If that ever needs to change, this test should fail.
        assertEquals(
            99,
            decodeQrPayload(
                """{"v":99,"pseudonym":"bob","verifyKey":"AQE","encKey":"AgI","cipherSuite":"${CipherSuite.current.wireValue}"}"""
            ).v,
        )
    }

    @Test
    fun `an unrecognised cipher suite decodes here and is rejected by the caller`() {
        // decodeQrPayload returns the raw string; CipherSuite.fromWire is applied further up, in
        // the scan flow. Recorded so the split of responsibility is not mistaken for a gap.
        val decoded = decodeQrPayload(
            """{"v":1,"pseudonym":"bob","verifyKey":"AQE","encKey":"AgI","cipherSuite":"rot13+magic-v9"}"""
        )
        assertEquals("rot13+magic-v9", decoded.cipherSuite)
        assertNull(CipherSuite.fromWire(decoded.cipherSuite))
    }

    @Test(expected = SerializationException::class)
    fun `a payload missing a required field fails to decode`() {
        decodeQrPayload("""{"v":1,"pseudonym":"bob","verifyKey":"AQE","encKey":"AgI"}""")
    }

    @Test
    fun `unknown keys are tolerated`() {
        val decoded = decodeQrPayload(
            """{"v":1,"pseudonym":"bob","verifyKey":"AQE","encKey":"AgI","cipherSuite":"${CipherSuite.current.wireValue}","fromTheFuture":42}"""
        )
        assertEquals("bob", decoded.pseudonym)
    }

    @Test(expected = SerializationException::class)
    fun `malformed JSON fails to decode`() {
        decodeQrPayload("not json at all")
    }
}
