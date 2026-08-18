package com.deposplit.driven_ports

import com.deposplit.value_objects.RetainedDepositBlob
import java.util.UUID

interface RetainedDepositRepository {
    fun getAll(): List<RetainedDepositBlob>
    fun save(blob: RetainedDepositBlob)
    fun delete(id: UUID)
}
