package com.deposplit.driven_ports

import com.deposplit.value_objects.KeyConflict
import java.util.UUID

interface KeyConflictRepository {
    fun getAll(): List<KeyConflict>
    fun save(conflict: KeyConflict)
    fun delete(id: UUID)
}
