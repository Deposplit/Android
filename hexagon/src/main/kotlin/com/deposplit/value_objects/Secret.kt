package com.deposplit.value_objects

import java.time.Instant
import java.util.UUID

// Two-state lifecycle. No `DISCARDED`
// tombstone: once every holder confirms deletion (or the sender force-forgets), the Secret
// record is removed outright.
enum class SecretState { ACTIVE, DISCARDING }

// Sender-side per-secret aggregate — the single source of truth for
// k/n/label/mimeType/secretCreatedAt, keyed by secretId. ShareMetadata rows reference this rather
// than duplicating its fields.
data class Secret(
    val id: UUID,
    val label: String,
    val mimeType: MimeType,
    val k: Int,
    val n: Int,
    val secretCreatedAt: Instant,
    val state: SecretState,
) {
    override fun equals(other: Any?) = other is Secret && id == other.id
    override fun hashCode() = id.hashCode()
}
