package com.deposplit.driving_ports

import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.VerificationLevel
import java.util.UUID

interface ContactManagement {
    fun listContacts(): List<Contact>
    fun addManually(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray, verificationLevel: VerificationLevel, relayBaseUrl: String? = null)
    fun addFromQr(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray, verificationLevel: VerificationLevel, relayBaseUrl: String? = null)
    fun deleteContact(contactId: UUID)
}
