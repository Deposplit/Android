package com.deposplit.driven_ports

import com.deposplit.value_objects.HeldShare
import java.util.UUID

interface ShareRepository {
    fun getAll(): List<HeldShare>
    fun getPlaintextShare(shareId: UUID): ByteArray?
    fun save(share: HeldShare)
    fun delete(shareId: UUID)
}
