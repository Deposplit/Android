package com.deposplit.settings

import com.deposplit.value_objects.Catalog
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.MimeType
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.SecretState
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.VerificationLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * JSON (de)serialization for [Catalog] — the optional non-secret backup export/import.
 * Lives in the app layer, not the hexagon: serialization is an adapter concern, and the hexagon
 * has no JSON dependency (see [com.deposplit.value_objects.Catalog]'s doc comment). Mirrors the
 * wire shapes already used by `LocalContactRepository`/`LocalSecretRepository`/
 * `LocalShareMetadataRepository`.
 */
object CatalogCodec {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class ContactWire(
        val id: String,
        val pseudonym: String,
        val verifyKey: String,
        val encKey: String,
        val verificationLevel: String,
        val verifiedAt: String?,
        val addedAt: String,
        val relayBaseUrl: String? = null,
        // No default/fallback decode shim: Deposplit is pre-launch, local stores are
        // wiped, not migrated.
        val cipherSuite: String,
        // Carried for full catalog round-trip fidelity, same reasoning as cipherSuite above.
        val nickname: String? = null,
        // Trust state. Defaulted so an export written before these were carried still decodes.
        // revokedVerifyKeys is the security-relevant one: losing it on a restore silently re-enables
        // auto-accept of rotation notices signed by a key the user marked compromised.
        val revokedVerifyKeys: List<String> = emptyList(),
        val keyChangedAt: String? = null,
        // Custody-monitoring state. Losing these makes a privacy-opted-out holder read as
        // silent-overdue after a restore.
        val heartbeatOptedOutAt: String? = null,
        val lastHeartbeatSentAt: String? = null,
        val heartbeatEmissionOptedOut: Boolean = false,
    )

    @Serializable
    private data class SecretWire(
        val id: String,
        val label: String,
        val k: Int,
        val n: Int,
        val mimeType: String,
        val secretCreatedAt: String,
        val state: String,
    )

    @Serializable
    private data class ShareMetadataWire(
        val id: String,
        val secretId: String,
        val contactId: String,
        // Last proof-of-custody. Losing it on a restore makes every holder read as
        // never-confirmed, so every secret shows as lost until fresh heartbeats arrive.
        val lastConfirmedAt: String? = null,
    )

    @Serializable
    private data class CatalogWire(
        val contacts: List<ContactWire>,
        val secrets: List<SecretWire>,
        val shareMetadata: List<ShareMetadataWire>,
    )

    fun encode(catalog: Catalog): ByteArray = json.encodeToString(
        CatalogWire(
            contacts = catalog.contacts.map { it.toWire() },
            secrets = catalog.secrets.map { it.toWire() },
            shareMetadata = catalog.shareMetadata.map {
                ShareMetadataWire(it.id.toString(), it.secretId.toString(), it.contactId.toString(), it.lastConfirmedAt?.toString())
            },
        )
    ).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Catalog {
        val wire = json.decodeFromString<CatalogWire>(bytes.toString(Charsets.UTF_8))
        return Catalog(
            contacts = wire.contacts.map { it.toDomain() },
            secrets = wire.secrets.map { it.toDomain() },
            shareMetadata = wire.shareMetadata.map {
                ShareMetadata(
                    UUID.fromString(it.id),
                    UUID.fromString(it.secretId),
                    UUID.fromString(it.contactId),
                    it.lastConfirmedAt?.let(Instant::parse),
                )
            },
        )
    }

    private fun Contact.toWire() = ContactWire(
        id = id.toString(),
        pseudonym = pseudonym,
        verifyKey = Base64.getUrlEncoder().withoutPadding().encodeToString(verifyKey),
        encKey = Base64.getUrlEncoder().withoutPadding().encodeToString(encKey),
        verificationLevel = verificationLevel.name,
        verifiedAt = verifiedAt?.toString(),
        addedAt = addedAt.toString(),
        relayBaseUrl = relayBaseUrl,
        cipherSuite = cipherSuite.wireValue,
        nickname = nickname,
        revokedVerifyKeys = revokedVerifyKeys.map { Base64.getUrlEncoder().withoutPadding().encodeToString(it) },
        keyChangedAt = keyChangedAt?.toString(),
        heartbeatOptedOutAt = heartbeatOptedOutAt?.toString(),
        lastHeartbeatSentAt = lastHeartbeatSentAt?.toString(),
        heartbeatEmissionOptedOut = heartbeatEmissionOptedOut,
    )

    private fun ContactWire.toDomain() = Contact(
        id = UUID.fromString(id),
        pseudonym = pseudonym,
        verifyKey = Base64.getUrlDecoder().decode(verifyKey),
        encKey = Base64.getUrlDecoder().decode(encKey),
        verificationLevel = VerificationLevel.valueOf(verificationLevel),
        verifiedAt = verifiedAt?.let { Instant.parse(it) },
        addedAt = Instant.parse(addedAt),
        relayBaseUrl = relayBaseUrl,
        cipherSuite = CipherSuite.fromWire(cipherSuite) ?: error("Unknown cipher suite in catalog: $cipherSuite"),
        nickname = nickname,
        revokedVerifyKeys = revokedVerifyKeys.map { Base64.getUrlDecoder().decode(it) },
        keyChangedAt = keyChangedAt?.let(Instant::parse),
        heartbeatOptedOutAt = heartbeatOptedOutAt?.let(Instant::parse),
        lastHeartbeatSentAt = lastHeartbeatSentAt?.let(Instant::parse),
        heartbeatEmissionOptedOut = heartbeatEmissionOptedOut,
    )

    private fun Secret.toWire() = SecretWire(
        id = id.toString(), label = label, mimeType = mimeType.value, k = k, n = n,
        secretCreatedAt = secretCreatedAt.toString(), state = state.name,
    )

    private fun SecretWire.toDomain() = Secret(
        id = UUID.fromString(id), label = label, mimeType = MimeType(mimeType), k = k, n = n,
        secretCreatedAt = Instant.parse(secretCreatedAt), state = SecretState.valueOf(state),
    )
}
