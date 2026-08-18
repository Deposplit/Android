package com.deposplit.contacts

import android.content.Context
import com.deposplit.driven_ports.KeyConflictRepository
import com.deposplit.value_objects.KeyConflict
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.UUID

class LocalKeyConflictRepository(context: Context) : KeyConflictRepository {

    private val file = File(context.filesDir, "key_conflicts.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class KeyConflictWire(
        val id: String,
        val contactId: String,
        val oldEd25519Key: String,
        val newEd25519Key: String,
        val newX25519Key: String,
        val detectedAt: String,
    )

    @Synchronized
    override fun getAll(): List<KeyConflict> = load().map { it.toDomain() }

    @Synchronized
    override fun save(conflict: KeyConflict) {
        val conflicts = load().toMutableList()
        val idx = conflicts.indexOfFirst { it.id == conflict.id.toString() }
        val wire = conflict.toWire()
        if (idx >= 0) conflicts[idx] = wire else conflicts.add(wire)
        persist(conflicts)
    }

    @Synchronized
    override fun delete(id: UUID) {
        val conflicts = load().filter { it.id != id.toString() }
        persist(conflicts)
    }

    private fun load(): List<KeyConflictWire> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<KeyConflictWire>>(file.readText()) }.getOrDefault(emptyList())
    }

    private fun persist(conflicts: List<KeyConflictWire>) {
        file.writeText(json.encodeToString(conflicts))
    }

    private fun KeyConflictWire.toDomain() = KeyConflict(
        id = UUID.fromString(id),
        contactId = UUID.fromString(contactId),
        oldEd25519Key = Base64.getUrlDecoder().decode(oldEd25519Key),
        newEd25519Key = Base64.getUrlDecoder().decode(newEd25519Key),
        newX25519Key = Base64.getUrlDecoder().decode(newX25519Key),
        detectedAt = Instant.parse(detectedAt),
    )

    private fun KeyConflict.toWire() = KeyConflictWire(
        id = id.toString(),
        contactId = contactId.toString(),
        oldEd25519Key = Base64.getUrlEncoder().withoutPadding().encodeToString(oldEd25519Key),
        newEd25519Key = Base64.getUrlEncoder().withoutPadding().encodeToString(newEd25519Key),
        newX25519Key = Base64.getUrlEncoder().withoutPadding().encodeToString(newX25519Key),
        detectedAt = detectedAt.toString(),
    )
}
