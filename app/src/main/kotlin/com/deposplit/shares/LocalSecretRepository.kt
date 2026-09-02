package com.deposplit.shares

import android.content.Context
import com.deposplit.driven_ports.SecretRepository
import com.deposplit.value_objects.MimeType
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.SecretState
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

class LocalSecretRepository(context: Context) : SecretRepository {

    private val file = File(context.filesDir, "secrets.json")
    private val json = Json { ignoreUnknownKeys = true }

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

    @Synchronized
    override fun getAll(): List<Secret> = load().map { it.toDomain() }

    @Synchronized
    override fun save(secret: Secret) {
        val secrets = load().toMutableList()
        val idx = secrets.indexOfFirst { it.id == secret.id.toString() }
        val wire = secret.toWire()
        if (idx >= 0) secrets[idx] = wire else secrets.add(wire)
        persist(secrets)
    }

    @Synchronized
    override fun delete(secretId: UUID) {
        val secrets = load().filter { it.id != secretId.toString() }
        persist(secrets)
    }

    private fun load(): List<SecretWire> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<SecretWire>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun persist(secrets: List<SecretWire>) {
        file.writeText(json.encodeToString(secrets))
    }

    private fun SecretWire.toDomain() = Secret(
        id = UUID.fromString(id),
        label = label,
        mimeType = MimeType(mimeType),
        k = k,
        n = n,
        secretCreatedAt = Instant.parse(secretCreatedAt),
        state = SecretState.valueOf(state),
    )

    private fun Secret.toWire() = SecretWire(
        id = id.toString(),
        label = label,
        mimeType = mimeType.value,
        k = k,
        n = n,
        secretCreatedAt = secretCreatedAt.toString(),
        state = state.name,
    )
}
