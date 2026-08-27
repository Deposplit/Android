package com.deposplit.contacts

import android.content.Context
import com.deposplit.driven_ports.ContactRepository
import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.VerificationLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

class LocalContactRepository(context: Context) : ContactRepository {

    private val file = File(context.filesDir, "contacts.json")
    private val json = Json { ignoreUnknownKeys = true }

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
        // Item 10 — no default/fallback decode shim: Deposplit is pre-launch, local stores are
        // wiped, not migrated.
        val revokedVerifyKeys: List<String>,
        val keyChangedAt: String?,
        // Item 12 — no default/fallback decode shim: Deposplit is pre-launch, local stores are
        // wiped, not migrated.
        val heartbeatOptedOutAt: String?,
        val lastHeartbeatSentAt: String?,
        val heartbeatEmissionOptedOut: Boolean,
        // Item 14 — no default/fallback decode shim: Deposplit is pre-launch, local stores are
        // wiped, not migrated.
        val cipherSuite: String,
        // Item 15 — defaulted (like relayBaseUrl) so decoding a contacts.json written before this
        // field existed doesn't need a migration shim.
        val nickname: String? = null,
    )

    @Synchronized
    override fun getAll(): List<Contact> = load().map { it.toDomain() }

    @Synchronized
    override fun getByEdKey(edPublicKey: ByteArray): Contact? =
        load().find { Base64.getUrlDecoder().decode(it.verifyKey).contentEquals(edPublicKey) }?.toDomain()

    @Synchronized
    override fun getById(id: UUID): Contact? =
        load().find { it.id == id.toString() }?.toDomain()

    @Synchronized
    override fun save(contact: Contact) {
        val contacts = load().toMutableList()
        val idx = contacts.indexOfFirst { it.id == contact.id.toString() }
        val wire = contact.toWire()
        if (idx >= 0) contacts[idx] = wire else contacts.add(wire)
        persist(contacts)
    }

    @Synchronized
    override fun delete(contactId: UUID) {
        val contacts = load().filter { it.id != contactId.toString() }
        persist(contacts)
    }

    private fun load(): List<ContactWire> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<ContactWire>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun persist(contacts: List<ContactWire>) {
        file.writeText(json.encodeToString(contacts))
    }

    private fun ContactWire.toDomain() = Contact(
        id = UUID.fromString(id),
        pseudonym = pseudonym,
        verifyKey = Base64.getUrlDecoder().decode(verifyKey),
        encKey = Base64.getUrlDecoder().decode(encKey),
        verificationLevel = VerificationLevel.valueOf(verificationLevel),
        verifiedAt = verifiedAt?.let { Instant.parse(it) },
        addedAt = Instant.parse(addedAt),
        relayBaseUrl = relayBaseUrl,
        revokedVerifyKeys = revokedVerifyKeys.map { Base64.getUrlDecoder().decode(it) },
        keyChangedAt = keyChangedAt?.let { Instant.parse(it) },
        heartbeatOptedOutAt = heartbeatOptedOutAt?.let { Instant.parse(it) },
        lastHeartbeatSentAt = lastHeartbeatSentAt?.let { Instant.parse(it) },
        heartbeatEmissionOptedOut = heartbeatEmissionOptedOut,
        cipherSuite = CipherSuite.fromWire(cipherSuite) ?: error("Unknown cipher suite in local store: $cipherSuite"),
        nickname = nickname,
    )

    private fun Contact.toWire() = ContactWire(
        id = id.toString(),
        pseudonym = pseudonym,
        verifyKey = Base64.getUrlEncoder().withoutPadding().encodeToString(verifyKey),
        encKey = Base64.getUrlEncoder().withoutPadding().encodeToString(encKey),
        verificationLevel = verificationLevel.name,
        verifiedAt = verifiedAt?.toString(),
        addedAt = addedAt.toString(),
        relayBaseUrl = relayBaseUrl,
        revokedVerifyKeys = revokedVerifyKeys.map { Base64.getUrlEncoder().withoutPadding().encodeToString(it) },
        keyChangedAt = keyChangedAt?.toString(),
        heartbeatOptedOutAt = heartbeatOptedOutAt?.toString(),
        lastHeartbeatSentAt = lastHeartbeatSentAt?.toString(),
        heartbeatEmissionOptedOut = heartbeatEmissionOptedOut,
        cipherSuite = cipherSuite.wireValue,
        nickname = nickname,
    )
}
