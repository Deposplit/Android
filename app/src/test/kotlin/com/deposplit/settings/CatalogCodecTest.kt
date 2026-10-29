package com.deposplit.settings

import com.deposplit.value_objects.Catalog
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.MimeType
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.SecretState
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.VerificationLevel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * `CatalogCodec` hand-writes its wire DTOs rather than serializing the domain types, so every
 * field added to `Contact`, `Secret` or `ShareMetadata` has to be mirrored there by hand.
 * Forgetting costs nothing at compile time and silently drops the field on export/import.
 *
 * The audit below therefore derives its expectation from the domain types by reflection: a new
 * field that the codec does not carry fails here, by name, without anyone remembering to add a
 * case.
 *
 * **Do not replace it with `assertEquals(original, decoded)`.** `Contact` overrides `equals` to
 * compare `id` alone, so the obvious whole-object round-trip assertion passes even when every
 * other field has been lost — which is exactly how six dropped fields went unnoticed.
 *
 * These tests use JUnit 4 rather than `kotlin.test` as `:hexagon` does: `:app` deliberately does
 * not apply the Kotlin plugin (AGP registers the `kotlin` extension itself), so `kotlin("test")`
 * is unavailable here.
 */
class CatalogCodecTest {

    // --- the audit ------------------------------------------------------------------------

    @Test
    fun `round trip preserves every Contact field`() {
        val original = fullyPopulatedContact()
        val decoded = roundTrip(Catalog(listOf(original), emptyList(), emptyList())).contacts.single()
        assertAllFieldsPreserved(original, decoded)
    }

    @Test
    fun `round trip preserves every Secret field`() {
        val original = fullyPopulatedSecret()
        val decoded = roundTrip(Catalog(emptyList(), listOf(original), emptyList())).secrets.single()
        assertAllFieldsPreserved(original, decoded)
    }

    @Test
    fun `round trip preserves every ShareMetadata field`() {
        val original = fullyPopulatedShareMetadata()
        val decoded = roundTrip(Catalog(emptyList(), emptyList(), listOf(original))).shareMetadata.single()
        assertAllFieldsPreserved(original, decoded)
    }

    @Test
    fun `round trip preserves a whole catalog`() {
        val original = Catalog(
            contacts = listOf(fullyPopulatedContact()),
            secrets = listOf(fullyPopulatedSecret()),
            shareMetadata = listOf(fullyPopulatedShareMetadata()),
        )
        val decoded = roundTrip(original)
        assertEquals(1, decoded.contacts.size)
        assertEquals(1, decoded.secrets.size)
        assertEquals(1, decoded.shareMetadata.size)
        assertAllFieldsPreserved(original.contacts.single(), decoded.contacts.single())
        assertAllFieldsPreserved(original.secrets.single(), decoded.secrets.single())
        assertAllFieldsPreserved(original.shareMetadata.single(), decoded.shareMetadata.single())
    }

    @Test
    fun `an empty catalog round trips`() {
        val decoded = roundTrip(Catalog(emptyList(), emptyList(), emptyList()))
        assertTrue(decoded.contacts.isEmpty())
        assertTrue(decoded.secrets.isEmpty())
        assertTrue(decoded.shareMetadata.isEmpty())
    }

    // --- the security-relevant field, called out on its own -----------------------------------

    @Test
    fun `revoked keys survive a round trip`() {
        // A restore that loses this silently re-enables auto-accept of rotation notices signed by
        // a key the user marked compromised. See docs/trust-model.md.
        val revoked = listOf(ByteArray(32) { 0x03 }, ByteArray(32) { 0x04 })
        val original = fullyPopulatedContact().copy(revokedVerifyKeys = revoked)
        val decoded = roundTrip(Catalog(listOf(original), emptyList(), emptyList())).contacts.single()

        assertEquals(2, decoded.revokedVerifyKeys.size)
        assertTrue(decoded.revokedVerifyKeys.zip(revoked).all { (a, b) -> a.contentEquals(b) })
    }

