package com.deposplit.shares

import android.content.Context
import com.deposplit.driven_ports.ShareMetadataRepository
import com.deposplit.value_objects.ShareMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

class LocalShareMetadataRepository(context: Context) : ShareMetadataRepository {

    private val file = File(context.filesDir, "distributed_shares.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ShareMetadataWire(
        val id: String,
        val secretId: String,
        val contactId: String,
        // Item 12 — no default/fallback decode shim: Deposplit is pre-launch, local stores are
        // wiped, not migrated.
        val lastConfirmedAt: String?,
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
        contactId = UUID.fromString(contactId),
        lastConfirmedAt = lastConfirmedAt?.let { Instant.parse(it) },
    )

    private fun ShareMetadata.toWire() = ShareMetadataWire(
        id = id.toString(),
        secretId = secretId.toString(),
        contactId = contactId.toString(),
        lastConfirmedAt = lastConfirmedAt?.toString(),
    )
}
