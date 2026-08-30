package com.deposplit.shares

import android.content.Context
import com.deposplit.driven_ports.RetainedDepositRepository
import com.deposplit.value_objects.RetainedDepositBlob
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * The local store of [RetainedDepositBlob]s: each per-holder encrypted deposit blob,
 * held until that holder's pickup is confirmed (relay-observed or heartbeat-attested), then
 * discarded by ShareService. Structurally identical to [LocalShareMetadataRepository].
 */
class LocalRetainedDepositRepository(context: Context) : RetainedDepositRepository {

    private val file = File(context.filesDir, "retained_deposits.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class RetainedDepositBlobWire(
        val id: String,
        val secretId: String,
        val contactId: String,
        val label: String,
        val secretCreatedAt: String,
        val ciphertext: String,
        val k: Int,
        val n: Int,
    )

    @Synchronized
    override fun getAll(): List<RetainedDepositBlob> = load().map { it.toDomain() }

    @Synchronized
    override fun save(blob: RetainedDepositBlob) {
        val blobs = load().toMutableList()
        val idx = blobs.indexOfFirst { it.id == blob.id.toString() }
        val wire = blob.toWire()
        if (idx >= 0) blobs[idx] = wire else blobs.add(wire)
        persist(blobs)
    }

    @Synchronized
    override fun delete(id: UUID) {
        val blobs = load().filter { it.id != id.toString() }
        persist(blobs)
    }

    private fun load(): List<RetainedDepositBlobWire> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<RetainedDepositBlobWire>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun persist(blobs: List<RetainedDepositBlobWire>) {
        file.writeText(json.encodeToString(blobs))
    }

    private fun RetainedDepositBlobWire.toDomain() = RetainedDepositBlob(
        id = UUID.fromString(id),
        secretId = UUID.fromString(secretId),
        contactId = UUID.fromString(contactId),
        label = label,
        secretCreatedAt = Instant.parse(secretCreatedAt),
        ciphertext = Base64.getDecoder().decode(ciphertext),
        k = k,
        n = n,
    )

    private fun RetainedDepositBlob.toWire() = RetainedDepositBlobWire(
        id = id.toString(),
        secretId = secretId.toString(),
        contactId = contactId.toString(),
        label = label,
        secretCreatedAt = secretCreatedAt.toString(),
        ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        k = k,
        n = n,
    )
}
