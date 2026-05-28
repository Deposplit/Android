package com.deposplit.shares

import android.content.Context
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.value_objects.ShareMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

class LocalShareMetadataRepository(context: Context) : ShareMetadataRepository {

    private val file = File(context.filesDir, "distributed_shares.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ShareMetadataWire(
        val id: String,
        val secretId: String,
        val label: String,
        val senderKey: String,
        val recipientKey: String,
        val createdAt: String,
        val pickedUpAt: String? = null,
    )

    @Synchronized
    override fun getAll(): List<ShareMetadata> = load().map { it.toDomain() }

    @Synchronized
    override fun save(share: ShareMetadata) {
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

    private fun load(): List<ShareMetadataWire> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ShareMetadataWire>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun persist(shares: List<ShareMetadataWire>) {
        file.writeText(json.encodeToString(shares))
    }

    private fun ShareMetadataWire.toDomain() = ShareMetadata(
        id = UUID.fromString(id),
        secretId = UUID.fromString(secretId),
        label = label,
        senderKey = Base64.getUrlDecoder().decode(senderKey),
        recipientKey = Base64.getUrlDecoder().decode(recipientKey),
        createdAt = Instant.parse(createdAt),
        pickedUpAt = pickedUpAt?.let { Instant.parse(it) },
    )

    private fun ShareMetadata.toWire() = ShareMetadataWire(
        id = id.toString(),
        secretId = secretId.toString(),
        label = label,
        senderKey = Base64.getUrlEncoder().withoutPadding().encodeToString(senderKey),
        recipientKey = Base64.getUrlEncoder().withoutPadding().encodeToString(recipientKey),
        createdAt = createdAt.toString(),
        pickedUpAt = pickedUpAt?.toString(),
    )
}
