package com.deposplit.shares

import android.content.Context
import com.deposplit.driven_ports.ShareRepository
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.MimeType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

class LocalShareRepository(context: Context) : ShareRepository {

    private val file = File(context.filesDir, "shares.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class HeldShareWire(
        val id: String,
        val secretId: String,
        val label: String,
        val contactId: String,
        val senderPseudonym: String,
        val createdAt: String,
        val pickedUpAt: String,
        val plaintextShare: String,
        val k: Int,
        val n: Int,
        val mimeType: String,
    )

    @Synchronized
    override fun getAll(): List<HeldShare> = load().map { it.toDomain() }

    @Synchronized
    override fun getPlaintextShare(secretId: UUID): ByteArray? =
        load().find { it.secretId == secretId.toString() }
            ?.plaintextShare
            ?.let { Base64.getDecoder().decode(it) }

    @Synchronized
    override fun save(share: HeldShare) {
        val shares = load().toMutableList()
        val idx = shares.indexOfFirst { it.id == share.id.toString() }
        val wire = share.toWire()
        if (idx >= 0) shares[idx] = wire else shares.add(wire)
        persist(shares)
    }

    @Synchronized
    override fun delete(shareId: UUID) {
        val shares = load().filter { it.id != shareId.toString() }
        persist(shares)
    }

    private fun load(): List<HeldShareWire> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<HeldShareWire>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun persist(shares: List<HeldShareWire>) {
        file.writeText(json.encodeToString(shares))
    }

    private fun HeldShareWire.toDomain() = HeldShare(
        id = UUID.fromString(id),
        secretId = UUID.fromString(secretId),
        label = label,
        contactId = UUID.fromString(contactId),
        senderPseudonym = senderPseudonym,
        createdAt = Instant.parse(createdAt),
        pickedUpAt = Instant.parse(pickedUpAt),
        plaintextShare = Base64.getDecoder().decode(plaintextShare),
        k = k,
        n = n,
        mimeType = MimeType(mimeType),
    )

    private fun HeldShare.toWire() = HeldShareWire(
        id = id.toString(),
        secretId = secretId.toString(),
        label = label,
        contactId = contactId.toString(),
        senderPseudonym = senderPseudonym,
        createdAt = createdAt.toString(),
        pickedUpAt = pickedUpAt.toString(),
        plaintextShare = Base64.getEncoder().encodeToString(plaintextShare),
        k = k,
        n = n,
        mimeType = mimeType.value,
    )
}
