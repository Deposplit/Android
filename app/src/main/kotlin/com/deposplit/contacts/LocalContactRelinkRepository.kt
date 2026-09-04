package com.deposplit.contacts

import android.content.Context
import com.deposplit.driven_ports.ContactRelinkRepository
import com.deposplit.value_objects.ContactRelink
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

class LocalContactRelinkRepository(context: Context) : ContactRelinkRepository {

    private val file = File(context.filesDir, "contact_relinks.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ContactRelinkWire(
        val contactId: String,
        val observedAt: String,
    )

    @Synchronized
    override fun getAll(): List<ContactRelink> = load().map { it.toDomain() }

    @Synchronized
    override fun get(contactId: UUID): ContactRelink? =
        load().find { it.contactId == contactId.toString() }?.toDomain()

    @Synchronized
    override fun save(relink: ContactRelink) {
        val relinks = load().filter { it.contactId != relink.contactId.toString() } + relink.toWire()
        persist(relinks)
    }

    private fun load(): List<ContactRelinkWire> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<ContactRelinkWire>>(file.readText()) }
            .getOrDefault(emptyList())
    }

    private fun persist(relinks: List<ContactRelinkWire>) {
        file.writeText(json.encodeToString(relinks))
    }

    private fun ContactRelinkWire.toDomain() = ContactRelink(UUID.fromString(contactId), Instant.parse(observedAt))

    private fun ContactRelink.toWire() = ContactRelinkWire(contactId.toString(), observedAt.toString())
}
