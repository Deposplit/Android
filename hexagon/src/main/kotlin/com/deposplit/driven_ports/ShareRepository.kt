package com.deposplit.driven_ports

import com.deposplit.value_objects.HeldShare
import java.util.UUID

interface ShareRepository {
    fun getAll(): List<HeldShare>
    // Keyed on secretId, not the pickup relay-row id — secretId survives device loss/recovery and
    // is unique per (secretId, sender) at a given holder. See item 8's "re-key retrieve".
    fun getPlaintextShare(secretId: UUID): ByteArray?
    fun save(share: HeldShare)
    fun delete(shareId: UUID)
}
