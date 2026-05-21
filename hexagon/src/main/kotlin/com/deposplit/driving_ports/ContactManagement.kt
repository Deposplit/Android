package com.deposplit.driving_ports

import com.deposplit.value_objects.Contact
import java.util.UUID

interface ContactManagement {
    fun listContacts(): List<Contact>
    fun addManually(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray)
    fun addFromQr(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray)
    fun deleteContact(contactId: UUID)
}
