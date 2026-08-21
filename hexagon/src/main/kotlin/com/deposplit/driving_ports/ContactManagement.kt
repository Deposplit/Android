package com.deposplit.driving_ports

import com.deposplit.value_objects.CipherSuite
import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.VerificationLevel
import java.util.UUID

interface ContactManagement {
    fun listContacts(): List<Contact>
    fun addManually(pseudonym: String, verifyKey: ByteArray, encKey: ByteArray, verificationLevel: VerificationLevel, relayBaseUrl: String? = null)
    // cipherSuite (item 14) is required here (unlike addManually) because the QR/link payload is
    // exactly where this self-describing fact comes from — manual entry has no wire payload to
    // read one from, so addManually assumes today's one suite instead.
    fun addFromQr(pseudonym: String, verifyKey: ByteArray, encKey: ByteArray, cipherSuite: CipherSuite, verificationLevel: VerificationLevel, relayBaseUrl: String? = null)
    // Updates an existing contact in place, preserving contactId — never delete-and-re-add, which
    // would mint a fresh id and orphan any HeldShare/ShareMetadata rows anchored to it. See
    // deposplit.com/CLAUDE.md "What is next" item 8. verifyKey/encKey/cipherSuite null leaves the
    // corresponding field unchanged; when any is non-null (a key or algorithm change),
    // verificationLevel must be supplied too — a key or cipher-suite change forces re-choosing the
    // level fresh, never a silent carry-forward (item 14 extends item 8's key-change rule to a
    // cipher-suite-only change: an algorithm change is still continuity of key control, not a
    // fresh personhood check).
    fun updateContact(contactId: UUID, verifyKey: ByteArray? = null, encKey: ByteArray? = null, cipherSuite: CipherSuite? = null, verificationLevel: VerificationLevel? = null)
    fun deleteContact(contactId: UUID)
    // Item 10 — flags a verify key into the contact's revokedEdKeys history, out-of-band-
    // triggered (the user has some independent reason to believe it was stolen). Defaults to the
    // contact's *current* verifyKey when verifyKey is null. From this point, any signed rotation
    // notice claiming continuity from that key is refused auto-accept; only a fresh
    // human-verified relink can move the contact forward. Idempotent — a no-op if already flagged.
    fun markKeyCompromised(contactId: UUID, verifyKey: ByteArray? = null)
}
