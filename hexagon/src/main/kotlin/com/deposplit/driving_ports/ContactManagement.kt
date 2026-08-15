package com.deposplit.driving_ports

import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.VerificationLevel
import java.util.UUID

interface ContactManagement {
    fun listContacts(): List<Contact>
    fun addManually(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray, verificationLevel: VerificationLevel, relayBaseUrl: String? = null)
    fun addFromQr(pseudonym: String, edPublicKey: ByteArray, xPublicKey: ByteArray, verificationLevel: VerificationLevel, relayBaseUrl: String? = null)
    // Updates an existing contact in place, preserving contactId — never delete-and-re-add, which
    // would mint a fresh id and orphan any HeldShare/ShareMetadata rows anchored to it. See
    // deposplit.com/CLAUDE.md "What is next" item 8. edPublicKey/xPublicKey null leaves the keys
    // unchanged; when either is non-null (a key change), verificationLevel must be supplied too —
    // a key change forces re-choosing the level fresh, never a silent carry-forward.
    fun updateContact(contactId: UUID, edPublicKey: ByteArray? = null, xPublicKey: ByteArray? = null, verificationLevel: VerificationLevel? = null)
    fun deleteContact(contactId: UUID)
}
