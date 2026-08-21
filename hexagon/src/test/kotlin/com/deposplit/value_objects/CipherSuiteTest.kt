package com.deposplit.value_objects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CipherSuiteTest {

    @Test
    fun `wireValue round-trips through fromWire`() {
        for (suite in CipherSuite.entries) {
            assertEquals(suite, CipherSuite.fromWire(suite.wireValue))
        }
    }

    @Test
    fun `fromWire returns null for an unknown value`() {
        assertNull(CipherSuite.fromWire("made-up-suite"))
    }

    @Test
    fun `current is the ed25519+x25519-v1 suite with 32-byte keys`() {
        assertEquals("ed25519+x25519-v1", CipherSuite.current.wireValue)
        assertEquals(32, CipherSuite.current.verifyKeyLength)
        assertEquals(32, CipherSuite.current.encKeyLength)
    }
}
