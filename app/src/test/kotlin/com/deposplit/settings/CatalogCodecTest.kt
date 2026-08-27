package com.deposplit.settings

import com.deposplit.value_objects.Catalog
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.SecretState
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.VerificationLevel
import kotlinx.serialization.SerializationException
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
    fun `a catalog written before the newer fields existed still decodes`() {
        // Pre-launch means no migrations, but an export taken from an earlier build should still
        // load rather than throw — the newer fields simply come back at their defaults.
        val decoded = CatalogCodec.decode(contactJson().toByteArray(Charsets.UTF_8))
        val contact = decoded.contacts.single()

        assertEquals("bob", contact.pseudonym)
        assertTrue(contact.revokedVerifyKeys.isEmpty())
        assertEquals(null, contact.keyChangedAt)
        assertEquals(null, contact.heartbeatOptedOutAt)
        assertEquals(null, contact.lastHeartbeatSentAt)
        assertFalse(contact.heartbeatEmissionOptedOut)
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
        k = 2,
        n = 3,
        secretCreatedAt = Instant.parse("2026-05-06T07:08:09Z"),
        state = SecretState.DISCARDING,
    )

    private fun fullyPopulatedShareMetadata() = ShareMetadata(
        id = UUID.fromString("33333333-3333-3333-3333-333333333333"),
        secretId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
        contactId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
        lastConfirmedAt = Instant.parse("2026-06-07T08:09:10Z"),
    )

    /** A catalog in the shape an older build wrote: contacts only, none of the newer fields. */
    private fun contactJson(cipherSuite: String = CipherSuite.current.wireValue) = """
        {
          "contacts": [
            {
              "id": "11111111-1111-1111-1111-111111111111",
              "pseudonym":"bob",
              "verifyKey": "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE",
              "encKey": "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI",
              "verificationLevel": "HIGH",
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
