package com.deposplit.driven_ports

import com.deposplit.value_objects.Contact
import java.util.UUID

interface ContactRepository {
    fun getAll(): List<Contact>
    fun getByEdKey(edPublicKey: ByteArray): Contact?
    fun getById(id: UUID): Contact?
    fun save(contact: Contact)
    fun delete(contactId: UUID)
}