    // --- decode failures ----------------------------------------------------------------------

    @Test(expected = IllegalStateException::class)
    fun `decoding an unknown cipher suite fails loudly`() {
        CatalogCodec.decode(
            contactJson(cipherSuite = "rot13+magic-v9").toByteArray(Charsets.UTF_8)
        )
    }

    @Test(expected = SerializationException::class)
    fun `decoding a contact with a missing required field fails`() {
        val withoutPseudonym = contactJson().replace("\"pseudonym\":\"bob\",", "")
        CatalogCodec.decode(withoutPseudonym.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `unknown keys are tolerated`() {
        val withExtra = contactJson().replace("\"pseudonym\":\"bob\",", "\"pseudonym\":\"bob\",\"fromTheFuture\":42,")
        val decoded = CatalogCodec.decode(withExtra.toByteArray(Charsets.UTF_8))
        assertEquals("bob", decoded.contacts.single().pseudonym)
    }

    @Test
    fun `a contact with only its required fields decodes with the defaults`() {
        // The format lets a field still at its default be left out, and this codec leaves out
        // every one that is, so a reader on either platform has to fill them back in.
        val decoded = CatalogCodec.decode(contactJson().toByteArray(Charsets.UTF_8))
        val contact = decoded.contacts.single()

        assertEquals("bob", contact.pseudonym)
        assertTrue(contact.revokedVerifyKeys.isEmpty())
        assertEquals(null, contact.keyChangedAt)
        assertEquals(null, contact.heartbeatOptedOutAt)
        assertEquals(null, contact.lastHeartbeatSentAt)
        assertFalse(contact.heartbeatEmissionOptedOut)
    }

    // --- the format shared with iOS -----------------------------------------------------------

    @Test
    fun `the shared fixture decodes field by field`() {
        val catalog = CatalogCodec.decode(SHARED_FIXTURE.toByteArray(Charsets.UTF_8))

        val contact = catalog.contacts.single()
        assertEquals(UUID.fromString("6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b"), contact.id)
        assertEquals("Bob", contact.pseudonym)
        assertTrue(contact.verifyKey.contentEquals(ByteArray(32) { 0xFB.toByte() }))
        assertTrue(contact.encKey.contentEquals(ByteArray(32) { 0x02 }))
        assertEquals(VerificationLevel.VERY_HIGH, contact.verificationLevel)
        assertEquals(1793258855457, contact.verifiedAt?.toEpochMilli())
        assertEquals(1793215970123, contact.addedAt.toEpochMilli())
        assertEquals("https://relay.example.org", contact.relayBaseUrl)
        assertEquals(CipherSuite.current, contact.cipherSuite)
        assertEquals("Bobby", contact.nickname)
        assertEquals(1, contact.revokedVerifyKeys.size)
        assertTrue(contact.revokedVerifyKeys.single().contentEquals(ByteArray(32) { 0xFC.toByte() }))
        assertEquals(1793260800001, contact.keyChangedAt?.toEpochMilli())
        assertEquals(1793265342999, contact.heartbeatOptedOutAt?.toEpochMilli())
        assertEquals(1793268000500, contact.lastHeartbeatSentAt?.toEpochMilli())
        assertTrue(contact.heartbeatEmissionOptedOut)

        val secret = catalog.secrets.single()
        assertEquals(UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"), secret.id)
        assertEquals("S1", secret.label)
        assertEquals(2, secret.k)
        assertEquals(3, secret.n)
        assertEquals(MimeType("text/plain"), secret.mimeType)
        assertEquals(1793258835657, secret.secretCreatedAt.toEpochMilli())
        assertEquals(SecretState.DESTROYING, secret.state)

        val meta = catalog.shareMetadata.single()
        assertEquals(UUID.fromString("10a39134-615c-4d28-8e19-a1f9f4a926ba"), meta.id)
        assertEquals(secret.id, meta.secretId)
        assertEquals(contact.id, meta.contactId)
        assertEquals(1793258996809, meta.lastConfirmedAt?.toEpochMilli())
    }

    @Test
    fun `the shared fixture encodes back to itself`() {
        val encoded = CatalogCodec.encode(CatalogCodec.decode(SHARED_FIXTURE.toByteArray(Charsets.UTF_8)))

        assertEquals(Json.parseToJsonElement(SHARED_FIXTURE), Json.parseToJsonElement(encoded.toString(Charsets.UTF_8)))
    }

    @Test
    fun `times are read with any number of fractional digits and ids in either case`() {
        val catalog = CatalogCodec.decode(
            """
            {"version": 1, "contacts": [], "shareMetadata": [], "secrets": [{
              "id": "0A1B2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D", "label": "S1", "k": 2, "n": 3,
              "mimeType": "text/plain", "secretCreatedAt": "2026-10-29T07:27:15Z", "state": "active"
            }, {
              "id": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4e", "label": "S2", "k": 2, "n": 3,
              "mimeType": "text/plain", "secretCreatedAt": "2026-10-29T07:27:15.657123Z", "state": "active"
            }]}
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )

        assertEquals(listOf(1793258835000, 1793258835657), catalog.secrets.map { it.secretCreatedAt.toEpochMilli() })
        assertEquals(UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"), catalog.secrets.first().id)
    }

    @Test
    fun `whole and fractional seconds are both written with exactly three digits`() {
        val secret = fullyPopulatedSecret().copy(secretCreatedAt = Instant.parse("2026-10-29T07:27:15.657999Z"))
        val whole = fullyPopulatedShareMetadata().copy(lastConfirmedAt = Instant.parse("2026-10-29T07:27:15Z"))
        val encoded = CatalogCodec.encode(Catalog(emptyList(), listOf(secret), listOf(whole))).toString(Charsets.UTF_8)

        assertTrue(encoded, encoded.contains("\"2026-10-29T07:27:15.657Z\""))
        assertTrue(encoded, encoded.contains("\"2026-10-29T07:27:15.000Z\""))
    }

    @Test(expected = IllegalStateException::class)
    fun `a catalog of another version is refused`() {
        CatalogCodec.decode("""{"version": 2, "contacts": [], "secrets": [], "shareMetadata": []}""".toByteArray(Charsets.UTF_8))
    }

    @Test(expected = SerializationException::class)
    fun `a catalog without a version is refused`() {
        CatalogCodec.decode("""{"contacts": [], "secrets": [], "shareMetadata": []}""".toByteArray(Charsets.UTF_8))
    }

    // --- helpers ------------------------------------------------------------------------------

    private fun roundTrip(catalog: Catalog): Catalog = CatalogCodec.decode(CatalogCodec.encode(catalog))

    /**
     * Compares every declared field of [original] against [decoded] by reflection, so a field
     * added to the domain type is covered automatically.
     */
    private fun assertAllFieldsPreserved(original: Any, decoded: Any) {
        val type = original.javaClass.simpleName
        val fields = original.javaClass.declaredFields.filterNot { it.isSynthetic }
        assertTrue("no declared fields found on $type — reflection assumption broken", fields.isNotEmpty())

        val dropped = fields.filter { field ->
            field.isAccessible = true
            !valuesEqual(field.get(original), field.get(decoded))
        }.map { it.name }

        assertTrue(
            "CatalogCodec did not preserve these $type fields across an export/import round " +
                "trip: $dropped. Add them to the matching *Wire DTO and to both mappers in " +
                "CatalogCodec.kt.",
            dropped.isEmpty(),
        )
    }

    private fun valuesEqual(a: Any?, b: Any?): Boolean = when {
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        a is List<*> && b is List<*> -> a.size == b.size && a.zip(b).all { (x, y) -> valuesEqual(x, y) }
        else -> a == b
    }

    private fun fullyPopulatedContact() = Contact(
        id = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        pseudonym = "bob",
        verifyKey = ByteArray(32) { 0x01 },
        encKey = ByteArray(32) { 0x02 },
        verificationLevel = VerificationLevel.HIGH,
        verifiedAt = Instant.parse("2026-01-02T03:04:05Z"),
        addedAt = Instant.parse("2026-01-01T00:00:00Z"),
        relayBaseUrl = "https://relay.example.com",
        revokedVerifyKeys = listOf(ByteArray(32) { 0x03 }, ByteArray(32) { 0x04 }),
        keyChangedAt = Instant.parse("2026-02-03T04:05:06Z"),
        heartbeatOptedOutAt = Instant.parse("2026-03-04T05:06:07Z"),
        lastHeartbeatSentAt = Instant.parse("2026-04-05T06:07:08Z"),
        heartbeatEmissionOptedOut = true,
        cipherSuite = CipherSuite.current,
        nickname = "Paul from work",
    )

    private fun fullyPopulatedSecret() = Secret(
        id = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        label = "BitLocker key",
        // Deliberately not MimeType.DEFAULT: the round-trip audit would pass vacuously if the
        // fixture happened to carry whatever a dropped field decodes back to.
        mimeType = MimeType("image/png"),
        k = 2,
        n = 3,
        secretCreatedAt = Instant.parse("2026-05-06T07:08:09Z"),
        state = SecretState.DESTROYING,
    )

    private fun fullyPopulatedShareMetadata() = ShareMetadata(
        id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        secretId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        contactId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        lastConfirmedAt = Instant.parse("2026-06-07T08:09:10Z"),
    )

    /** A catalog with one contact carrying only the fields that have no default. */
    private fun contactJson(cipherSuite: String = CipherSuite.current.wireValue) = """
        {
          "version": 1,
          "contacts": [
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "pseudonym":"bob",
              "verifyKey": "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE",
              "encKey": "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI",
              "verificationLevel": "high",
              "verifiedAt": null,
              "addedAt": "2026-01-01T00:00:00Z",
              "cipherSuite": "$cipherSuite"
            }
          ],
          "secrets": [],
          "shareMetadata": []
        }
    """.trimIndent()
}

// The catalogue file is shared with iOS, so this fixture is too: iOS's CatalogCodecTests decodes
// the very same text. Change it in both places or in neither. Every field holds something other
// than its default, so a field one side forgets to write cannot pass unnoticed, and the keys are
// bytes whose base64url differs from plain base64.
private val SHARED_FIXTURE = """
{
  "version": 1,
  "contacts": [
    {
      "id": "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b",
      "pseudonym": "Bob",
      "verifyKey": "-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_v7-_s",
      "encKey": "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI",
      "verificationLevel": "veryHigh",
      "verifiedAt": "2026-10-29T07:27:35.457Z",
      "addedAt": "2026-10-28T19:32:50.123Z",
      "relayBaseUrl": "https://relay.example.org",
      "cipherSuite": "ed25519+x25519-v1",
      "nickname": "Bobby",
      "revokedVerifyKeys": ["_Pz8_Pz8_Pz8_Pz8_Pz8_Pz8_Pz8_Pz8_Pz8_Pz8_Pw"],
      "keyChangedAt": "2026-10-29T08:00:00.001Z",
      "heartbeatOptedOutAt": "2026-10-29T09:15:42.999Z",
      "lastHeartbeatSentAt": "2026-10-29T10:00:00.500Z",
      "heartbeatEmissionOptedOut": true
    }
  ],
  "secrets": [
    {
      "id": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
      "label": "S1",
      "k": 2,
      "n": 3,
      "mimeType": "text/plain",
      "secretCreatedAt": "2026-10-29T07:27:15.657Z",
      "state": "destroying"
    }
  ],
  "shareMetadata": [
    {
      "id": "10a39134-615c-4d28-8e19-a1f9f4a926ba",
      "secretId": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
      "contactId": "6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b",
      "lastConfirmedAt": "2026-10-29T07:29:56.809Z"
    }
  ]
}
"""
