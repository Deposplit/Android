package com.deposplit.driven_ports

import com.deposplit.value_objects.ShareMetadata
import java.util.UUID

interface ShareMetadataRepository {
    fun getAll(): List<ShareMetadata>
    fun save(share: ShareMetadata)
    fun delete(shareId: UUID)
}
