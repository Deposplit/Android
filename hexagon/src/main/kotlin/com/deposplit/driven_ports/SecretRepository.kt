package com.deposplit.driven_ports

import com.deposplit.value_objects.Secret
import java.util.UUID

// Local store of sender-side Secret aggregates. Purely local: the relay never stores Secret,
// only opaque ShareRequest rows.
interface SecretRepository {
    fun getAll(): List<Secret>
    fun save(secret: Secret)
    fun delete(secretId: UUID)
}
