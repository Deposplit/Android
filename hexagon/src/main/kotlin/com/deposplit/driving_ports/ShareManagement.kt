package com.deposplit.driving_ports

import com.deposplit.value_objects.Contact
import com.deposplit.value_objects.HeldShare
import com.deposplit.value_objects.KeyConflict
import com.deposplit.value_objects.Secret
import com.deposplit.value_objects.ShareMetadata
import com.deposplit.value_objects.ShareRequest
import com.deposplit.value_objects.ShareTransactionType
import java.util.UUID

interface ShareManagement {
    // ─── Sender ───────────────────────────────────────────────────────────────
    fun deposit(secret: ByteArray, label: String, contacts: List<Contact>, threshold: Int)
    fun listSecrets(): List<Secret>
    fun syncDistributed()
    fun listDistributed(): List<ShareMetadata>
    fun listSentRequests(): List<ShareRequest>
    fun requestAll(secretId: UUID)
    fun openRequest(shareId: UUID, type: ShareTransactionType): ShareRequest
    // Pure read (item 11) — collects k approved retrieval shares and decrypts them. Never tears
    // down local ShareMetadata or relay rows; use discardSecret for that.
    fun reconstruct(secretId: UUID): ByteArray
    // Fans out a sender-initiated removal request to every known holder of secretId and flips the
    // Secret to DISCARDING immediately (before any holder responds).
    fun discardSecret(secretId: UUID)
    // Local-only teardown for a DISCARDING secret whose holders will never all respond (e.g. a
    // permanently dark holder). Does not wait for or require relay confirmation.
    fun forceForgetSecret(secretId: UUID)

    // ─── Recipient ────────────────────────────────────────────────────────────
    fun syncInbox()
    fun listHeld(): List<HeldShare>
    fun listPendingRequests(): List<ShareRequest>
    fun respond(requestId: UUID, approved: Boolean)
    fun deleteHeldShare(shareId: UUID)
    fun deleteAllHeldFromSender(contactId: UUID)

    // ─── Identity recovery (item 8) — holder side ────────────────────────────
    // Pushes a metadata-only report (no share bytes) for every HeldShare held from contactId back
    // to that contact, so a recovering owner can rebuild her ShareMetadata/Secret records. Call
    // after ContactManagement.updateContact has relinked the re-presented identity to the
    // existing contact.
    fun pushRecoveryMetadata(contactId: UUID)

    // ─── Item 9 — signed rotate(K_old -> K_new) push, client primitive only ─────
    // Signs newEd25519Key/newX25519Key with the device's *current* identity (which becomes
    // oldEd25519Key on the wire) and pushes one signed notice to contactId. There is deliberately
    // no "regenerate my own identity" trigger yet — see deposplit.com/TODO.md item 9's scope-split
    // note — so callers supply the new keys directly; this method is exercised by tests today,
    // not yet by any UI action.
    fun pushRotation(contactId: UUID, newEd25519Key: ByteArray, newX25519Key: ByteArray)

    // ─── Item 10 — key conflicts (never auto-resolved), local-only, no relay involvement ───────
    fun listKeyConflicts(): List<KeyConflict>
    fun dismissKeyConflict(id: UUID)
}
