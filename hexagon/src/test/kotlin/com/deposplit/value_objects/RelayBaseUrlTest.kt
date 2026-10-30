package com.deposplit.value_objects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rule every way of naming a relay is checked against. iOS's and phon's RelayBaseUrl tests hold
 * the same cases, so a relay one platform accepts another does not refuse.
 */
class RelayBaseUrlTest {

    @Test
    fun `accepts http and https with a host, a port and a path`() {
        assertEquals("https://api.deposplit.com", RelayBaseUrl.parse("https://api.deposplit.com"))
        assertEquals("http://172.22.114.13:9000", RelayBaseUrl.parse("http://172.22.114.13:9000"))
        assertEquals("https://example.org/deposplit", RelayBaseUrl.parse("https://example.org/deposplit"))
        assertEquals("http://[::1]:9000", RelayBaseUrl.parse("http://[::1]:9000"))
        assertEquals("HTTPS://Relay.Example", RelayBaseUrl.parse("HTTPS://Relay.Example"))
    }

    /** Every request appends a path that starts with a slash, so one left on the end would double it. */
    @Test
    fun `trims whitespace and trailing slashes`() {
        assertEquals("https://relay.example", RelayBaseUrl.parse("  https://relay.example/ \n"))
        assertEquals("https://example.org/deposplit", RelayBaseUrl.parse("https://example.org/deposplit//"))
    }

    @Test
    fun `refuses an address without an http scheme`() {
        assertNull(RelayBaseUrl.parse("172.22.114.13:9000"))
        assertNull(RelayBaseUrl.parse("localhost:9000"))
        assertNull(RelayBaseUrl.parse("relay.example"))
        assertNull(RelayBaseUrl.parse("file:///etc/hosts"))
        assertNull(RelayBaseUrl.parse("ftp://relay.example"))
    }

    @Test
    fun `refuses an address without a host`() {
        assertNull(RelayBaseUrl.parse(""))
        assertNull(RelayBaseUrl.parse("http://"))
        assertNull(RelayBaseUrl.parse("http:///share-requests"))
    }

    @Test
    fun `refuses user info, a query or a fragment`() {
        assertNull(RelayBaseUrl.parse("https://user:secret@relay.example"))
        assertNull(RelayBaseUrl.parse("https://relay.example?region=eu"))
        assertNull(RelayBaseUrl.parse("https://relay.example#top"))
    }

    @Test
    fun `refuses characters a URL cannot hold`() {
        assertNull(RelayBaseUrl.parse("https://relay example.com"))
        assertNull(RelayBaseUrl.parse("https://bücher.example"))
    }
}
